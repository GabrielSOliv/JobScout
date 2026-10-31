package com.jobscout.feature.search.presentation

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.jobscout.domain.*
import com.jobscout.domain.Job
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScoutViewModelTest {
    @Test fun `manual API-only search still runs independent sources without requiring dorks`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { release.complete(Unit) }
        val vm = vm(repo); runCurrent()
        vm.search(SearchQuery(titles = listOf("Java"), methods = setOf(DiscoveryMethod.API))); runCurrent()
        assertEquals(1, repo.searchCalls)
        assertNull(repo.preferences.value.googleSession)
    }

    @Test fun `manual search restarts same Google query including a pending human session`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { preferences.value = AppPreferences(sources = emptyList()) }
        val vm = vm(repo); runCurrent()
        val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
        vm.search(query); runCurrent(); val first = repo.preferences.value.googleSession!!
        repo.updatePreferences { it.copy(googleSession = first.copy(phase = GooglePhase.NEEDS_USER)) }
        vm.search(query); runCurrent()
        assertNotEquals(first.id, repo.preferences.value.googleSession!!.id)
        assertEquals(first.tasks, repo.preferences.value.googleSession!!.tasks)
        vm.finishGoogle(); runCurrent(); assertNull(repo.preferences.value.googleSession)
        vm.search(query); runCurrent()
        assertNotEquals(first.id, repo.preferences.value.googleSession!!.id)
        assertEquals(0, repo.searchCalls)
    }

    @Test fun `clearing unsaved cancels active scan before removal and keeps favorites and filters`() = runTest(dispatcher) {
        val base = FakeRepository(); var cancelled = false; var cleared = false
        val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
        val favorite = TrackedJob(Job("kept", "fixture", "1", "Java", "Fixture", "https://example.com/jobs/1"), saved = true, notes = "Keep")
        base.jobs.value = listOf(favorite, favorite.copy(job = favorite.job.copy(id = "delete"), saved = false))
        base.preferences.value = AppPreferences(profile = CandidateProfile(skills = listOf("SQL")))
        val repo = object : ScoutRepository by base {
            override suspend fun search(onSource: suspend (SourceResult) -> Unit): SearchSummary {
                try { awaitCancellation() } finally { cancelled = true }
            }
            override suspend fun clearUnsavedJobs(): Int {
                assertTrue(cancelled); cleared = true
                base.jobs.value = listOf(favorite); return 1
            }
        }
        val vm = vm(repo); runCurrent()
        vm.search(query); runCurrent(); assertNotNull(base.preferences.value.googleSession)
        vm.clearUnsavedJobs(); runCurrent()
        assertTrue(cleared); assertEquals(listOf(favorite), base.jobs.value)
        assertNull(base.preferences.value.googleSession)
        assertEquals(query, base.preferences.value.query)
        assertEquals(listOf("SQL"), base.preferences.value.profile.skills)
        base.release.complete(Unit); runCurrent()
        assertEquals(listOf(favorite), base.jobs.value)
        vm.search(query); runCurrent()
        assertNotNull(base.preferences.value.googleSession)
        assertEquals(query, base.preferences.value.googleSession!!.query)
    }

    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ScoutViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() = runTest(dispatcher) {
        try {
            val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        } finally { Dispatchers.resetMain() }
    }

    private class FakeRepository : ScoutRepository {
        override val jobs = MutableStateFlow<List<TrackedJob>>(emptyList())
        override val preferences = MutableStateFlow(AppPreferences())
        var failure: Exception? = null
        var searchCalls = 0
        val release = CompletableDeferred<Unit>()
        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { preferences.value = transform(preferences.value) }
        override suspend fun search(onSource: suspend (SourceResult) -> Unit): SearchSummary {
            searchCalls++
            release.await()
            failure?.let { throw it }
            onSource(SourceResult("fixture:ok"))
            onSource(SourceResult("fixture:failed", error = "Unavailable"))
            val job = Job("1", "fixture:ok", "1", "Java Developer", "Fixture", "https://example.com/jobs/1", "São Paulo, Brasil")
            jobs.value = listOf(TrackedJob(job))
            preferences.value = preferences.value.copy(lastResultIds = setOf("1"))
            return SearchSummary(1, listOf(SourceResult("fixture:ok"), SourceResult("fixture:failed", error = "Unavailable")), emptyList())
        }
        override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
        override suspend fun markNotified(ids: List<String>) = Unit
        override suspend fun clearAll() { jobs.value = emptyList(); preferences.value = AppPreferences() }
    }
    private fun vm(repo: ScoutRepository) = ScoutViewModel(repo, object : ResumeImporter {
        override suspend fun importDocument(uri: String) = ResumeDraft(CandidateProfile(skills = listOf("SQL")), listOf("Review"))
    }, object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
        object : WebJobImporter { override suspend fun importLink(url: String): com.jobscout.domain.Job = error("Not used") })
        .also { viewModels += it }

    @Test fun `captured previews from prior sessions stay in native cards without relaxing match filters`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val query = SearchQuery(titles = listOf("Java"))
        val job = Job("captured", "google:linkedin", "1", "Java", "Empresa não informada", "https://www.linkedin.com/jobs/view/123/")
        repo.jobs.value = listOf(TrackedJob(job), TrackedJob(job.copy(id = "old")), TrackedJob(job.copy(id = "dismissed"), dismissed = true))
        repo.preferences.value = AppPreferences(query = query, googleResultIds = setOf("captured", "old", "dismissed"),
            googleSession = GoogleSession("last", query, emptyList(), phase = GooglePhase.COMPLETE, foundIds = setOf("captured", "dismissed")))
        val vm = vm(repo)
        vm.state.test {
            var state = awaitItem(); while (state.loading) state = awaitItem()
            assertEquals(listOf("captured", "old"), state.googleCaptured.map { it.tracked.job.id })
            assertTrue(state.results.isEmpty()) // Unknown location stays outside the Brazil radar.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `pending human verification never blocks startup refresh of independent sources`() = runTest(dispatcher) {
        val query = SearchQuery(titles = listOf("Java"))
        val pending = GoogleSession("pending", query, GoogleDorksPlanner.plan(query, CandidateProfile()),
            phase = GooglePhase.NEEDS_USER, startedAt = System.currentTimeMillis() - 86_400_000)
        val repo = FakeRepository().apply {
            preferences.value = AppPreferences(onboardingComplete = true, query = query, googleSession = pending)
            release.complete(Unit)
        }
        val vm = vm(repo)
        vm.onAppOpened(); advanceUntilIdle()
        assertEquals(1, repo.searchCalls)
        assertEquals(pending, repo.preferences.value.googleSession)
    }

    @Test fun `new criteria replace the active scan instead of silently being ignored`() = runTest(dispatcher) {
        val base = FakeRepository()
        val started = mutableListOf<String>()
        var cancelled = false
        val releaseSecond = CompletableDeferred<Unit>()
        val repo = object : ScoutRepository by base {
            override suspend fun search(onSource: suspend (SourceResult) -> Unit): SearchSummary {
                val title = base.preferences.value.query.titles.single()
                started += title
                if (title == "Java") try { awaitCancellation() } finally { cancelled = true }
                releaseSecond.await()
                return SearchSummary(0, emptyList(), emptyList())
            }
        }
        val vm = vm(repo)
        vm.search(SearchQuery(titles = listOf("Java"))); runCurrent()
        vm.search(SearchQuery(titles = listOf("Kotlin"))); runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf("Java", "Kotlin"), started)
        assertEquals(listOf("Kotlin"), base.preferences.value.query.titles)
        releaseSecond.complete(Unit); advanceUntilIdle()
    }

    @Test fun `cooldown has a visible cancellable countdown and does not issue early requests`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { preferences.value = AppPreferences(lastAttempt = System.currentTimeMillis()) }
        val vm = vm(repo)
        vm.state.test {
            awaitItem(); vm.search(SearchQuery(titles = listOf("Java"))); runCurrent()
            var item = awaitItem()
            while (item.activity.waitingSeconds == 0) item = awaitItem()
            assertTrue(item.activity.searching)
            assertEquals(0, repo.searchCalls)
            vm.cancelSearch(); runCurrent(); advanceTimeBy(31_000); runCurrent()
            assertEquals(0, repo.searchCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `progress and editor events reuse ranked lists rather than rebuilding every result`() = runTest(dispatcher) {
        val repo = FakeRepository()
        repo.jobs.value = listOf(TrackedJob(Job("1", "lever:fixture", "1", "Java", "Fixture", "https://example.com/1", "São Paulo, Brasil")))
        val vm = vm(repo)
        vm.state.test {
            var item = awaitItem(); while (item.loading) item = awaitItem()
            val original = item.all
            vm.requestSearchEditor(); runCurrent()
            item = awaitItem(); while (item.activity.editSearchRequest == 0) item = awaitItem()
            assertSame(original, item.all)
            repo.preferences.value = repo.preferences.value.copy(reports = listOf(SourceResult("fixture", fetched = 10)))
            item = awaitItem(); while (item.preferences.reports.isEmpty()) item = awaitItem()
            assertSame(original, item.all)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `opening twice starts one scan and uses targets without changing confirmed skills`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { preferences.value = AppPreferences(onboardingComplete = true,
            profile = CandidateProfile(skills = listOf("SQL"), targets = listOf("Java"))) }
        val vm = vm(repo)
        vm.onAppOpened(); vm.onAppOpened()
        runCurrent()
        assertEquals(1, repo.searchCalls)
        assertEquals(listOf("Java"), repo.preferences.value.query.titles)
        assertEquals(listOf("SQL"), repo.preferences.value.profile.skills)
        vm.cancelSearch(); runCurrent()
    }

    @Test fun `recent attempt and disabled startup prevent automatic scans`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { preferences.value = AppPreferences(onboardingComplete = true, lastAttempt = System.currentTimeMillis()) }
        val vm = vm(repo)
        vm.onAppOpened(); runCurrent()
        assertEquals(0, repo.searchCalls)
        repo.preferences.value = repo.preferences.value.copy(lastAttempt = null, autoScanOnOpen = false)
        vm.onAppOpened(); runCurrent()
        assertEquals(0, repo.searchCalls)
    }

    @Test fun `partial source failure keeps successful results and ends loading`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = vm(repo)
        vm.state.test {
            awaitItem()
            vm.search(SearchQuery(titles = listOf("Java")))
            runCurrent()
            repo.release.complete(Unit)
            advanceUntilIdle()
            // state's scoring runs on Default; await the actual terminal emission.
            var item = awaitItem()
            while (item.activity.searching || item.results.isEmpty()) item = awaitItem()
            assertEquals(1, item.results.size)
            assertTrue(item.activity.progress.any { it.error != null })
            assertFalse(item.activity.searching)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `search failure clears spinner and presents actionable error`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { failure = IllegalArgumentException("Ative uma fonte."); release.complete(Unit) }
        val vm = vm(repo)
        vm.state.test {
            awaitItem()
            vm.search(SearchQuery())
            advanceUntilIdle()
            var item = awaitItem()
            while (item.activity.message == null) item = awaitItem()
            assertFalse(item.activity.searching)
            assertEquals("Ative uma fonte.", item.activity.message)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `equal relevance handles mixed missing and known posting dates without losing local state`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val unknown = Job("unknown", "fixture", "unknown", "Java Developer", "Fixture", "https://example.com/jobs/unknown", "São Paulo, Brasil")
        val dated = unknown.copy(id = "dated", sourceId = "dated", url = "https://example.com/jobs/dated", postedAt = 1000L)
        repo.jobs.value = listOf(TrackedJob(unknown), TrackedJob(dated))
        repo.preferences.value = AppPreferences(query = SearchQuery(titles = listOf("Java")), lastResultIds = setOf("unknown", "dated"))
        vm(repo).state.test {
            var item = awaitItem()
            while (item.loading || item.results.isEmpty() && item.readError == null) item = awaitItem()
            assertNull(item.readError, item.readError)
            assertEquals(listOf("dated", "unknown"), item.results.map { it.tracked.job.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `world toggle rechecks cached jobs without requiring another network scan`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val brazil = Job("br", "fixture", "br", "Java Developer", "Fixture", "https://example.com/br", "Campinas, SP")
        val foreign = brazil.copy(id = "us", sourceId = "us", url = "https://example.com/us", location = "New York, US")
        repo.jobs.value = listOf(TrackedJob(brazil), TrackedJob(foreign))
        repo.preferences.value = AppPreferences(query = SearchQuery(titles = listOf("Java")), lastResultIds = setOf("br", "us"))
        vm(repo).state.test {
            var item = awaitItem()
            while (item.loading || item.results.isEmpty()) item = awaitItem()
            assertEquals(listOf("br"), item.results.map { it.tracked.job.id })
            repo.preferences.value = repo.preferences.value.copy(query = repo.preferences.value.query.copy(worldwide = true))
            do { item = awaitItem() } while (item.results.size != 2)
            assertEquals(setOf("br", "us"), item.results.map { it.tracked.job.id }.toSet())
            repo.preferences.value = repo.preferences.value.copy(query = repo.preferences.value.query.copy(worldwide = false))
            do { item = awaitItem() } while (item.results.size != 1)
            assertEquals("br", item.results.single().tracked.job.id)
            assertEquals(0, repo.searchCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

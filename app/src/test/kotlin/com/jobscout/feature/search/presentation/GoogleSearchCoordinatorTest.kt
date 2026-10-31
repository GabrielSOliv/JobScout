package com.jobscout.feature.search.presentation

import com.jobscout.domain.*
import com.jobscout.domain.Job
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleSearchCoordinatorTest {
    @Test fun `skip cancels pending work and skips its later fallback without inventing counts`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val original = repo.session(); val primary = original.tasks.first(); val other = original.tasks[1]
        val fallback = original.tasks.first { it.fallbackFor == primary.id }
        val pending = GoogleLinkPreview("https://www.google.com/goto?url=CAESfixtureToken12345", "Java", "Brasil", "linkedin", primary.query, 1L)
        repo.updatePreferences { it.copy(googleResultIds = setOf("kept"), googleSession = original.copy(
            tasks = listOf(primary, other, fallback), pendingLinks = listOf(pending), foundIds = setOf("kept"))) }
        repo.waitForPage = true
        coordinator.snapshot("loading", primary.url, original.id, original.requestId, false); runCurrent()
        coordinator.skip(); runCurrent()
        val next = repo.session()
        assertEquals(1, next.taskIndex); assertEquals(0, next.pagesRead)
        assertTrue(next.pendingLinks.isEmpty()); assertEquals(setOf("kept"), next.foundIds)
        assertEquals(setOf(primary.id), next.skippedTaskIds); assertEquals(0, next.taskCounts!![primary.id])
        repo.waitForPage = false; repo.page = GooglePage(GooglePageKind.RESULTS)
        coordinator.snapshot("old", primary.url, original.id, original.requestId, true); runCurrent()
        assertEquals(next, repo.session())
        coordinator.snapshot("next", other.url, next.id, next.requestId, false); runCurrent()
        assertEquals(GooglePhase.COMPLETE, repo.session().phase)
        assertEquals(1, repo.session().pagesRead)
        assertFalse(repo.session().taskCounts!!.containsKey(fallback.id))
    }

    @Test fun `finish cancels inspection rejects late callbacks and same query starts a new plan`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
        coordinator.prepare(query); val old = repo.session()
        repo.updatePreferences { it.copy(googleResultIds = setOf("kept")) }
        repo.waitForPage = true
        coordinator.snapshot("old", old.currentUrl!!, old.id, old.requestId, false); runCurrent()
        coordinator.finish()
        assertNull(repo.preferences.value.googleSession)
        coordinator.snapshot("late", old.currentUrl!!, old.id, old.requestId, true); runCurrent()
        assertNull(repo.preferences.value.googleSession)
        coordinator.prepare(query)
        assertNotEquals(old.id, repo.session().id)
        assertEquals(old.tasks, repo.session().tasks)
        assertEquals(setOf("kept"), repo.preferences.value.googleResultIds)
    }

    @Test fun `explicit confirmation replaces an unfinished inspection instead of being dropped`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java"))); val old = repo.session()
        repo.waitForPage = true
        coordinator.snapshot("loading", old.currentUrl!!, old.id, old.requestId, false); runCurrent()
        repo.updatePreferences { it.copy(googleSession = old.copy(phase = GooglePhase.NEEDS_USER)) }
        repo.waitForPage = false; repo.page = GooglePage(GooglePageKind.RESULTS)
        coordinator.snapshot("confirmed", old.currentUrl!!, old.id, old.requestId, true); runCurrent()
        assertEquals(2, repo.inspections)
        assertEquals(1, repo.session().taskIndex)
        assertEquals(1L, repo.session().humanConfirmations)
    }

    @Test fun `restore reloads the planned dork rather than the changed Google query`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java"))); val old = repo.session()
        repo.updatePreferences { it.copy(googleSession = old.copy(phase = GooglePhase.NEEDS_USER,
            currentUrl = "https://www.google.com/search?q=changed")) }
        coordinator.resume(reload = true); runCurrent()
        assertEquals(old.tasks.first().url, repo.session().currentUrl)
        assertTrue(repo.session().requestId > old.requestId)
        val pending = GoogleLinkPreview("https://www.google.com/goto?url=CAESfixtureToken12345", "Java", "Brasil", "linkedin", old.tasks.first().query, 1L)
        repo.updatePreferences { it.copy(googleSession = it.googleSession!!.copy(phase = GooglePhase.NEEDS_USER, pendingLinks = listOf(pending))) }
        coordinator.resume(reload = true); runCurrent()
        assertEquals(pending.wrapperUrl, repo.session().currentUrl)
    }

    @Test fun `legacy unrestricted task is skipped without importing or reading another page`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java"))); val old = repo.session()
        val legacy = old.tasks.first().copy(id = "legacy", board = "careers", query = "Java", url = "https://www.google.com/search?q=Java")
        repo.updatePreferences { it.copy(googleSession = old.copy(tasks = listOf(legacy) + old.tasks, phase = GooglePhase.PAUSED)) }
        coordinator.resume(); runCurrent()
        assertEquals(old.tasks.first().url, repo.session().currentUrl)
        assertEquals(0, repo.session().pagesRead); assertEquals(0, repo.inspections)
    }

    @Test fun `switching discovery methods away from dorks pauses a previous Google queue`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val id = repo.session().id
        coordinator.prepare(SearchQuery(titles = listOf("Java"), methods = setOf(DiscoveryMethod.API)))
        assertEquals(id, repo.session().id)
        assertEquals(GooglePhase.PAUSED, repo.session().phase)
    }
    private class Repository : ScoutRepository {
        override val jobs = MutableStateFlow<List<TrackedJob>>(emptyList())
        override val preferences = MutableStateFlow(AppPreferences())
        var page = GooglePage(GooglePageKind.CAPTCHA)
        var inspections = 0
        var waitForPage = false
        var resolvedJob: Job? = null
        var inspectedTask: GoogleDorkTask? = null
        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { preferences.value = transform(preferences.value) }
        override suspend fun search(onSource: suspend (SourceResult) -> Unit) = SearchSummary(0, emptyList(), emptyList())
        override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
        override suspend fun markNotified(ids: List<String>) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun inspectGooglePage(html: String, url: String, task: GoogleDorkTask, query: SearchQuery): GooglePage {
            inspections++; inspectedTask = task; if (waitForPage) awaitCancellation(); return page
        }
        override suspend fun mergeDiscoveredJobs(jobs: List<Job>): List<String> {
            this.jobs.value = jobs.map { TrackedJob(it) }; return jobs.map { it.id }
        }
        override suspend fun resolveGoogleLink(preview: GoogleLinkPreview, url: String, query: SearchQuery) = resolvedJob
        fun session() = preferences.value.googleSession!!
    }

    @Test fun `persisted legacy tasks resume with no strategy or count metadata`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val gson = com.google.gson.Gson()
        val json = gson.toJsonTree(repo.session()).asJsonObject
        json.remove("taskAccepted"); json.remove("taskCounts"); json.remove("skippedTaskIds")
        json.getAsJsonArray("tasks").forEach { item ->
            item.asJsonObject.apply { remove("role"); remove("strategy"); remove("fallbackFor"); remove("roles") }
        }
        val legacy = gson.fromJson(json, GoogleSession::class.java)
        assertNull(legacy.taskCounts)
        assertNull(legacy.skippedTaskIds)
        assertEquals(0, legacy.taskAccepted)
        assertTrue(legacy.tasks.all { it.role == null && it.strategy == null && it.fallbackFor == null })
        assertTrue(legacy.tasks.all { it.searchRoles.isEmpty() })
        repo.updatePreferences { it.copy(googleSession = legacy) }
        repo.page = GooglePage(GooglePageKind.RESULTS)
        coordinator.snapshot("results", legacy.currentUrl!!, legacy.id, legacy.requestId, false); runCurrent()
        assertEquals(GooglePhase.RUNNING, repo.session().phase)
        assertEquals(1, repo.session().taskIndex)
        assertEquals(0, repo.session().taskCounts!![legacy.tasks.first().id])
        val restored = gson.fromJson(gson.toJson(repo.session()), GoogleSession::class.java)
        assertEquals(repo.session().taskCounts, restored.taskCounts)
    }

    @Test fun `Google empty message pauses for review and fresh results can be captured in the same task`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session()
        repo.page = GooglePage(GooglePageKind.EMPTY)
        coordinator.snapshot("empty", first.currentUrl!!, first.id, first.requestId, false); runCurrent()
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        assertTrue(repo.session().emptyPagePending)
        assertEquals(0, repo.session().taskIndex)
        repo.page = GooglePage(GooglePageKind.RESULTS, listOf(Job("late", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")))
        coordinator.snapshot("results", first.currentUrl!!, first.id, first.requestId, true); runCurrent()
        assertEquals(setOf("late"), repo.session().foundIds)
        assertFalse(repo.session().emptyPagePending)
        assertEquals(1, repo.session().taskIndex)
    }

    @Test fun `explicitly confirmed empty page advances but does not invent jobs`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session(); repo.page = GooglePage(GooglePageKind.EMPTY)
        coordinator.snapshot("empty", first.currentUrl!!, first.id, first.requestId, false); runCurrent()
        coordinator.snapshot("empty", first.currentUrl!!, first.id, first.requestId, true); runCurrent()
        assertEquals(1, repo.session().taskIndex)
        assertTrue(repo.jobs.value.isEmpty())
        assertFalse(repo.session().emptyPagePending)
    }

    @Test fun `changed browser query is inspected against original task rather than silently adopted`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session(); repo.page = GooglePage(GooglePageKind.UNKNOWN)
        coordinator.snapshot("changed", "https://www.google.com/search?q=Different", first.id, first.requestId, false); runCurrent()
        assertEquals(first.tasks.first().query, repo.inspectedTask!!.query)
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        assertEquals(0, repo.session().taskIndex)
    }

    @Test fun `expanded query is skipped when its specific query captured a link`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val initial = repo.session()
        val first = initial.tasks.first()
        val fallback = initial.tasks.first { it.fallbackFor == first.id }
        val tail = initial.tasks.first { it.board == "gupy" && it.strategy == DorkStrategy.TITLE }
        repo.updatePreferences { it.copy(googleSession = initial.copy(tasks = listOf(first, fallback, tail))) }
        repo.page = GooglePage(GooglePageKind.RESULTS, listOf(Job("1", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")))
        coordinator.snapshot("results", first.url, initial.id, initial.requestId, false); runCurrent()
        assertEquals(tail.url, repo.session().currentUrl)
        assertEquals(1, repo.session().taskCounts!![first.id])
        assertFalse(repo.session().taskCounts!!.containsKey(fallback.id))
        assertEquals(1, repo.session().pagesRead)
    }

    @Test fun `zero accepted links permits expansion but a challenge never does`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val initial = repo.session(); val first = initial.tasks.first()
        val fallback = initial.tasks.first { it.fallbackFor == first.id }
        repo.updatePreferences { it.copy(googleSession = initial.copy(tasks = listOf(first, fallback))) }
        coordinator.snapshot("captcha", first.url, initial.id, initial.requestId, false); runCurrent()
        assertEquals(0, repo.session().taskIndex)
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        repo.page = GooglePage(GooglePageKind.RESULTS) // Recognized page, but nothing relevant accepted.
        coordinator.snapshot("results", first.url, initial.id, initial.requestId, true); runCurrent()
        assertEquals(fallback.url, repo.session().currentUrl)
        assertEquals(0, repo.session().taskCounts!![first.id])
    }

    @Test fun `page one advances to next source even when Google publishes a next page`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session()
        assertNotEquals(first.tasks[0].board, first.tasks[1].board)
        repo.page = GooglePage(GooglePageKind.RESULTS, nextUrl = first.currentUrl + "&start=10")
        coordinator.snapshot("page1", first.currentUrl!!, first.id, first.requestId, false); runCurrent()
        val next = repo.session()
        assertEquals(1, next.taskIndex)
        assertEquals(first.tasks[1].url, next.currentUrl)
        assertEquals(0, next.pageInTask)
        assertEquals(1, next.pagesRead)
        assertNull(next.nextPageUrl)
    }

    @Test fun `legacy paused second page is skipped without another browser read`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session()
        repo.updatePreferences { it.copy(googleSession = first.copy(phase = GooglePhase.PAUSED, pageInTask = 1,
            currentUrl = first.currentUrl + "&start=10", pagesRead = 1)) }
        coordinator.resume(); runCurrent()
        assertEquals(1, repo.session().taskIndex)
        assertEquals(first.tasks[1].url, repo.session().currentUrl)
        assertEquals(1, repo.session().pagesRead)
        assertEquals(0, repo.inspections)
    }

    @Test fun `second page reached manually is never imported as first page`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val first = repo.session()
        repo.page = GooglePage(GooglePageKind.RESULTS, jobs = listOf(Job("wrong-page", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")))
        coordinator.snapshot("page2", first.currentUrl + "&start=10", first.id, first.requestId, false); runCurrent()
        assertTrue(repo.jobs.value.isEmpty())
        assertTrue(repo.session().foundIds.isEmpty())
        assertEquals(first.tasks[1].url, repo.session().currentUrl)
    }

    @Test fun `opaque redirect keeps checkpoint and accepts only current browser callback`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        repo.updatePreferences { prefs -> val session = prefs.googleSession!!; prefs.copy(googleSession = session.copy(tasks = session.tasks.take(1))) }
        val page = repo.session()
        val wrapper = "https://www.google.com/goto?url=CAESfixtureToken12345"
        repo.page = GooglePage(GooglePageKind.RESULTS, pendingLinks = listOf(GoogleLinkPreview(wrapper, "Java", "Brasil", "linkedin", page.tasks.single().query, 123L)))
        coordinator.snapshot("DOM", page.currentUrl!!, page.id, page.requestId, false); runCurrent()
        val redirect = repo.session()
        assertEquals(wrapper, redirect.currentUrl)
        assertTrue(repo.jobs.value.isEmpty())
        assertEquals(0, redirect.pagesRead)
        repo.resolvedJob = Job("1", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")
        coordinator.resolvedTarget(repo.resolvedJob!!.url, page.id, page.requestId, false); runCurrent()
        assertTrue(repo.jobs.value.isEmpty())
        coordinator.resolvedTarget(repo.resolvedJob!!.url, redirect.id, redirect.requestId, false); runCurrent()
        assertEquals(GooglePhase.COMPLETE, repo.session().phase)
        assertEquals(1, repo.session().pagesRead)
        assertEquals(setOf("1"), repo.preferences.value.googleResultIds)
        assertTrue(repo.session().pendingLinks.isEmpty())
    }

    @Test fun `confirming a challenged search resumes opaque links without another confirmation`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        repo.updatePreferences { prefs -> val session = prefs.googleSession!!; prefs.copy(googleSession = session.copy(tasks = session.tasks.take(1))) }
        val initial = repo.session()
        coordinator.snapshot("captcha", initial.currentUrl!!, initial.id, initial.requestId, false); runCurrent()
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        val wrapper = "https://www.google.com/goto?url=CAESfixtureToken12345"
        repo.page = GooglePage(GooglePageKind.RESULTS, pendingLinks = listOf(
            GoogleLinkPreview(wrapper, "Java Developer", "Brasil", "linkedin", initial.tasks.single().query, 123L)))
        coordinator.snapshot("results", initial.currentUrl!!, initial.id, initial.requestId, true); runCurrent()
        val resumed = repo.session()
        assertEquals(GooglePhase.RUNNING, resumed.phase)
        assertEquals(wrapper, resumed.currentUrl)
        assertEquals(1L, resumed.humanConfirmations)
        assertEquals(1, resumed.pendingLinks.size)
        assertTrue(resumed.requestId > initial.requestId)
        repo.resolvedJob = Job("1", "google:linkedin", "1", "Java Developer", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")
        coordinator.resolvedTarget(repo.resolvedJob!!.url, resumed.id, resumed.requestId, false); runCurrent()
        assertEquals(GooglePhase.COMPLETE, repo.session().phase)
        assertEquals(1L, repo.session().humanConfirmations)
        assertEquals(setOf("1"), repo.preferences.value.googleResultIds)
    }

    @Test fun `challenge waits for explicit confirmation and confirmation must actually find results`() = runTest {
        val repo = Repository()
        val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        repo.updatePreferences { prefs -> val session = prefs.googleSession!!; prefs.copy(googleSession = session.copy(tasks = session.tasks.take(1))) }
        val current = repo.session()
        fun submit(confirmed: Boolean) = coordinator.snapshot("DOM", current.currentUrl!!, current.id, current.requestId, confirmed)
        submit(false); runCurrent()
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        submit(false); runCurrent(); assertEquals(1, repo.inspections)
        submit(true); runCurrent(); assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        assertEquals(2, repo.inspections)
        val job = Job("1", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")
        repo.page = GooglePage(GooglePageKind.RESULTS, listOf(job))
        submit(false); runCurrent(); assertTrue(repo.jobs.value.isEmpty())
        submit(true); runCurrent()
        assertEquals(GooglePhase.COMPLETE, repo.session().phase)
        assertEquals(setOf("1"), repo.preferences.value.googleResultIds)
        assertEquals(setOf("1"), repo.session().foundIds)
    }

    @Test fun `old browser callback cannot alter a replacement search`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val old = repo.session()
        coordinator.prepare(SearchQuery(titles = listOf("Kotlin")), force = true)
        coordinator.snapshot("old", old.currentUrl!!, old.id, old.requestId, true); runCurrent()
        assertEquals(0, repo.inspections)
        assertEquals(listOf("Kotlin"), repo.session().query.titles)
        assertEquals(GooglePhase.RUNNING, repo.session().phase)
    }

    @Test fun `a help or invalid target cannot confirm a challenge or skip its pending link`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        repo.updatePreferences { prefs -> val session = prefs.googleSession!!; prefs.copy(googleSession = session.copy(tasks = session.tasks.take(1))) }
        val first = repo.session()
        repo.page = GooglePage(GooglePageKind.RESULTS, pendingLinks = listOf(GoogleLinkPreview("https://www.google.com/goto?url=CAESfixtureToken12345", "Java", "Brasil", "linkedin", first.tasks.single().query, 123L)))
        coordinator.snapshot("results", first.currentUrl!!, first.id, first.requestId, false); runCurrent()
        val redirect = repo.session()
        repo.page = GooglePage(GooglePageKind.CAPTCHA)
        coordinator.snapshot("captcha", "https://www.google.com/sorry/", redirect.id, redirect.requestId, false); runCurrent()
        coordinator.resolvedTarget("https://support.google.com/", redirect.id, redirect.requestId, true); runCurrent()
        assertEquals(GooglePhase.NEEDS_USER, repo.session().phase)
        assertEquals(1, repo.session().pendingLinks.size)
        assertEquals(0L, repo.session().humanConfirmations)
        repo.resolvedJob = Job("1", "google:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/", "Brasil")
        coordinator.resolvedTarget(repo.resolvedJob!!.url, redirect.id, redirect.requestId, true); runCurrent()
        assertEquals(GooglePhase.COMPLETE, repo.session().phase)
        assertEquals(1L, repo.session().humanConfirmations)
    }

    @Test fun `background pause cancels ongoing page processing and keeps checkpoint resumable`() = runTest {
        val repo = Repository(); val coordinator = GoogleSearchCoordinator(repo, backgroundScope)
        coordinator.prepare(SearchQuery(titles = listOf("Java")))
        val old = repo.session(); repo.waitForPage = true
        coordinator.snapshot("DOM", old.currentUrl!!, old.id, old.requestId, false); runCurrent()
        coordinator.pause(background = true); runCurrent()
        assertEquals(GooglePhase.PAUSED, repo.session().phase)
        assertEquals(old.currentUrl, repo.session().currentUrl)
        coordinator.resume(); runCurrent()
        assertEquals(GooglePhase.RUNNING, repo.session().phase)
        assertTrue(repo.session().requestId > old.requestId)
    }
}

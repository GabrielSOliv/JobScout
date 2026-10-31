package com.jobscout

import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import com.jobscout.app.navigation.ScoutApp
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.data.GoogleResultsParser
import com.jobscout.feature.search.presentation.ScoutViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local fixture only: does not solve, click or emulate a real provider's CAPTCHA. */
class GoogleBrowserTest {
    @Test fun foreignOrganicResultsRequireReviewAndTheLoadedURLHasAnIndependentDomainFilter() {
        val repo = Repository()
        var browser: WebView? = null
        val loaded = mutableListOf<String>()
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view; loaded += url
            view.loadDataWithBaseURL(url, """<html><body><div id='search'><div class='g'><a href='https://www.instagram.com/junior_oficial/'><h3>JUNIOR Instagram</h3></a></div><div class='g'><a href='https://www.facebook.com/JuniorLima/'><h3>Junior Lima</h3></a></div></div></body></html>""", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.NEEDS_USER }
        val session = repo.preferences.value.googleSession!!
        assertEquals(0, session.pagesRead); assertEquals(0, session.taskIndex)
        assertTrue(session.message.orEmpty().contains("fora de linkedin.com"))
        assertTrue(loaded.single().contains("as_sitesearch=linkedin.com"))
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.onNodeWithTag("google-displayed-query").assertTextContains("site:linkedin.com", substring = true)
        compose.onNodeWithText("Restaurar consulta").performClick()
        compose.waitUntil(10_000) { loaded.size == 2 }
        assertEquals(loaded.first(), loaded.last()); assertNotNull(browser)
    }
    @get:Rule val compose = createComposeRule()
    private class Repository(private val taskLimit: Int = 1) : ScoutRepository {
        override val jobs = MutableStateFlow<List<TrackedJob>>(emptyList())
        override val preferences = MutableStateFlow(AppPreferences(onboardingComplete = true, autoScanOnOpen = false,
            sources = emptyList(), query = SearchQuery(titles = listOf("Java"))))
        @Volatile var inspections = 0
        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
            val next = transform(preferences.value)
            preferences.value = next.copy(googleSession = next.googleSession?.let { it.copy(tasks = it.tasks.take(taskLimit)) })
        }
        override suspend fun search(onSource: suspend (SourceResult) -> Unit) = SearchSummary(0, emptyList(), emptyList())
        override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
        override suspend fun markNotified(ids: List<String>) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun clearUnsavedJobs(): Int {
            val old = jobs.value
            jobs.value = old.filter { it.saved }
            val ids = jobs.value.map { it.job.id }.toSet()
            updatePreferences { it.copy(lastResultIds = it.lastResultIds.intersect(ids), googleResultIds = it.googleResultIds.orEmpty().intersect(ids)) }
            return old.size - jobs.value.size
        }
        override suspend fun mergeDiscoveredJobs(jobs: List<Job>): List<String> { this.jobs.value = jobs.map { TrackedJob(it) }; return jobs.map { it.id } }
        override suspend fun resolveGoogleLink(preview: GoogleLinkPreview, url: String, query: SearchQuery) = GoogleResultsParser.resolve(preview, url, query)
        override suspend fun inspectGooglePage(html: String, url: String, task: GoogleDorkTask, query: SearchQuery): GooglePage {
            inspections++; return GoogleResultsParser.parse(html, url, task, query)
        }
    }
    @Test fun confirmationSurvivesNavigationToTheSolvedPageInTheSameBrowser() {
        val repo = Repository()
        var browser: WebView? = null
        var queryUrl = ""
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view; queryUrl = url
            view.loadDataWithBaseURL(url, "<html><body><form id='captcha-form'>Local fixture</form></body></html>", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.NEEDS_USER }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        val sameBrowser = browser
        compose.runOnUiThread {
            browser!!.loadDataWithBaseURL(queryUrl, """<html><body><div id='search'><div class='g'><a href='https://www.linkedin.com/jobs/view/777448/'><h3>Java Developer</h3></a><div class='VwiC3b'>Brasil</div></div></div></body></html>""", "text/html", "UTF-8", queryUrl)
            // Deliberate confirmation racing WebView's started/finished callbacks.
            vm.confirmGoogle()
        }
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.COMPLETE }
        assertSame(sameBrowser, browser)
        assertEquals(1, repo.jobs.value.size)
        assertEquals(1L, repo.preferences.value.googleSession!!.humanConfirmations)
    }

    @Test fun skipAndFinishReleaseTheBrowserAndCleanupPreservesFavoritesBeforeNewSearch() {
        val repo = Repository(taskLimit = 2)
        val favorite = TrackedJob(Job("saved", "fixture", "1", "Java", "Fixture", "https://example.com/jobs/1", "Brasil"), saved = true, notes = "Manter")
        repo.jobs.value = listOf(favorite, favorite.copy(job = favorite.job.copy(id = "unsaved", url = "https://example.com/jobs/2"), saved = false))
        repo.preferences.value = repo.preferences.value.copy(googleResultIds = setOf("saved", "unsaved"))
        val loaded = mutableListOf<String>()
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            loaded += url
            view.loadDataWithBaseURL(url, "<html><body><form id='captcha-form'>Local fixture</form></body></html>", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.NEEDS_USER }
        val oldId = repo.preferences.value.googleSession!!.id
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.onNodeWithTag("google-skip").performClick()
        compose.waitUntil(10_000) { repo.preferences.value.googleSession?.taskIndex == 1 }
        assertEquals(0, repo.preferences.value.googleSession!!.pagesRead)
        compose.onNodeWithTag("google-displayed-query").assertTextContains("site:linkedin.com", substring = true)
        compose.onNodeWithTag("google-next-query").assertTextContains("site:gupy.io", substring = true)
        compose.onNodeWithTag("google-finish").performClick()
        compose.waitUntil(10_000) { repo.preferences.value.googleSession == null }
        compose.onNodeWithTag("screen-explore").assertIsDisplayed()
        assertEquals(2, repo.jobs.value.size)
        compose.onNodeWithTag("search-clear-unsaved").performScrollTo().performClick()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(2, repo.jobs.value.size)
        compose.onNodeWithTag("search-clear-unsaved").performClick()
        compose.onNodeWithTag("confirm-clear-unsaved").performClick()
        compose.waitUntil(10_000) { repo.jobs.value.size == 1 }
        assertEquals(favorite, repo.jobs.value.single())
        compose.onNodeWithTag("search-clear-unsaved").assertIsNotEnabled()
        // Wait for the real snackbar to leave before scrolling a button behind its overlay.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 vagas não salvas removidas. Favoritos preservados.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("results-matches").performScrollTo().performClick()
        compose.onNodeWithText("Encontrar vagas").performScrollTo().performClick()
        try { compose.waitUntil(10_000) { repo.preferences.value.googleSession != null } }
        catch (failure: Throwable) { throw AssertionError("Manual search did not start: query=${repo.preferences.value.query}, activity=${vm.state.value.activity}, loaded=$loaded", failure) }
        assertNotEquals(oldId, repo.preferences.value.googleSession!!.id)
        assertEquals(0, repo.preferences.value.googleSession!!.taskIndex)
        compose.waitUntil(10_000) { loaded.size == 2 }
        assertEquals(loaded.first(), loaded.last())
    }

    @Test fun lateResultsReplaceInitialGoogleEmptyMessageBeforeTheTaskAdvances() {
        val repo = Repository()
        repo.preferences.value = repo.preferences.value.copy(query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês")))
        var browser: WebView? = null
        val loaded = mutableListOf<String>()
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view; loaded += url
            val html = """<html><body><div id='search'><div id='topstuff'>Sua pesquisa não encontrou nenhum documento.</div></div>
                <script>setTimeout(function(){document.getElementById('search').innerHTML='<div class="g"><a href="https://www.linkedin.com/jobs/view/777446/"><h3>Java Developer</h3></a><div class="VwiC3b">São Paulo, Brasil. English required.</div></div>';},4500);</script>
                </body></html>"""
            view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        // Rendering stays active behind the native UI; this is a local delayed-DOM fixture.
        compose.onNodeWithTag("nav-Currículo").performClick()
        compose.waitUntil(20_000) { repo.preferences.value.googleSession?.phase == GooglePhase.COMPLETE }
        assertEquals(1, loaded.size)
        assertEquals(1, repo.jobs.value.size)
        assertEquals(0L, repo.preferences.value.googleSession!!.humanConfirmations)
        assertFalse(repo.preferences.value.googleSession!!.emptyPagePending)
        compose.waitUntil(10_000) { vm.state.value.results.size == 1 }
        assertTrue(loaded.single().contains("English"))
        compose.runOnUiThread { assertEquals(android.view.View.VISIBLE, browser!!.visibility) }
        compose.onNodeWithTag("screen-profile").assertIsDisplayed()
    }

    @Test fun actualGoogleEmptyPageCanBeReviewedAndLaterResultsCapturedInTheSameBrowser() {
        val repo = Repository()
        var browser: WebView? = null
        var queryUrl = ""
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view; queryUrl = url
            view.loadDataWithBaseURL(url, "<html><body><div id='topstuff'>Sua pesquisa não encontrou nenhum documento.</div></body></html>", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(25_000) { repo.preferences.value.googleSession?.emptyPagePending == true }
        assertEquals(0, repo.preferences.value.googleSession!!.taskIndex)
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.onNodeWithTag("google-confirm").assertTextEquals("Conferir página e continuar")
        val sameBrowser = browser
        compose.runOnUiThread {
            // Represents results reached after the user's navigation, without visiting AI or Google.
            browser!!.loadDataWithBaseURL(queryUrl, """<html><body><div id='search'><div class='g'><a href='https://www.linkedin.com/jobs/view/777447/'><h3>Java Developer</h3></a><div class='VwiC3b'>Brasil</div></div></div></body></html>""", "text/html", "UTF-8", queryUrl)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-confirm").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.onNodeWithTag("google-confirm").performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.COMPLETE }
        assertSame(sameBrowser, browser)
        assertEquals(1, repo.jobs.value.size)
    }
    @Test fun humanConfirmationUsesSameBrowserAndNeverOpensAutomatically() {
        val repo = Repository()
        var browser: WebView? = null
        var googleUrl = ""
        val loadedUrls = mutableListOf<String>()
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view; googleUrl = url; loadedUrls += url
            view.loadDataWithBaseURL(url, "<html><body><form id='captcha-form'>Fixture: human verification required</form></body></html>", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.NEEDS_USER }
        compose.onNodeWithTag("home-list").assertIsDisplayed()
        compose.onNodeWithTag("screen-google").assertDoesNotExist()
        val sameBrowser = browser
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.onNodeWithTag("screen-google").assertIsDisplayed()
        compose.onNodeWithTag("google-confirm").performClick()
        compose.waitUntil(10_000) { repo.inspections >= 2 }
        compose.waitForIdle()
        assertEquals(GooglePhase.NEEDS_USER, repo.preferences.value.googleSession?.phase)
        assertTrue(repo.jobs.value.isEmpty())
        compose.runOnUiThread {
            // Simulates the DOM the user reaches after resolving. No challenge interaction occurs.
            browser!!.loadDataWithBaseURL(googleUrl, """<html><body><div id="search"><div class="g"><a href="https://www.linkedin.com/jobs/view/123456/"><h3>Java Developer | LinkedIn</h3></a><div class="VwiC3b">São Paulo, Brasil. Desenvolvimento Java.</div></div><a id="pnnext" href="${googleUrl.replace("&", "&amp;")}&amp;start=10">Próxima</a></div></body></html>""", "text/html", "UTF-8", googleUrl)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-confirm").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.waitForIdle()
        assertTrue(repo.jobs.value.isEmpty())
        compose.onNodeWithTag("google-confirm").performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.COMPLETE }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-google").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("home-list").assertIsDisplayed()
        assertSame(sameBrowser, browser)
        val job = repo.jobs.value.single().job
        assertEquals("https://www.linkedin.com/jobs/view/123456", job.url.trimEnd('/'))
        assertEquals(VerificationState.UNVERIFIED, job.verification.state)
        assertTrue(job.discoveryOrigins.single().query!!.contains("site:linkedin.com"))
        compose.waitUntil(10_000) { vm.state.value.googleCaptured.isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("captured-${job.id}"))
        compose.onNodeWithTag("captured-${job.id}").assertIsDisplayed()
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("captured-heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-google").assertDoesNotExist()
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("captured-${job.id}"))
        compose.onNodeWithTag("captured-${job.id}").assertIsDisplayed()
        compose.onNodeWithTag("google-browser").performScrollTo().performClick()
        compose.onNodeWithTag("screen-google").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val externalFilter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        }
        val monitor = instrumentation.addMonitor(externalFilter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            compose.runOnUiThread {
                val request = object : android.webkit.WebResourceRequest {
                    override fun getUrl() = android.net.Uri.parse(job.url)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = true
                    override fun hasGesture() = true
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = mutableMapOf<String, String>()
                }
                assertTrue(browser!!.webViewClient.shouldOverrideUrlLoading(browser!!, request))
            }
            compose.waitForIdle()
            assertEquals(0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
        compose.onNodeWithTag("google-links").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("captured-heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-google").assertDoesNotExist()
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("job-${job.id}"))
        compose.onNodeWithTag("job-${job.id}").assertIsDisplayed()
        assertTrue(loadedUrls.all(GoogleDorksPlanner::allowedNavigation))
        assertTrue(loadedUrls.none { it.contains("start=10") })
        Espresso.pressBack()
        compose.onNodeWithTag("home-list").assertIsDisplayed()
    }

    @Test fun opaqueOrganicRedirectPausesForHumanAndCollectsHiddenAfterConfirmation() {
        val repo = Repository()
        var browser: WebView? = null
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            browser = view
            val html = if (url.contains("/goto?")) "<html><body><form id='captcha-form'>Fixture verification</form></body></html>"
                else """<html><body><div id="search"><div class="g"><a href="https://www.google.com/goto?url=CAESfixtureToken12345"><span>https://br.linkedin.com/jobs/view</span><h3>Java Developer | LinkedIn</h3></a><div class="VwiC3b">São Paulo, Brasil. Java.</div></div></div></body></html>"""
            view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(30_000) { repo.preferences.value.googleSession?.phase == GooglePhase.NEEDS_USER }
        compose.onNodeWithTag("home-list").assertIsDisplayed()
        assertTrue(repo.jobs.value.isEmpty())
        assertEquals(1, repo.preferences.value.googleSession!!.pendingLinks.size)
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.runOnUiThread {
            // A normal browser redirect observed after a hypothetical manual resolution.
            // This invokes our callback with a fixture, without navigating or solving a challenge.
            val request = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse("https://www.linkedin.com/jobs/view/999888/")
                override fun isForMainFrame() = true
                override fun isRedirect() = true
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = mutableMapOf<String, String>()
            }
            assertTrue(browser!!.webViewClient.shouldOverrideUrlLoading(browser!!, request))
        }
        assertTrue(repo.jobs.value.isEmpty())
        assertEquals(GooglePhase.NEEDS_USER, repo.preferences.value.googleSession!!.phase)
        compose.onNodeWithTag("google-confirm").performClick()
        compose.waitUntil(15_000) { repo.preferences.value.googleSession?.phase == GooglePhase.COMPLETE }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-google").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("home-list").assertIsDisplayed()
        assertEquals("https://www.linkedin.com/jobs/view/999888", repo.jobs.value.single().job.url.trimEnd('/'))
        assertEquals(1L, repo.preferences.value.googleSession!!.humanConfirmations)
        compose.waitUntil(10_000) { vm.state.value.googleCaptured.isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("captured-${repo.jobs.value.single().job.id}"))
        compose.onNodeWithTag("captured-${repo.jobs.value.single().job.id}").assertIsDisplayed()
    }

    @Test fun onlyRelevantPreviewsArriveInNativeCardsWhileUserIsOnProfile() {
        val repo = Repository()
        val loaded = mutableListOf<String>()
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, googlePageLoader = { view, url ->
            loaded += url
            view.loadDataWithBaseURL(url, """<html><body><div id="search">
                <div class="g"><a href="https://www.google.com/goto?url=CAESunrelatedToken12345"><span>https://br.linkedin.com/jobs/view</span><h3>Assistente administrativo | LinkedIn</h3></a><div class="VwiC3b">Empresa de desenvolvimento Java, Brasil.</div></div>
                <div class="g"><a href="https://www.linkedin.com/jobs/view/777445/"><h3>JavaScript Developer | LinkedIn</h3></a><div class="VwiC3b">São Paulo, Brasil. Java citado no texto.</div></div>
                <div class="g"><a href="https://www.linkedin.com/jobs/view/777444/"><h3>Java Developer | LinkedIn</h3></a><div class="VwiC3b">Experiência com Java e SQL.</div></div>
                </div></body></html>""", "text/html", "UTF-8", url)
        }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("google-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.onNodeWithTag("nav-Currículo").performClick()
        compose.waitUntil(10_000) { vm.state.value.googleCaptured.size == 1 }
        compose.onNodeWithTag("screen-profile").assertIsDisplayed()
        compose.onNodeWithTag("screen-google").assertDoesNotExist()
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals("https://www.linkedin.com/jobs/view/777444", repo.jobs.value.single().job.url.trimEnd('/'))
        assertTrue(repo.preferences.value.googleSession!!.pendingLinks.isEmpty())
        assertEquals(1, loaded.size)
        assertTrue(loaded.all(GoogleDorksPlanner::allowedNavigation))
        compose.onNodeWithTag("nav-Início").performClick()
        val id = repo.jobs.value.single().job.id
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("captured-$id"))
        compose.onNodeWithTag("captured-$id").assertIsDisplayed()
        compose.onNodeWithTag("google-open").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("captured-heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("captured-$id"))
        compose.onNodeWithTag("captured-$id").assertIsDisplayed()
        compose.onNodeWithTag("screen-google").assertDoesNotExist()
    }
}

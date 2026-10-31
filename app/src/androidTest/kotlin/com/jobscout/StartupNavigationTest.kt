package com.jobscout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jobscout.app.navigation.ScoutApp
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutViewModel
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationIsUnavailableUntilLocalDataLoadsThenBecomesUsable() {
        val pendingJobs = MutableStateFlow<List<TrackedJob>?>(null)
        val prefs = MutableStateFlow(AppPreferences(googleEnabled = false, onboardingComplete = true, autoScanOnOpen = false))
        val repo = object : ScoutRepository {
            override val jobs: Flow<List<TrackedJob>> = pendingJobs.filterNotNull()
            override val preferences: Flow<AppPreferences> = prefs
            override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { prefs.value = transform(prefs.value) }
            override suspend fun search(onSource: suspend (SourceResult) -> Unit): SearchSummary = error("Not used by startup test")
            override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
            override suspend fun markNotified(ids: List<String>) = Unit
            override suspend fun clearAll() = Unit
        }
        val vm = ScoutViewModel(repo, object : ResumeImporter {
            override suspend fun importDocument(uri: String): ResumeDraft = error("Not used by startup test")
        }, object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String): Job = error("Not used") })
        compose.setContent { JobScoutTheme { ScoutApp(vm) } }
        compose.onNodeWithTag("nav-Salvas").assertDoesNotExist()
        compose.runOnIdle { pendingJobs.value = emptyList() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-Salvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-Salvas").performClick()
        compose.onNodeWithText("Vagas que importam.").assertIsDisplayed()
    }
}

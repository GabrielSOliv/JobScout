package com.jobscout

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.jobscout.app.navigation.ScoutApp
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** Offline preview of the actual app, with fictional jobs and no logos or personal data. */
class PublicHomePreviewTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : ScoutRepository {
        val job = Job("preview", "rss:python", "preview", "Desenvolvedor Kotlin", "Empresa exemplo",
            "https://example.com/jobs/preview", "São Paulo / SP, Brasil", Workplace.REMOTE,
            description = "Vaga fictícia usada somente na prévia visual do aplicativo.")
        override val jobs = MutableStateFlow(listOf(TrackedJob(job)))
        override val preferences = MutableStateFlow(AppPreferences(onboardingComplete = true,
            googleEnabled = true, autoScanOnOpen = false, sources = emptyList(),
            profile = CandidateProfile(skills = listOf("Kotlin")),
            query = SearchQuery(titles = listOf("Kotlin")), lastResultIds = setOf("preview")))
        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { preferences.value = transform(preferences.value) }
        override suspend fun search(onSource: suspend (SourceResult) -> Unit) = SearchSummary(0, emptyList(), emptyList())
        override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
        override suspend fun markNotified(ids: List<String>) = Unit
        override suspend fun clearAll() = Unit
    }
    @Test fun publicHomeRendersWithOriginalVectorArtwork() {
        val vm = ScoutViewModel(Repository(), object : ResumeImporter {
            override suspend fun importDocument(uri: String): ResumeDraft = error("Not used")
        }, object : BackgroundScheduler {
            override fun configure(enabled: Boolean, intervalHours: Long) = Unit
        }, object : WebJobImporter {
            override suspend fun importLink(url: String): Job = error("Not used")
        })
        compose.setContent { JobScoutTheme { ScoutApp(vm) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Seu próximo passo começa aqui.").assertIsDisplayed()
        compose.onNodeWithTag("nav-Currículo").assertIsDisplayed()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "public-home.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("nav-Vagas").performClick()
        compose.onNodeWithTag("google-status").assertIsDisplayed()
        compose.onNodeWithTag("search-settings").assertIsDisplayed()
        File(directory, "public-vagas.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("search-settings").performClick()
        compose.onNodeWithText("Fontes de oportunidades").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-Vagas").assertTextContains("Vagas")
        File(directory, "public-settings.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithTag("screen-explore").assertIsDisplayed()
        compose.onNodeWithTag("nav-Currículo").performClick()
        compose.onNodeWithTag("resume-export").assertIsDisplayed()
        compose.onNodeWithContentDescription("Configurações de vagas").assertDoesNotExist()
        File(directory, "public-resume.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

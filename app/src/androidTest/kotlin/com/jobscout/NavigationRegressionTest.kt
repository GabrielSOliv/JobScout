package com.jobscout

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import com.jobscout.app.navigation.ScoutApp
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutViewModel
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NavigationRegressionTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : ScoutRepository {
        private val html = Job("one", "web:empregos", "1", "Java Developer", "Fixture", "https://example.com/jobs/1", "São Paulo, Brasil",
            discoveryMethod = "Coleta HTML · Empregos", discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.HTML, "web:empregos", "Empregos.com.br", 1, 2),
                DiscoveryOrigin(DiscoveryMethod.API, "freehire:global", "Freehire", 2, 3)))
        override val jobs = MutableStateFlow(listOf(TrackedJob(html, saved = true)))
        override val preferences = MutableStateFlow(AppPreferences(googleEnabled = false, onboardingComplete = true, autoScanOnOpen = false,
            query = SearchQuery(titles = listOf("Java")), lastResultIds = setOf("one"), lastRun = 1L))
        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { preferences.value = transform(preferences.value) }
        override suspend fun search(onSource: suspend (SourceResult) -> Unit) = SearchSummary(1, emptyList(), emptyList())
        override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) = Unit
        override suspend fun markNotified(ids: List<String>) = Unit
        override suspend fun clearAll() { jobs.value = emptyList(); preferences.value = AppPreferences(googleEnabled = false, autoScanOnOpen = false) }
        override suspend fun importJob(job: Job) { jobs.value = jobs.value + TrackedJob(job, saved = true) }
    }
    private fun setUp(repo: Repository = Repository(), link: MutableState<String?> = mutableStateOf(null)): Repository {
        val vm = ScoutViewModel(repo, object : ResumeImporter { override suspend fun importDocument(uri: String): ResumeDraft = error("Not used") },
            object : BackgroundScheduler { override fun configure(enabled: Boolean, intervalHours: Long) = Unit },
            object : WebJobImporter { override suspend fun importLink(url: String) = Job("imported", "web:fixture.com", "i", "Imported Kotlin role", "Fixture", "https://fixture.com/jobs/i", discoveryMethod = "Página web · JobPosting") })
        compose.setContent { JobScoutTheme { ScoutApp(vm, link.value) { link.value = null } } }
        await("home-list")
        return repo
    }
    private fun await(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun tab(name: String, screen: String) { compose.onNodeWithTag("nav-$name").performClick(); await(screen) }

    private fun profileSkills(): SemanticsNodeInteraction {
        compose.onNodeWithTag("screen-profile").performScrollToNode(hasTestTag("profile-skills"))
        return compose.onNodeWithTag("profile-skills")
    }
    private fun settingsButton(): SemanticsNodeInteraction {
        tab("Vagas", "screen-explore")
        compose.onNodeWithTag("screen-explore").performScrollToIndex(0)
        return compose.onNodeWithTag("search-settings")
    }

    @Test fun vacanciesAlwaysReturnsHomeIncludingKeyboardAndRepeatedSwitches() {
        setUp()
        repeat(3) {
            tab("Vagas", "screen-explore")
            tab("Início", "home-list")
        }
        tab("Vagas", "screen-explore")
        Espresso.pressBack(); await("home-list")
        compose.onNodeWithContentDescription("Ajustar busca").performClick()
        await("search-titles")
        compose.onNodeWithTag("search-titles").performClick().performTextReplacement("Java, SQL")
        compose.onNodeWithTag("nav-Início").assertIsDisplayed().performClick()
        await("home-list")
        tab("Vagas", "screen-explore")
        compose.onNodeWithTag("search-titles").assertTextContains("Java, SQL")
        tab("Início", "home-list")
    }

    @Test fun settingsBelongsToVacanciesAndUnsavedResumeSurvivesTabChanges() {
        val repo = setUp()
        tab("Currículo", "screen-profile")
        profileSkills().performTextReplacement("SQL, VBA")
        Espresso.closeSoftKeyboard()
        tab("Início", "home-list")
        tab("Currículo", "screen-profile")
        profileSkills().assertTextContains("SQL, VBA")
        settingsButton().performClick(); await("screen-settings")
        tab("Início", "home-list")
        tab("Currículo", "screen-profile")
        compose.onNodeWithTag("screen-settings").assertDoesNotExist()
        profileSkills().assertTextContains("SQL, VBA")
        compose.runOnIdle { repo.preferences.value = repo.preferences.value.copy(reports = listOf(SourceResult("web:empregos", fetched = 9))) }
        compose.onNodeWithTag("profile-skills").assertTextContains("SQL, VBA")
    }

    @Test fun detailsReturnsToItsOriginAndShowsBothDiscoveryMethods() {
        setUp()
        tab("Salvas", "screen-saved")
        compose.onNodeWithTag("details-one").performScrollTo().performClick(); await("screen-details-one")
        compose.onNodeWithText("Como encontramos esta vaga").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("method-html").assertExists()
        compose.onNodeWithTag("method-api").assertExists()
        Espresso.pressBack(); await("screen-saved")
        tab("Início", "home-list")
        compose.onNodeWithTag("details-one").performScrollTo().performClick(); await("screen-details-one")
        Espresso.pressBack(); await("home-list")
    }

    @Test fun collectorFiltersOpenTheEditorAndSharedLinksOpenTheirOwnReview() {
        val link = mutableStateOf<String?>(null)
        setUp(link = link)
        compose.onNodeWithContentDescription("Atividade da varredura").performClick()
        compose.onNodeWithText("Ver coleta web").performClick(); await("screen-web")
        compose.onNodeWithTag("dorks-status").assertIsDisplayed().assertTextContains("LinkedIn e Gupy", substring = true)
        compose.onNodeWithText("Ajustar critérios").performScrollTo().performClick(); await("search-titles")
        tab("Início", "home-list")
        compose.runOnIdle { link.value = "https://fixture.com/jobs/i" }
        await("screen-import")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Salvar e ver salvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Salvar e ver salvas").performScrollTo().performClick(); await("screen-saved")
        compose.onNodeWithTag("screen-saved").performScrollToNode(hasText("Imported Kotlin role"))
        compose.onNodeWithText("Imported Kotlin role").assertIsDisplayed()
    }

    @Test fun clearingFromSettingsThenFinishingSetupStartsAtHomeInsteadOfOldSettings() {
        setUp()
        settingsButton().performClick(); await("screen-settings")
        compose.onNodeWithTag("screen-settings").performScrollToNode(hasText("Apagar todos os dados locais"))
        compose.onNodeWithText("Apagar todos os dados locais").performClick()
        compose.onNodeWithText("Apagar tudo").performClick(); await("screen-onboarding")
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Desenvolvimento").performClick()
        compose.onNodeWithText("Continuar").performClick()
        Espresso.pressBack()
        compose.onNodeWithText("Desenvolvimento").assertIsDisplayed()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Começar varredura").performClick(); await("home-list")
        compose.onNodeWithTag("screen-settings").assertDoesNotExist()
    }

    @Test fun dorksFilterAndDetailsShowTheRecordedQueryAndReturnHome() {
        val repo = setUp()
        val dork = "site:gupy.io inurl:jobs/ Java"
        compose.runOnIdle {
            val job = Job("dork", "dorks:gupy", "123", "Java Gupy role", "Fixture", "https://fixture.gupy.io/jobs/123",
                location = "São Paulo, Brasil", description = "Indexed summary", descriptionComplete = false,
                discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, "dorks:gupy", "Gupy", 1, 2, dork)))
            repo.jobs.value = repo.jobs.value + TrackedJob(job)
            repo.preferences.value = repo.preferences.value.copy(lastResultIds = setOf("one", "dork"))
        }
        tab("Vagas", "screen-explore")
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("filter-dorks"))
        compose.onNodeWithTag("filter-dorks").performClick()
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("details-dork"))
        compose.onNodeWithTag("method-dorks", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("details-one").assertDoesNotExist()
        compose.onNodeWithTag("details-dork").performClick(); await("screen-details-dork")
        compose.onNodeWithText("Consulta: $dork").assertExists()
        Espresso.pressBack(); await("screen-explore")
        tab("Início", "home-list")
    }

    @Test fun brazilIsDefaultEvenForRemoteJobsAndWorldChoiceSurvivesTabChanges() {
        val repo = Repository()
        val foreign = Job("outside", "lever:fixture", "us", "Java Remote Developer", "Fixture", "https://example.com/jobs/us",
            location = "Austin, United States", workplace = Workplace.REMOTE)
        repo.jobs.value = repo.jobs.value + TrackedJob(foreign)
        repo.preferences.value = repo.preferences.value.copy(
            query = repo.preferences.value.query.copy(remoteAnywhere = true), lastResultIds = setOf("one", "outside"))
        setUp(repo)
        tab("Vagas", "screen-explore")
        compose.onNodeWithText("1 oportunidades para sua busca").assertExists()
        compose.onNodeWithText("Mercado: Brasil").assertExists()

        tab("Início", "home-list")
        compose.onNodeWithContentDescription("Ajustar busca").performClick(); await("search-titles")
        compose.onNodeWithTag("search-worldwide").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertFalse(repo.preferences.value.query.worldwide) }
        compose.onNodeWithText("Encontrar vagas").performScrollTo().performClick()
        compose.onNodeWithTag("screen-explore").performScrollToIndex(0)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 oportunidades para sua busca").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mercado: Mundo").assertExists()
        compose.runOnIdle { assertTrue(repo.preferences.value.query.worldwide) }

        tab("Início", "home-list")
        tab("Vagas", "screen-explore")
        compose.onNodeWithText("2 oportunidades para sua busca").assertExists()
        compose.onNodeWithText("Editar filtros", substring = true).performScrollTo().performClick()
        compose.onNodeWithTag("search-worldwide").performScrollTo().assertIsOn()
    }

    @Test fun ibgeMunicipalitiesArePackagedForBrazilFilteringOnAndroid() {
        assertTrue(BrazilMarket.matches("São Gabriel da Palha / ES"))
        assertTrue(BrazilMarket.matches("Embu-Guaçu / SP"))
        assertFalse(BrazilMarket.matches("Santa Maria, California, United States"))
    }
}

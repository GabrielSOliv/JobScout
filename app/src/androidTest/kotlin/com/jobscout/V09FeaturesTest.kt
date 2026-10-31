package com.jobscout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.profile.presentation.OnboardingScreen
import com.jobscout.feature.profile.presentation.ResumeBuilderScreen
import com.jobscout.feature.search.presentation.GoogleSitesScreen
import com.jobscout.feature.search.presentation.ScoutUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class V09FeaturesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onboardingIncludesFullFiltersAndKeepsRemoteDefault() {
        var saved: SearchQuery? = null
        compose.setContent { JobScoutTheme { OnboardingScreen(AppPreferences()) { _, _, query -> saved = query } } }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Desenvolvimento").performClick()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithTag("filter-remote-anywhere").performScrollTo().assertIsOn()
        compose.onNodeWithText("PJ", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("date-7").performScrollTo().performClick()
        compose.onNodeWithTag("filter-hide-viewed").performScrollTo().performClick()
        compose.onNodeWithText("Salário mínimo divulgado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Começar varredura").performClick()
        compose.runOnIdle {
            assertTrue(saved!!.remoteAnywhere); assertEquals("PJ", saved!!.employment)
            assertEquals(7, saved!!.postedWithinDays); assertTrue(saved!!.hideViewed); assertFalse(saved!!.worldwide)
        }
    }
    @Test fun onboardingAreaNotOnListCanBeWrittenAndGoesToTitles() {
        var saved: SearchQuery? = null
        var interests: List<String> = emptyList()
        compose.setContent { JobScoutTheme { OnboardingScreen(AppPreferences()) { name, chosen, query -> interests = chosen; saved = query } } }
        compose.onNodeWithText("Continuar").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Outra área de atuação"))
        compose.onNodeWithTag("onboarding-custom-area").performTextInput("Eletricista")
        compose.onNodeWithTag("onboarding-add-area").performClick()
        compose.onNodeWithText("Eletricista").performScrollTo().assertExists()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithTag("search-titles").performScrollTo().assertTextContains("Eletricista", ignoreCase = true)
        compose.onNodeWithText("Começar varredura").performClick()
        compose.runOnIdle {
            assertTrue("Eletricista".uppercase() in saved!!.titles.single().uppercase())
            assertTrue(interests.contains("Eletricista"))
        }
    }
    @Test fun sitesCanBeAddedValidatedRemovedAndSaved() {
        var saved = emptyList<String>()
        compose.setContent { JobScoutTheme { GoogleSitesScreen(emptyList()) { saved = it } } }
        compose.onNodeWithTag("google-site-input").performTextInput("https://jobs.example.com/careers")
        compose.onNodeWithTag("google-site-add").performClick()
        compose.onNodeWithTag("google-sites-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("jobs.example.com"), saved) }
        compose.onNodeWithContentDescription("Remover jobs.example.com").performScrollTo().performClick()
        compose.onNodeWithTag("google-sites-save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }
    @Test fun curriculumRequiresNameForPdfAndSavesEditableFacts() {
        var saved: ResumeDocument? = null
        compose.setContent { JobScoutTheme { ResumeBuilderScreen(ScoutUiState(loading = false), {}, { saved = it }, { _, _ -> error("Picker must not open") }, {}, {}) } }
        compose.onNodeWithTag("resume-export").performScrollTo().performClick()
        compose.onNodeWithText("Informe seu nome para gerar o currículo.").assertExists()
        compose.onNodeWithTag("resume-name").performScrollTo().performTextInput("Pessoa de Teste")
        compose.onNodeWithTag("screen-profile").performScrollToNode(hasTestTag("profile-skills"))
        compose.onNodeWithTag("profile-skills").performTextInput("SQL, Kotlin")
        compose.onNodeWithTag("screen-profile").performScrollToNode(hasTestTag("resume-save"))
        compose.onNodeWithTag("resume-save").performClick()
        compose.runOnIdle { assertEquals("Pessoa de Teste", saved!!.fullName); assertEquals("SQL, Kotlin", saved!!.skills) }
    }
}

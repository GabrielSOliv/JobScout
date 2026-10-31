package com.jobscout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import com.jobscout.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import androidx.test.platform.app.InstrumentationRegistry
import com.jobscout.core.database.PreferencesStore
import com.google.gson.Gson
import com.jobscout.domain.AppPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private var previous: AppPreferences? = null
    private val settings = object : ExternalResource() {
        override fun before() = runBlocking {
            val store = PreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext, Gson())
            previous = store.flow.first()
            store.update { it.copy(onboardingComplete = true, autoScanOnOpen = false) }
        }
        override fun after() = runBlocking {
            previous?.let { old -> PreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext, Gson()).update {
                it.copy(onboardingComplete = old.onboardingComplete, autoScanOnOpen = old.autoScanOnOpen)
            } }
            Unit
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(settings).around(compose)
    @Test fun opensSearchProfileAndSettingsWithoutNetwork() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-Vagas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-Vagas").performClick()
        val editFilters = compose.onAllNodesWithText(" Editar filtros").fetchSemanticsNodes()
        if (editFilters.isNotEmpty()) compose.onNodeWithText(" Editar filtros").performClick()
        compose.onNodeWithTag("search-titles").performScrollTo().performTextReplacement("Java, Developer")
        compose.onNodeWithTag("search-titles").assertTextContains("Java, Developer")
        compose.onNodeWithTag("search-locations").performScrollTo().performTextReplacement("Sao Paulo, Brazil")
        compose.onNodeWithTag("search-locations").assertTextContains("Sao Paulo, Brazil")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Encontrar vagas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-Currículo").performClick()
        compose.onNodeWithTag("resume-export").assertIsDisplayed()
        compose.onNodeWithContentDescription("Configurações de vagas").assertDoesNotExist()
        compose.onNodeWithTag("nav-Vagas").performClick()
        compose.onNodeWithTag("screen-explore").performScrollToIndex(0)
        compose.onNodeWithTag("search-settings").performClick()
        compose.onNodeWithText("Fontes de oportunidades").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("screen-settings").performScrollToNode(hasText("Sua chave Freehire"))
        compose.onNodeWithText("Sua chave Freehire").assertIsDisplayed()
        compose.onNodeWithTag("screen-settings").performScrollToNode(hasText("Licenças de código aberto"))
        compose.onNodeWithText("Licenças de código aberto").performClick()
        compose.onNodeWithText("Licenças e créditos").assertIsDisplayed()
        compose.onNodeWithText("Fechar").performClick()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithTag("screen-explore").assertIsDisplayed()
        compose.onNodeWithTag("nav-Vagas").assertTextContains("Vagas")
        compose.onNodeWithTag("nav-Salvas").performClick()
        compose.onNodeWithText("Vagas que importam.").assertIsDisplayed()
    }
}

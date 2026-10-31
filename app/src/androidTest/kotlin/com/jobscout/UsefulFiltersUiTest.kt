package com.jobscout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.ScoutUiState
import com.jobscout.feature.search.presentation.SearchScreen
import com.jobscout.feature.search.presentation.ScoredJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsefulFiltersUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun textFilterInputKeepsMoreThanOneHundredTerms() {
        val terms = List(130) { "term$it" }
        assertEquals(terms, com.jobscout.core.designsystem.splitTerms(terms.joinToString(", ")))
    }
    @Test fun unconfirmedRequiredEnglishIsExplainedOnTheCapturedCard() {
        val job = Job("english-preview", "google:gupy", "1", "Java Developer", "Fixture", "https://fixture.gupy.io/jobs/1",
            "Brasil", description = "Java development.", descriptionComplete = false)
        val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
        val scored = ScoredJob(TrackedJob(job), RankingEngine.score(job, CandidateProfile(), query))
        val state = ScoutUiState(loading = false, preferences = AppPreferences(googleEnabled = false, query = query), googleCaptured = listOf(scored))
        compose.setContent { JobScoutTheme { SearchScreen(state, {}, { _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("results-captured").performScrollTo().performClick()
        compose.onNodeWithTag("screen-explore").performScrollToNode(hasTestTag("required-unconfirmed-english-preview"))
        compose.onNodeWithTag("required-unconfirmed-english-preview").assertTextContains("O resumo ainda não confirma", substring = true)
            .assertTextContains("Inglês", substring = true)
    }
    @Test fun datesAndQualityControlsSubmitTheActualSelectedCriteria() {
        var submitted: SearchQuery? = null
        val state = ScoutUiState(loading = false, preferences = AppPreferences(googleEnabled = false,
            query = SearchQuery(titles = listOf("Java"))))
        compose.setContent { JobScoutTheme { SearchScreen(state, { submitted = it }, { _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("date-7").performScrollTo().performClick()
        compose.onNodeWithTag("date-unknown").performScrollTo().performClick()
        compose.onNodeWithTag("date-discovery").performScrollTo().performClick()
        compose.onNodeWithTag("date-discovery").assertIsSelected()
        compose.onNodeWithTag("filters-quality").performScrollTo().performClick()
        compose.onNodeWithTag("filter-verified").performScrollTo().performClick()
        compose.onNodeWithTag("filter-hide-viewed").performScrollTo().performClick()
        compose.onNodeWithTag("filter-hide-applied").performScrollTo().performClick()
        compose.onNodeWithText("Encontrar vagas").performScrollTo().performClick()
        compose.runOnIdle {
            val query = submitted!!
            assertEquals(7, query.postedWithinDays)
            assertEquals(DateBasis.DISCOVERY, query.dateBasis)
            assertFalse(query.includeUnknownDates)
            assertTrue(query.onlyVerifiedPages)
            assertTrue(query.hideViewed)
            assertTrue(query.hideApplied)
            assertTrue(query.excludeTalentPools)
        }
    }
}

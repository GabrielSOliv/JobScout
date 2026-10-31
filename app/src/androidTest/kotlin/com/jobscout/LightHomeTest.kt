package com.jobscout

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import com.jobscout.core.designsystem.JobScoutTheme
import com.jobscout.domain.*
import com.jobscout.feature.search.presentation.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LightHomeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun homeRemainsWhiteWhenAndroidRequestsDarkTheme() {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                JobScoutTheme {
                    Surface {
                        HomeScreen(ScoutUiState(loading = false, preferences = AppPreferences(onboardingComplete = true)),
                            {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("home-search").assertIsDisplayed()
        compose.onNodeWithText("Recomendações").assertIsDisplayed()
        val pixel = compose.onNodeWithTag("home-list").captureToImage().toPixelMap()[2, 2]
        assertTrue("Home must be light even with system dark mode", pixel.red > .95f && pixel.green > .95f && pixel.blue > .95f)
    }
}

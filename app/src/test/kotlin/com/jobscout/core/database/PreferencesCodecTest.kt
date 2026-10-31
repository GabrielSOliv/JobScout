package com.jobscout.core.database

import com.google.gson.Gson
import com.jobscout.domain.*
import org.junit.Assert.*
import org.junit.Test

class PreferencesCodecTest {
    private val gson = Gson()

    @Test fun freshInstallStartsWithGoogleDorksEnabled() {
        assertTrue(PreferencesCodec.decode(null, gson).googleEnabled)
    }

    @Test fun upgradeFromPublic0100RestoresDorksWithoutChangingUserData() {
        val old = AppPreferences(googleEnabled = false, onboardingComplete = true,
            query = SearchQuery(titles = listOf("Kotlin"), locations = listOf("Recife")),
            googleSites = listOf("example.com"), displayName = "Pessoa exemplo",
            resumeDocument = ResumeDocument(fullName = "Pessoa exemplo"))
        val json = gson.toJsonTree(old).asJsonObject.apply { remove("googleDefaultsVersion") }
        val restored = PreferencesCodec.decode(json.toString(), gson)
        assertEquals(old.copy(googleEnabled = true), restored)
    }

    @Test fun explicitDisableAfterUpgradeSurvivesSerializationAndReload() {
        val upgraded = PreferencesCodec.decode(gson.toJson(AppPreferences(googleEnabled = false, googleDefaultsVersion = 0)), gson)
        assertTrue(upgraded.googleEnabled)
        val disabled = upgraded.copy(googleEnabled = false)
        assertEquals(disabled, PreferencesCodec.decode(gson.toJson(disabled), gson))
    }

    @Test fun legacyPreferencesWithoutGoogleFieldsUseOriginalDefault() {
        val json = gson.toJsonTree(AppPreferences()).asJsonObject.apply {
            remove("googleEnabled"); remove("googleDefaultsVersion")
        }
        assertTrue(PreferencesCodec.decode(json.toString(), gson).googleEnabled)
    }
}

package com.jobscout.core.database

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jobscout.domain.AppPreferences
import com.jobscout.domain.SourceCatalog
import com.jobscout.domain.SearchQuery
import com.jobscout.domain.DateBasis
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import java.io.IOException

private val Context.scoutPreferences by preferencesDataStore("scout_preferences")

@Singleton
class PreferencesStore @Inject constructor(@param:ApplicationContext private val context: Context, private val gson: Gson) {
    private val key = stringPreferencesKey("settings_v1")
    val flow: Flow<AppPreferences> = context.scoutPreferences.data.catch { error ->
        if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
    }.map { prefs -> PreferencesCodec.decode(prefs[key], gson) }

    suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        context.scoutPreferences.edit { prefs -> prefs[key] = gson.toJson(transform(PreferencesCodec.decode(prefs[key], gson))) }
    }
    suspend fun clear() { context.scoutPreferences.edit { it.clear() } }
}

internal object PreferencesCodec {
    fun decode(value: String?, gson: Gson): AppPreferences {
        if (value == null) return AppPreferences()
        val prefs = gson.fromJson(value, AppPreferences::class.java)
        val json = JsonParser.parseString(value).asJsonObject
        fun healQuery(query: SearchQuery, node: com.google.gson.JsonObject?) = query.copy(
            dateBasis = if (node?.has("dateBasis") == true) query.dateBasis else DateBasis.PUBLICATION,
            includeUnknownDates = if (node?.has("includeUnknownDates") == true) query.includeUnknownDates else true,
            excludeTalentPools = if (node?.has("excludeTalentPools") == true) query.excludeTalentPools else true,
            methods = query.methods.orEmpty())
        val healed = prefs.copy(query = healQuery(prefs.query, json.getAsJsonObject("query")),
            presets = prefs.presets.mapIndexed { index, preset -> preset.copy(query = healQuery(preset.query,
                json.getAsJsonArray("presets")?.get(index)?.asJsonObject?.getAsJsonObject("query"))) },
            // Restore the pre-publication default once for 0.10.0 installs. Later explicit choices persist.
            googleEnabled = if (json.has("googleDefaultsVersion") && prefs.googleDefaultsVersion >= 1) prefs.googleEnabled else true,
            googleDefaultsVersion = 1,
            googleSession = prefs.googleSession?.let { it.copy(pendingLinks = it.pendingLinks.orEmpty()) },
            googleResultIds = prefs.googleResultIds.orEmpty())
        return if (!json.has("discoveryVersion") || healed.discoveryVersion < 6) SourceCatalog.upgrade(healed) else healed
    }
}

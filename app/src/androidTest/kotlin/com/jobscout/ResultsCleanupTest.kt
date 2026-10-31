package com.jobscout

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jobscout.core.database.*
import com.jobscout.domain.*
import com.jobscout.feature.search.data.HtmlPageCache
import com.jobscout.feature.search.data.LocalScoutRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ResultsCleanupTest {
    @Test fun incrementalCollectionSurvivesCancellationBeforeTheSourceFinishes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ScoutDatabase::class.java).build()
        val gson = Gson(); val store = PreferencesStore(context, gson); val original = store.flow.first()
        val emitted = CompletableDeferred<Unit>()
        val job = Job("batch", "fixture:board", "1", "Java Developer", "Fixture", "https://example.com/jobs/1", "Brasil")
        val source = object : JobSource {
            override val kind = "fixture"
            override val displayName = "Test only"
            override val capabilities = SourceCapabilities(emptySet(), emptySet(), true)
            override suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult = error("Must use streaming overload")
            override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult {
                onProgress(SourceResult(config.key, listOf(job), fetched = 1, pagesRead = 1, limited = true, coverage = "Lendo próxima página…"))
                emitted.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            store.update { AppPreferences(onboardingComplete = true, sources = listOf(SourceConfig("fixture", "board", "Fixture")), query = SearchQuery(titles = listOf("Java"))) }
            val repo = LocalScoutRepository(database.jobs(), store, gson, setOf(source), HtmlPageCache(File(context.cacheDir, "stream-fixture")), object : JobPageVerifier { override suspend fun verify(job: Job) = job }, ApiCredentialStore(context))
            val collection = launch { repo.search() }
            try {
                withTimeout(10_000) { emitted.await() }
                assertNotNull(database.jobs().get("batch"))
                assertEquals(setOf("batch"), store.flow.first().lastResultIds)
                collection.cancelAndJoin()
                val stopped = store.flow.first()
                assertEquals("interrupted", stopped.scanStatus)
                assertEquals(setOf("batch"), stopped.lastResultIds)
                assertEquals(1, stopped.reports.single().fetched)
                assertTrue(stopped.reports.single().limited)
                assertNotNull(database.jobs().get("batch"))
            } finally { collection.cancelAndJoin() }
        } finally { store.update { original }; database.close() }
    }

    @Test fun cleanupDeletesOnlyUnsavedRoomRowsAndRebuildsCanonicalIds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ScoutDatabase::class.java).build()
        val gson = Gson(); val store = PreferencesStore(context, gson); val original = store.flow.first()
        try {
            val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
            val favorite = Job("favorite", "fixture", "1", "Java Developer", "Fixture", "https://example.com/jobs/1", "Brasil")
            val unsaved = favorite.copy(id = "unsaved", url = "https://example.com/jobs/2")
            val applied = favorite.copy(id = "applied", url = "https://example.com/jobs/3")
            val savedRow = JobEntity(favorite.id, gson.toJson(favorite), saved = true, viewed = true,
                status = "INTERVIEWING", notes = "Conservar anotações", statusAt = 123L)
            database.jobs().upsert(listOf(savedRow, JobEntity(unsaved.id, gson.toJson(unsaved)),
                JobEntity(applied.id, gson.toJson(applied), dismissed = true, status = "APPLIED", notes = "Sem favorito")))
            store.update { it.copy(query = query, profile = CandidateProfile(skills = listOf("SQL")),
                lastResultIds = setOf("favorite", "unsaved", "applied"), googleResultIds = setOf("favorite", "unsaved"),
                googleSession = GoogleSession("fixture", query, emptyList())) }
            val repo = LocalScoutRepository(database.jobs(), store, gson, emptySet(),
                HtmlPageCache(File(context.cacheDir, "cleanup-fixture")), object : JobPageVerifier { override suspend fun verify(job: Job) = job }, ApiCredentialStore(context))
            // Populate the canonical index before deletion, as a real previous collection does.
            repo.mergeDiscoveredJobs(listOf(unsaved))
            assertEquals(2, repo.clearUnsavedJobs())
            assertEquals(listOf(savedRow), database.jobs().all())
            val prefs = store.flow.first()
            assertNull(prefs.googleSession)
            assertEquals(setOf("favorite"), prefs.googleResultIds)
            assertEquals(setOf("favorite"), prefs.lastResultIds)
            assertEquals(query, prefs.query)
            assertEquals(listOf("SQL"), prefs.profile.skills)
            assertEquals(0, repo.clearUnsavedJobs())
            // Deleted identities must not remain mapped to a row that no longer exists.
            val fresh = unsaved.copy(id = "fresh")
            assertEquals(listOf("fresh"), repo.mergeDiscoveredJobs(listOf(fresh)))
            assertNotNull(database.jobs().get("fresh"))
            assertEquals(savedRow, database.jobs().get("favorite"))
        } finally { store.update { original }; database.close() }
    }
}

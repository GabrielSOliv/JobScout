package com.jobscout.feature.search.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jobscout.core.database.*
import com.jobscout.domain.*
import com.jobscout.domain.Job
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

@Singleton
class LocalScoutRepository @Inject constructor(
    private val dao: JobDao,
    private val store: PreferencesStore,
    private val gson: Gson,
    private val sources: Set<@JvmSuppressWildcards JobSource>,
    private val htmlCache: HtmlPageCache,
    private val pageVerifier: JobPageVerifier,
    private val apiCredentials: ApiCredentialStore,
) : ScoutRepository {
    private val mutex = Mutex()
    private val dataMutex = Mutex()
    private val canonicalIds = linkedMapOf<String, String>() // Access only while holding dataMutex.
    override val jobs = dao.observe().map { rows -> withContext(Dispatchers.Default) { rows.map { it.toDomain() } } }
    override val preferences = store.flow
    override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) = store.update(transform)

    private fun decodeJob(payload: String): Job {
        val job = gson.fromJson(payload, Job::class.java)
        val json = JsonParser.parseString(payload).asJsonObject
        val healed = job.copy(discoveryMethod = if (json.has("discoveryMethod")) job.discoveryMethod else "ATS",
            descriptionComplete = if (json.has("descriptionComplete")) job.descriptionComplete else true,
            discoveryOrigins = job.discoveryOrigins.orEmpty(),
            verification = if (json.has("verification") && !json.get("verification").isJsonNull) job.verification else JobVerification())
        return healed.copy(discoveryOrigins = JobProvenance.origins(healed))
    }
    private fun JobEntity.toDomain() = TrackedJob(decodeJob(payload), saved, viewed,
        dismissed, ApplicationStatus.valueOf(status), notes, statusAt, notified, stale)

    override suspend fun search(onSource: suspend (SourceResult) -> Unit): SearchSummary = withContext(Dispatchers.Default) { mutex.withLock {
        val prefs = preferences.first()
        val enabled = prefs.sources.filter { it.enabled }
        require(enabled.isNotEmpty()) { "Ative pelo menos um quadro em Ajustes." }
        val since = (prefs.lastAttempt ?: prefs.lastRun)?.let { System.currentTimeMillis() - it }
        require(since == null || since >= 30_000) { "Aguarde 30 segundos entre buscas para respeitar as fontes." }
        val old = dao.all().associateBy { it.id }
        dataMutex.withLock {
            old.values.sortedByDescending { it.saved || it.status != "NOT_APPLIED" }.forEach { row ->
                JobLinks.canonical(decodeJob(row.payload).url)?.let { canonicalIds.putIfAbsent(it, row.id) }
            }
        }
        val semaphore = Semaphore(2)
        // A fast failure (e.g. a search-engine CAPTCHA) must not blank the previous results
        // while slower sources are still running. New criteria are always applied to cached jobs.
        val partialIds = old.values.asSequence().filter { it.id in prefs.lastResultIds && !it.dismissed && !it.stale }
            .map { it.toDomain().job }.filter { JobFilter.matches(it, prefs.query, prefs.synonyms) }
            .map { it.id }.toMutableSet()
        val collectedIds = mutableSetOf<String>()
        val finishedReports = mutableListOf<SourceResult>()
        val progressReports = linkedMapOf<String, SourceResult>()
        store.update { it.copy(lastAttempt = System.currentTimeMillis(), scanStatus = "running", reports = emptyList()) }
        try {
        val reports = supervisorScope {
            enabled.sortedBy { it.kind == "web" }.map { config -> async(Dispatchers.IO) {
                semaphore.withPermit {
                    val sourceIds = mutableSetOf<String>()
                    suspend fun accept(rawResult: SourceResult, complete: Boolean): SourceResult = dataMutex.withLock {
                        var result = rawResult
                        if (result.error == null || result.jobs.isNotEmpty()) {
                            val jobs = JobLinks.deduplicate(result.jobs).map { incoming ->
                                val canonical = JobLinks.canonical(incoming.url)!!
                                val id = canonicalIds.getOrPut(canonical) { incoming.id }
                                incoming.copy(id = id)
                            }
                            collectedIds += jobs.map { it.id }
                            sourceIds += jobs.map { it.id }
                            val previousRows = jobs.associate { it.id to dao.get(it.id) }
                            val mergedJobs = jobs.map { job -> JobProvenance.merge(job, previousRows[job.id]?.let { decodeJob(it.payload) }, config.company) }
                            val entities = mergedJobs.map { job ->
                                val payload = gson.toJson(job)
                                previousRows[job.id]?.copy(payload = payload, stale = false) ?: JobEntity(job.id, payload)
                            }
                            dao.upsert(entities)
                            if (complete && !result.limited && result.error == null) {
                                val currentIds = jobs.map { it.id }.toSet()
                                dao.upsert(old.values.filter {
                                    decodeJob(it.payload).source == config.key && it.id !in currentIds && it.id !in collectedIds
                                }.mapNotNull { dao.get(it.id)?.copy(stale = true) })
                            }
                            val matched = mergedJobs.filter { JobFilter.matches(it, prefs.query, prefs.synonyms) }
                            partialIds += matched.map { it.id }
                            result = result.copy(jobs = mergedJobs, fetched = maxOf(result.fetched, sourceIds.size),
                                matched = sourceIds.count { it in partialIds })
                        }
                        val summary = result.copy(jobs = emptyList())
                        if (complete) { progressReports.remove(config.key); finishedReports += summary }
                        else progressReports[config.key] = summary
                        store.update { it.copy(lastResultIds = partialIds.toSet(), reports = finishedReports + progressReports.values) }
                        onSource(summary)
                        result
                    }
                    val rawResult = if (!config.board.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                        SourceResult(config.key, error = "Identificador do quadro inválido.")
                    } else sources.firstOrNull { it.kind == config.kind }?.search(config, prefs.query, prefs.profile) { batch ->
                        accept(batch.copy(limited = true), complete = false)
                    } ?: SourceResult(config.key, error = "Tipo de fonte não suportado.")
                    accept(rawResult, complete = true)
                }
            } }.awaitAll()
        }
        val failedSources = reports.filter { it.error != null }.map { it.source }.toSet()
        val cachedFailed = old.values.map { it.toDomain() }.filter {
            it.job.source in failedSources && !it.stale && JobFilter.matches(it.job, prefs.query, prefs.synonyms)
        }.map { it.job }
        val candidateIds = (reports.flatMap { it.jobs }.map { it.id } + cachedFailed.map { it.id }).toSet()
        val current = JobLinks.deduplicate(dao.all().filter { it.id in candidateIds }.map { decodeJob(it.payload) })
            .filter { JobFilter.matches(it, prefs.query, prefs.synonyms) }
        val newRelevant = current.filter { job ->
            val previous = old[job.id]
            previous == null && RankingEngine.score(job, prefs.profile, prefs.query, prefs.weights, prefs.synonyms).total >= prefs.notificationThreshold
        }.map { it.id }
        val summaries = reports.map { it.copy(jobs = emptyList()) }
        store.update { it.copy(lastRun = System.currentTimeMillis(), lastResultIds = current.map { job -> job.id }.toSet(), reports = summaries,
            scanStatus = if (reports.all { report -> report.error != null }) "failed" else if (reports.any { report -> report.error != null }) "partial" else "complete") }
        SearchSummary(current.size, summaries, newRelevant)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val pending = enabled.filter { config -> finishedReports.none { it.source == config.key } }
                    .map { (progressReports[it.key] ?: SourceResult(it.key)).copy(limited = true,
                        error = "Consulta interrompida; resultados já lidos foram preservados.", coverage = "Coleta parcial: interrompida antes do fim da fonte.") }
                store.update { it.copy(scanStatus = "interrupted", reports = finishedReports + pending, lastResultIds = partialIds.toSet()) }
            }
            throw cancelled
        }
    } }

    override suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: ApplicationStatus?, notes: String?) {
        dataMutex.withLock { dao.track(id, saved, viewed, dismissed, status?.name, notes, status?.let { System.currentTimeMillis() }) }
    }
    override suspend fun markNotified(ids: List<String>) { dataMutex.withLock { dao.notified(ids) } }
    override suspend fun clearUnsavedJobs(): Int = withContext(Dispatchers.Default) { mutex.withLock { dataMutex.withLock {
        val removed = dao.clearUnsaved()
        val kept = dao.all()
        val ids = kept.map { it.id }.toSet()
        canonicalIds.clear()
        kept.forEach { row -> JobLinks.canonical(decodeJob(row.payload).url)?.let { canonicalIds[it] = row.id } }
        store.update { prefs -> prefs.copy(lastResultIds = prefs.lastResultIds.intersect(ids),
            googleResultIds = prefs.googleResultIds.orEmpty().intersect(ids), googleSession = null,
            reports = emptyList(), scanStatus = "idle") }
        removed
    } } }
    override suspend fun clearAll() = mutex.withLock { dataMutex.withLock {
        apiCredentials.clear()
        dao.clear(); canonicalIds.clear(); store.clear(); withContext(Dispatchers.IO) { htmlCache.clear() }
        withContext(Dispatchers.Main) {
            // App WebView storage only; never touches the user's external browser.
            android.webkit.WebStorage.getInstance().deleteAllData()
            suspendCancellableCoroutine<Unit> { continuation -> android.webkit.CookieManager.getInstance().removeAllCookies {
                android.webkit.CookieManager.getInstance().flush()
                if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
            } }
        }
    } }
    override suspend fun importJob(job: Job) = withContext(Dispatchers.Default) { dataMutex.withLock {
        val canonical = JobLinks.canonical(job.url) ?: error("Link da vaga inválido.")
        val previous = dao.all().firstOrNull { JobLinks.canonical(decodeJob(it.payload).url) == canonical }
        val id = previous?.id ?: job.id
        val merged = JobProvenance.merge(job.copy(id = id, url = canonical), previous?.let { decodeJob(it.payload) })
        val payload = gson.toJson(merged)
        dao.upsert(previous?.copy(payload = payload, saved = true, stale = false) ?: JobEntity(id, payload, saved = true))
        canonicalIds[canonical] = id
        store.update { it.copy(lastResultIds = it.lastResultIds + id) }
    } }

    override suspend fun inspectGooglePage(html: String, url: String, task: GoogleDorkTask, query: SearchQuery): GooglePage =
        withContext(Dispatchers.Default) { GoogleResultsParser.parse(html, url, task, query, dictionary = store.flow.first().synonyms) }

    override suspend fun resolveGoogleLink(preview: GoogleLinkPreview, url: String, query: SearchQuery): Job? =
        withContext(Dispatchers.Default) { GoogleResultsParser.resolve(preview, url, query) }

    override suspend fun mergeDiscoveredJobs(jobs: List<Job>): List<String> = withContext(Dispatchers.Default) { dataMutex.withLock {
        val previous = dao.all().associateBy { it.id }
        previous.values.forEach { row -> JobLinks.canonical(decodeJob(row.payload).url)?.let { canonicalIds.putIfAbsent(it, row.id) } }
        val accepted = JobLinks.deduplicate(jobs).map { incoming ->
            val url = JobLinks.canonical(incoming.url)!!
            val id = canonicalIds.getOrPut(url) { incoming.id }
            val row = previous[id]
            val merged = JobProvenance.merge(incoming.copy(id = id, url = url), row?.let { decodeJob(it.payload) })
            row?.copy(payload = gson.toJson(merged), stale = false) ?: JobEntity(id, gson.toJson(merged))
        }
        dao.upsert(accepted)
        accepted.map { it.id }
    } }

    override suspend fun verifyJob(id: String) {
        val before = withContext(Dispatchers.Default) { dao.get(id)?.let { decodeJob(it.payload) } } ?: return
        val checked = pageVerifier.verify(before)
        withContext(Dispatchers.Default) { dataMutex.withLock {
            val current = dao.get(id) ?: return@withLock
            val latest = decodeJob(current.payload)
            val merged = JobProvenance.merge(checked, latest).copy(verification = checked.verification)
            dao.upsert(current.copy(payload = gson.toJson(merged)))
        } }
    }
}

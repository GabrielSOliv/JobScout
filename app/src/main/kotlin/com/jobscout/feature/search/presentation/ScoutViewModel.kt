package com.jobscout.feature.search.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobscout.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ScoredJob(val tracked: TrackedJob, val score: JobScore)
data class ActivityState(val searching: Boolean = false, val importing: Boolean = false,
    val message: String? = null, val progress: List<SourceResult> = emptyList(), val draft: ResumeDraft? = null,
    val importingWeb: Boolean = false, val webDraft: com.jobscout.domain.Job? = null,
    val savingSetup: Boolean = false, val setupError: String? = null, val waitingSeconds: Int = 0,
    val editSearchRequest: Int = 0, val savingWeb: Boolean = false, val savedWebId: String? = null,
    val googleInspectRequest: Long = 0L, val verifyingIds: Set<String> = emptySet(), val capturedResultsRequest: Int = 0,
    val exportingResume: Boolean = false, val clearingResults: Boolean = false)
data class ScoutUiState(val loading: Boolean = true, val preferences: AppPreferences = AppPreferences(),
    val all: List<ScoredJob> = emptyList(), val results: List<ScoredJob> = emptyList(), val activity: ActivityState = ActivityState(),
    val readError: String? = null, val googleCaptured: List<ScoredJob> = emptyList())
private data class RankingInput(val query: SearchQuery, val profile: CandidateProfile, val weights: ScoreWeights,
    val synonyms: Map<String, List<String>>, val resultIds: Set<String>, val capturedIds: Set<String>)
private data class RankedJobs(val all: List<ScoredJob>, val results: List<ScoredJob>, val captured: List<ScoredJob>)
private data class ScanCommand(val query: SearchQuery?, val includeGoogle: Boolean = true, val restartGoogle: Boolean = false)

@HiltViewModel
class ScoutViewModel @Inject constructor(
    private val repository: ScoutRepository,
    private val importer: ResumeImporter,
    private val scheduler: BackgroundScheduler,
    private val webImporter: WebJobImporter,
    private val resumeExporter: ResumeExporter? = null,
    val credentialSettings: FreehireCredentialSettings? = null,
) : ViewModel() {
    private val activity = MutableStateFlow(ActivityState())
    private val retryRead = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val scans = Channel<ScanCommand>(Channel.CONFLATED)
    private var requestedQuery: SearchQuery? = null
    private var activeScan: kotlinx.coroutines.Job? = null
    private var webTask: kotlinx.coroutines.Job? = null
    private var webSaveTask: kotlinx.coroutines.Job? = null
    private var resumeExportTask: kotlinx.coroutines.Job? = null
    private val google = GoogleSearchCoordinator(repository, viewModelScope)
    private var rankingKey: List<Any?>? = null
    private val scoreCache = linkedMapOf<String, Pair<com.jobscout.domain.Job, JobScore>>()
    fun retryLoading() { retryRead.tryEmit(Unit) }
    fun requestSearchEditor() { activity.update { it.copy(editSearchRequest = it.editSearchRequest + 1) } }
    fun requestCapturedResults() { activity.update { it.copy(capturedResultsRequest = it.capturedResultsRequest + 1) } }
    fun webSaveHandled() { activity.update { it.copy(savedWebId = null) } }

    // Progress, snackbar and countdown emissions never re-filter or re-rank the whole database.
    private val ranked = combine(repository.jobs, repository.preferences.map {
        RankingInput(it.query, it.profile, it.weights, it.synonyms, it.lastResultIds + it.googleResultIds.orEmpty(),
            it.googleResultIds.orEmpty() + it.googleSession?.foundIds.orEmpty())
    }.distinctUntilChanged()) { jobs, input ->
        val key = listOf(input.profile, input.query, input.weights, input.synonyms)
        if (rankingKey != key) { scoreCache.clear(); rankingKey = key }
        val scored = jobs.map { tracked ->
            val previous = scoreCache[tracked.job.id]
            val score = if (previous?.first == tracked.job) previous.second else
                RankingEngine.score(tracked.job, input.profile, input.query, input.weights, input.synonyms).also {
                    if (scoreCache.size < 2_000 || tracked.job.id in scoreCache) scoreCache[tracked.job.id] = tracked.job to it
                }
            ScoredJob(tracked, score)
        }
        RankedJobs(scored, scored.filter {
            it.tracked.job.id in input.resultIds && !it.tracked.dismissed &&
                !(input.query.hideViewed && it.tracked.viewed) &&
                !(input.query.hideApplied && it.tracked.status in setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFER)) &&
                JobFilter.matches(it.tracked.job, input.query, input.synonyms)
        }.sortedWith(compareByDescending<ScoredJob> { it.score.total }
            .thenByDescending { it.tracked.job.descriptionComplete }.thenByDescending { it.tracked.job.postedAt ?: 0L }),
            scored.filter { it.tracked.job.id in input.capturedIds && !it.tracked.dismissed }.sortedByDescending { it.tracked.job.collectedAt })
    }.flowOn(Dispatchers.Default)
    val state: StateFlow<ScoutUiState> = combine(ranked, repository.preferences, activity) { jobs, prefs, current ->
        ScoutUiState(false, prefs, jobs.all, jobs.results, current, googleCaptured = jobs.captured)
    }.flowOn(Dispatchers.Default).retryWhen { error, _ ->
        android.util.Log.e("JobScout", "Falha ao carregar dados locais", error)
        emit(ScoutUiState(loading = false, readError = "${error.javaClass.simpleName}: ${error.message}"))
        retryRead.first(); emit(ScoutUiState()); true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScoutUiState())

    init {
        viewModelScope.launch { val p = repository.preferences.first(); scheduler.configure(p.background, p.intervalHours)
            if (p.googleSession?.phase == GooglePhase.RUNNING) repository.updatePreferences { it.copy(googleSession = it.googleSession?.copy(
                phase = GooglePhase.PAUSED, message = "Pesquisa Google interrompida. Toque para retomar a sessão.")) }
        }
        viewModelScope.launch {
            scans.receiveAsFlow().collectLatest { command ->
                if (command.query != null) executeSearch(command.query, command.includeGoogle, command.restartGoogle)
                else activity.update { it.copy(searching = false, waitingSeconds = 0) }
            }
        }
    }
    private fun action(block: suspend () -> Unit): kotlinx.coroutines.Job = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { activity.update { it.copy(message = error.message ?: "Não foi possível concluir a ação.") } }
    }
    fun messageRead() { activity.update { it.copy(message = null) } }
    fun search(query: SearchQuery) = requestSearch(query, restartGoogle = true)
    private fun requestSearch(query: SearchQuery, includeGoogle: Boolean = true, restartGoogle: Boolean = false) {
        if (activity.value.clearingResults) return
        if (activity.value.searching && requestedQuery == query && !restartGoogle) return
        requestedQuery = query
        activity.update { it.copy(searching = true, waitingSeconds = 0, message = null) }
        scans.trySend(ScanCommand(query, includeGoogle, restartGoogle))
    }
    private suspend fun executeSearch(query: SearchQuery, includeGoogle: Boolean, restartGoogle: Boolean) {
        activeScan = currentCoroutineContext().job
        try {
            activity.update { it.copy(searching = true, progress = emptyList()) }
            repository.updatePreferences { it.copy(query = query) }
            if (includeGoogle) {
                val hasDork = query.titles.any(String::isNotBlank) && (query.methods.isEmpty() || DiscoveryMethod.DORKS in query.methods)
                google.prepare(query, force = restartGoogle && hasDork)
            }
            val prefs = repository.preferences.first()
            if (prefs.sources.none { it.enabled } && prefs.googleEnabled) return
            require(prefs.sources.any { it.enabled }) { "Ative pelo menos uma fonte em Configurações." }
            var wait = ((prefs.lastAttempt ?: prefs.lastRun)?.let { 30_000 - (System.currentTimeMillis() - it) } ?: 0L).coerceIn(0, 30_000)
            while (wait > 0) {
                activity.update { it.copy(waitingSeconds = ((wait + 999) / 1000).toInt()) }
                val part = minOf(wait, 1_000L); delay(part); wait -= part
            }
            activity.update { it.copy(waitingSeconds = 0) }
            val summary = repository.search { report -> activity.update { it.copy(progress = it.progress.filterNot { old -> old.source == report.source } + report) } }
            val failed = summary.reports.count { it.error != null }
            activity.update { it.copy(message = "${summary.found} vagas compatíveis. ${if (failed > 0) "$failed fonte(s) com falha; confira o diagnóstico." else "Busca concluída."}") }
        } catch (_: TimeoutCancellationException) {
            activity.update { it.copy(message = "A varredura atingiu o tempo limite. Resultados parciais foram preservados.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { activity.update { it.copy(message = error.message ?: "Não foi possível buscar agora.") } }
        finally { activeScan = null; activity.update { it.copy(searching = false, waitingSeconds = 0) } }
    }
    fun cancelSearch() {
        google.pause()
        requestedQuery = null; scans.trySend(ScanCommand(null))
        activity.update { it.copy(searching = false, waitingSeconds = 0, message = "Varredura interrompida. O que já foi encontrado continua no aparelho.") }
    }
    fun startGoogle(query: SearchQuery? = null) = action { google.prepare(query ?: repository.preferences.first().query, force = true) }
    fun googleSnapshot(html: String, url: String, session: String, request: Long, confirmed: Boolean) = google.snapshot(html, url, session, request, confirmed)
    fun googleResolvedTarget(url: String, session: String, request: Long, confirmed: Boolean) = google.resolvedTarget(url, session, request, confirmed)
    fun confirmGoogle() { activity.update { it.copy(googleInspectRequest = it.googleInspectRequest + 1) } }
    fun pauseGoogle(background: Boolean = false) { google.pause(background) }
    fun resumeGoogle() { google.resume() }
    fun reloadGoogle() { google.resume(reload = true) }
    fun skipGoogle() { google.skip() }
    fun finishGoogle() = action { google.finish(); activity.update { it.copy(message = "Coleta Google finalizada. Os links capturados foram preservados.") } }
    fun clearUnsavedJobs() {
        if (activity.value.clearingResults) return
        activity.update { it.copy(clearingResults = true) }
        action {
            try {
                requestedQuery = null
                scans.trySend(ScanCommand(null))
                activeScan?.cancelAndJoin()
                google.finish()
                val removed = repository.clearUnsavedJobs()
                activity.update { it.copy(progress = emptyList(), message = "$removed vagas não salvas removidas. Favoritos preservados.") }
            } finally { activity.update { it.copy(clearingResults = false) } }
        }
    }
    fun googleFailed(message: String, session: String, request: Long) { google.failed(message, session, request) }
    fun verifyJob(id: String) {
        if (id in activity.value.verifyingIds) return
        activity.update { it.copy(verifyingIds = it.verifyingIds + id) }
        action { try { repository.verifyJob(id) } finally { activity.update { it.copy(verifyingIds = it.verifyingIds - id) } } }
    }
    fun onAppOpened() = action {
        val prefs = repository.preferences.first()
        if (!prefs.onboardingComplete || !prefs.autoScanOnOpen || activity.value.searching || (prefs.sources.none { it.enabled } && !prefs.googleEnabled)) return@action
        val pendingGoogle = prefs.googleSession?.phase in setOf(GooglePhase.NEEDS_USER, GooglePhase.PAUSED, GooglePhase.ERROR)
        if (pendingGoogle && prefs.sources.none { it.enabled }) return@action
        val latest = listOfNotNull(prefs.lastAttempt ?: prefs.lastRun, prefs.googleSession?.startedAt).maxOrNull()
        if (latest != null && System.currentTimeMillis() - latest < prefs.startupCooldownMinutes * 60_000L) return@action
        requestSearch(DiscoveryPlanner.automaticQuery(prefs), includeGoogle = !pendingGoogle)
    }
    fun completeSetup(name: String, interests: List<String>, query: SearchQuery) = action {
        if (activity.value.savingSetup) return@action
        activity.update { it.copy(savingSetup = true, setupError = null) }
        try {
            require(query.titles.isNotEmpty()) { "Informe ao menos um cargo de interesse." }
            repository.updatePreferences { it.copy(displayName = name.trim().take(60), interests = interests.take(5), query = query,
                profile = it.profile.copy(targets = query.titles), onboardingComplete = true) }
            search(query)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { activity.update { it.copy(setupError = error.message ?: "Não foi possível salvar. Tente novamente.") } }
        finally { activity.update { it.copy(savingSetup = false) } }
    }
    fun importWebLink(text: String) {
        val previous = webTask
        activity.update { it.copy(importingWeb = true, webDraft = null) }
        webTask = action {
            previous?.cancelAndJoin()
            activity.update { it.copy(importingWeb = true) }
            try { val job = webImporter.importLink(text); activity.update { it.copy(webDraft = job) } }
            finally { activity.update { it.copy(importingWeb = false) } }
        }
    }
    fun discardWebDraft() { activity.update { it.copy(webDraft = null) } }
    fun saveWebDraft() {
        val job = activity.value.webDraft ?: return
        if (activity.value.savingWeb) return
        activity.update { it.copy(savingWeb = true) }
        webSaveTask = action {
            try {
                repository.importJob(job)
                activity.update { it.copy(webDraft = null, savedWebId = job.id, message = "Vaga importada e salva.") }
            } finally { activity.update { it.copy(savingWeb = false) } }
        }
    }
    fun bookmark(item: TrackedJob) = action { repository.track(item.job.id, saved = !item.saved) }
    fun viewed(id: String) = action { repository.track(id, viewed = true) }
    fun dismiss(item: TrackedJob) = action { repository.track(item.job.id, dismissed = !item.dismissed) }
    fun track(id: String, status: ApplicationStatus, notes: String) = action { repository.track(id, status = status, notes = notes) }
    fun saveProfile(profile: CandidateProfile) = action {
        repository.updatePreferences { it.copy(profile = profile) }
        activity.update { it.copy(draft = null, message = "Perfil revisado salvo somente neste aparelho.") }
    }
    fun saveResume(document: ResumeDocument) = action {
        require(document.toString().length <= 100_000) { "Reduza o currículo antes de salvar." }
        repository.updatePreferences { it.copy(resumeDocument = document, profile = document.toProfile(it.profile.targets)) }
        activity.update { it.copy(message = "Rascunho salvo neste aparelho.") }
    }
    fun exportResume(document: ResumeDocument, uri: String) {
        if (activity.value.exportingResume) return
        activity.update { it.copy(exportingResume = true) }
        resumeExportTask = action {
            try {
                require(document.validationError() == null) { document.validationError().orEmpty() }
                repository.updatePreferences { it.copy(resumeDocument = document, profile = document.toProfile(it.profile.targets)) }
                checkNotNull(resumeExporter) { "Exportação PDF indisponível." }.export(uri, document)
                activity.update { it.copy(message = "Currículo em PDF salvo no local escolhido.") }
            } finally { activity.update { it.copy(exportingResume = false) } }
        }
    }
    fun deleteResume() = action {
        repository.updatePreferences { it.copy(resumeDocument = ResumeDocument(), profile = CandidateProfile(targets = it.profile.targets)) }
        activity.update { it.copy(draft = null, message = "Currículo removido do app. PDFs exportados permanecem no local escolhido.") }
    }
    fun saveGoogleSites(sites: List<String>) = action {
        val normalized = GoogleSites.additional(sites)
        require(normalized.size == sites.size) { "Confira os sites e remova duplicados ou inválidos." }
        repository.updatePreferences { it.copy(googleSites = normalized) }
        activity.update { it.copy(message = "Sites salvos. Inicie uma nova busca Google para usá-los.") }
    }
    fun importPdf(uri: String) {
        if (activity.value.importing) return
        activity.update { it.copy(importing = true, draft = null) }
        action {
            try { val draft = importer.importDocument(uri); activity.update { it.copy(draft = draft) } }
            finally { activity.update { it.copy(importing = false) } }
        }
    }
    fun discardDraft() { activity.update { it.copy(draft = null) } }
    fun deleteProfile() = action {
        repository.updatePreferences { it.copy(profile = CandidateProfile()) }
        activity.update { it.copy(draft = null, message = "Perfil excluído. O PDF original não é armazenado pelo JobScout.") }
    }
    fun savePreset(name: String, query: SearchQuery) = action {
        require(name.isNotBlank()) { "Dê um nome à busca." }
        repository.updatePreferences { it.copy(presets = (it.presets.filterNot { p -> p.name == name } + SearchPreset(name.trim(), query)).takeLast(20)) }
        activity.update { it.copy(message = "Busca salva.") }
    }
    fun removePreset(name: String) = action { repository.updatePreferences { it.copy(presets = it.presets.filterNot { p -> p.name == name }) } }
    fun saveSettings(sources: List<SourceConfig>, weights: ScoreWeights, background: Boolean, interval: Long, threshold: Int,
                     synonyms: Map<String, List<String>>, autoOpen: Boolean, cooldown: Int, name: String, googleEnabled: Boolean = true) = action {
        require(sources.all { it.board.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.company.isNotBlank() }) { "Confira identificadores e empresas dos quadros." }
        require(sources.map { it.key }.distinct().size == sources.size) { "Remova os quadros duplicados." }
        require(sources.size <= 12 && sources.all { it.kind in SourceCatalog.kinds }) { "Use até 12 fontes de tipos suportados." }
        require(sources.filter { it.kind in setOf("rss", "web") }.all { it.endpoint?.let(JobLinks::canonical) != null }) { "Confira os endereços HTTPS das fontes." }
        repository.updatePreferences { it.copy(sources = sources, weights = weights, background = background,
            intervalHours = interval.coerceIn(3, 168), notificationThreshold = threshold.coerceIn(0, 100), synonyms = synonyms,
            autoScanOnOpen = autoOpen, startupCooldownMinutes = cooldown.coerceIn(1, 1440), displayName = name.trim().take(60), googleEnabled = googleEnabled) }
        if (!googleEnabled) google.pause()
        scheduler.configure(background, interval)
        activity.update { it.copy(message = "Configurações salvas.") }
    }
    fun clearAll() = action {
        google.stopForReset()
        cancelSearch()
        activeScan?.cancelAndJoin()
        webTask?.cancelAndJoin()
        webSaveTask?.cancelAndJoin()
        resumeExportTask?.cancelAndJoin()
        scheduler.configure(false, 12)
        repository.clearAll()
        activity.value = ActivityState(message = "Dados locais excluídos e buscas automáticas desativadas.")
    }
}

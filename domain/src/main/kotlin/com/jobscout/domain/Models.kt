package com.jobscout.domain

import kotlinx.coroutines.flow.Flow

enum class Workplace { REMOTE, HYBRID, ONSITE, UNKNOWN }
enum class ApplicationStatus { NOT_APPLIED, INTERESTED, APPLIED, INTERVIEWING, OFFER, REJECTED, WITHDRAWN }
enum class Seniority { INTERN, JUNIOR, MID, SENIOR, UNKNOWN }

data class Job(
    val id: String,
    val source: String,
    val sourceId: String,
    val title: String,
    val company: String,
    val url: String,
    val location: String = "",
    val workplace: Workplace = Workplace.UNKNOWN,
    val employment: String? = null,
    val seniority: Seniority = Seniority.UNKNOWN,
    val description: String = "",
    val skills: List<String> = emptyList(),
    val salaryMin: Double? = null,
    val salaryMax: Double? = null,
    val currency: String? = null,
    val salaryPeriod: String? = null,
    val postedAt: Long? = null,
    val collectedAt: Long = System.currentTimeMillis(),
    val firstSeen: Long = collectedAt,
    val lastSeen: Long = collectedAt,
    val discoveryMethod: String = "ATS",
    val descriptionComplete: Boolean = true,
    val logoUrl: String? = null,
    val discoveryOrigins: List<DiscoveryOrigin> = emptyList(),
    val verification: JobVerification = JobVerification(),
)

data class SearchQuery(
    val titles: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val workplaces: Set<Workplace> = emptySet(),
    val seniorities: Set<Seniority> = emptySet(),
    val excludedKeywords: List<String> = emptyList(),
    val excludedCompanies: List<String> = emptyList(),
    val company: String = "",
    val employment: String = "",
    val postedWithinDays: Int? = null,
    val salaryMin: Double? = null,
    val salaryCurrency: String = "BRL",
    val salaryPeriod: String = "month",
    val remoteAnywhere: Boolean = true,
    val worldwide: Boolean = false,
    val dateBasis: DateBasis = DateBasis.PUBLICATION,
    val includeUnknownDates: Boolean = true,
    val onlyVerifiedPages: Boolean = false,
    val excludeTalentPools: Boolean = true,
    val includeUnknownLocations: Boolean = false,
    val hideViewed: Boolean = false,
    val hideApplied: Boolean = false,
    val methods: Set<DiscoveryMethod> = emptySet(),
)

data class CandidateProfile(
    val skills: List<String> = emptyList(),
    val experience: String = "",
    val targets: List<String> = emptyList(),
    val education: String = "",
    val languages: List<String> = emptyList(),
)
data class ScoreWeights(val skills: Int = 40, val experience: Int = 25, val targets: Int = 20, val geography: Int = 15)
data class ScorePart(val name: String, val earned: Double, val weight: Double, val explanation: String)
data class JobScore(val total: Int, val parts: List<ScorePart>, val matched: List<String>, val missing: List<String>)
data class TrackedJob(
    val job: Job,
    val saved: Boolean = false,
    val viewed: Boolean = false,
    val dismissed: Boolean = false,
    val status: ApplicationStatus = ApplicationStatus.NOT_APPLIED,
    val notes: String = "",
    val statusAt: Long? = null,
    val notified: Boolean = false,
    val stale: Boolean = false,
)
data class SourceConfig(val kind: String, val board: String, val company: String, val enabled: Boolean = true, val endpoint: String? = null) {
    val key: String get() = "$kind:$board"
}
data class SourceCapabilities(val serverFilters: Set<String>, val localFilters: Set<String>, val pagination: Boolean)
data class SourceResult(val source: String, val jobs: List<Job> = emptyList(), val error: String? = null, val limited: Boolean = false,
                        val fetched: Int = 0, val matched: Int = 0, val pagesRead: Int = 0, val cacheHits: Int = 0,
                        val detailsRead: Int = 0, val queries: List<String> = emptyList(), val coverage: String? = null)
interface JobSource {
    val kind: String
    val displayName: String
    val capabilities: SourceCapabilities
    suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult
    suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile): SourceResult = search(config, query)
    suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult = search(config, query, profile)
}
data class SearchPreset(val name: String, val query: SearchQuery)
data class AppPreferences(
    val query: SearchQuery = SearchQuery(),
    val profile: CandidateProfile = CandidateProfile(),
    val weights: ScoreWeights = ScoreWeights(),
    val sources: List<SourceConfig> = SourceCatalog.defaults,
    val presets: List<SearchPreset> = emptyList(),
    val synonyms: Map<String, List<String>> = Synonyms.defaults,
    val background: Boolean = false,
    val intervalHours: Long = 12,
    val notificationThreshold: Int = 60,
    val lastRun: Long? = null,
    val lastResultIds: Set<String> = emptySet(),
    val reports: List<SourceResult> = emptyList(),
    val discoveryVersion: Int = 6,
    val autoScanOnOpen: Boolean = true,
    val startupCooldownMinutes: Int = 15,
    val onboardingComplete: Boolean = false,
    val displayName: String = "",
    val interests: List<String> = emptyList(),
    val lastAttempt: Long? = null,
    val scanStatus: String = "idle",
    val googleEnabled: Boolean = true,
    val googleDefaultsVersion: Int = 1,
    val googleSession: GoogleSession? = null,
    val googleResultIds: Set<String> = emptySet(),
    val googleSites: List<String>? = null,
    val resumeDocument: ResumeDocument? = null,
)
data class SearchSummary(val found: Int, val reports: List<SourceResult>, val newRelevantIds: List<String>)
interface ScoutRepository {
    val jobs: Flow<List<TrackedJob>>
    val preferences: Flow<AppPreferences>
    suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences)
    suspend fun search(onSource: suspend (SourceResult) -> Unit = {}): SearchSummary
    suspend fun track(id: String, saved: Boolean? = null, viewed: Boolean? = null, dismissed: Boolean? = null,
                      status: ApplicationStatus? = null, notes: String? = null)
    suspend fun markNotified(ids: List<String>)
    suspend fun clearAll()
    suspend fun clearUnsavedJobs(): Int { error("Limpeza de vagas não disponível.") }
    suspend fun importJob(job: Job) { error("Importação de links não disponível.") }
    suspend fun mergeDiscoveredJobs(jobs: List<Job>): List<String> { error("Coleta do navegador não disponível.") }
    suspend fun inspectGooglePage(html: String, url: String, task: GoogleDorkTask, query: SearchQuery): GooglePage { error("Leitura Google não disponível.") }
    suspend fun resolveGoogleLink(preview: GoogleLinkPreview, url: String, query: SearchQuery): Job? { error("Leitura do destino Google não disponível.") }
    suspend fun verifyJob(id: String) { error("Verificação de página não disponível.") }
}
data class ResumeDraft(val profile: CandidateProfile, val warnings: List<String>)
interface ResumeImporter { suspend fun importDocument(uri: String): ResumeDraft }
interface BackgroundScheduler { fun configure(enabled: Boolean, intervalHours: Long) }
interface WebJobImporter { suspend fun importLink(url: String): Job }
interface FreehireCredentialSettings {
    suspend fun hasStoredToken(): Boolean
    suspend fun save(value: String)
    suspend fun clear()
}

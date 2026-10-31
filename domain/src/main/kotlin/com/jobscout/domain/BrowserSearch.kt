package com.jobscout.domain

enum class DateBasis { PUBLICATION, DISCOVERY }
enum class VerificationState { UNVERIFIED, PAGE_READ, UNAVAILABLE, RESTRICTED, NOT_A_JOB, EXPIRED }
data class JobVerification(val state: VerificationState = VerificationState.UNVERIFIED, val checkedAt: Long? = null,
    val reason: String? = null, val validThrough: Long? = null)
interface JobPageVerifier { suspend fun verify(job: Job): Job }

enum class GooglePhase { RUNNING, NEEDS_USER, PAUSED, COMPLETE, ERROR }
enum class GooglePageKind { RESULTS, EMPTY, CAPTCHA, CONSENT, UNKNOWN }
enum class DorkStrategy { TITLE, EXPANDED }
data class GoogleDorkTask(val id: String, val board: String, val query: String, val url: String,
    val role: String? = null, val strategy: DorkStrategy? = null, val fallbackFor: String? = null,
    val roles: List<String>? = null) {
    // Nullable for checkpoints written before grouped queries existed (Gson skips constructors).
    val searchRoles: List<String> get() = roles?.takeIf { it.isNotEmpty() } ?: listOfNotNull(role)
}
data class GoogleLinkPreview(val wrapperUrl: String, val title: String, val snippet: String,
    val board: String, val query: String, val fetchedAt: Long)
data class GooglePage(val kind: GooglePageKind, val jobs: List<Job> = emptyList(), val nextUrl: String? = null,
    val message: String? = null, val pendingLinks: List<GoogleLinkPreview> = emptyList())
data class GoogleSession(val id: String, val query: SearchQuery, val tasks: List<GoogleDorkTask>,
    val taskIndex: Int = 0, val phase: GooglePhase = GooglePhase.RUNNING, val currentUrl: String? = null,
    val message: String? = null, val pagesRead: Int = 0, val pageInTask: Int = 0,
    val foundIds: Set<String> = emptySet(), val visitedUrls: Set<String> = emptySet(),
    val startedAt: Long = System.currentTimeMillis(), val updatedAt: Long = startedAt,
    val requestId: Long = 1L, val navigationReadyAt: Long = 0L,
    val pendingLinks: List<GoogleLinkPreview> = emptyList(), val nextPageUrl: String? = null, val pageUrl: String? = null,
    val humanConfirmations: Long = 0L, val taskAccepted: Int = 0, val taskCounts: Map<String, Int>? = null,
    val emptyPagePending: Boolean = false, val skippedTaskIds: Set<String>? = null)

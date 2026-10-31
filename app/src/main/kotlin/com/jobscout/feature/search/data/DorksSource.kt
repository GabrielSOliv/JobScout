package com.jobscout.feature.search.data

import com.jobscout.domain.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class DorksBlockedException : Exception("Buscador pediu CAPTCHA ou bloqueou consultas automáticas. Dorks pausados por 30 min; outras fontes continuam.")

object DorksPages {
    fun parse(html: String, config: SourceConfig, query: SearchQuery, executedQuery: String, fetchedAt: Long): List<Job> {
        val doc = Jsoup.parse(html, "https://html.duckduckgo.com/html/")
        if (doc.select("#challenge-form,.anomaly-modal,[action*=anomaly]").isNotEmpty() ||
            html.contains("Unfortunately, bots use DuckDuckGo too", ignoreCase = true)) throw DorksBlockedException()
        require(doc.select(".results,.result,.no-results").isNotEmpty()) { "Buscador retornou uma página não reconhecida; nenhum resultado foi inventado." }
        return doc.select(".result").filter {
            it.select(".result--ad,.result__badge--ad,[data-sponsored],.badge--ad").isEmpty() && !it.hasClass("result--ad")
        }.mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = DorksLinks.original(anchor.absUrl("href"), config.board) ?: return@mapNotNull null
            val title = anchor.text().removePrefix("Página da Vaga | ")
                .removeSuffix(" | LinkedIn").removeSuffix(" - LinkedIn").trim().take(220)
            if (title.isBlank()) return@mapNotNull null
            val snippet = result.selectFirst(".result__snippet")?.text().orEmpty().take(2_500)
            // Location is evidence in the indexed text, never copied merely because it was requested.
            val text = "$title $snippet"
            val exclusion = BrazilMarket.exclusionHint(text)
            val hint = BrazilMarket.locationHint(text)
            val mentioned = Geography.targets(query.locations).filter { containsTerm(text, it) && (hint == null || !containsTerm(hint, it)) }
            val location = exclusion ?: (listOfNotNull(hint) + mentioned).distinct().joinToString(" · ")
            val workplace = when {
                listOf("hibrido", "hybrid").any { containsTerm(text, it) } -> Workplace.HYBRID
                listOf("remoto", "remote", "home office").any { containsTerm(text, it) } -> Workplace.REMOTE
                containsTerm(text, "presencial") -> Workplace.ONSITE
                else -> Workplace.UNKNOWN
            }
            Job(JobLinks.stableId(config.key, url), config.key, url, title, "Empresa não informada", url,
                location, workplace, seniority = inferSeniority(title), description = snippet, collectedAt = fetchedAt,
                discoveryMethod = "Dorks · ${config.company} · DuckDuckGo", descriptionComplete = false,
                discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, config.key, config.company, fetchedAt, fetchedAt, executedQuery)))
        }.distinctBy { it.url }.take(20)
    }
}

/** One shared queue across both targets. Never rotates hosts, agents or proxies after a challenge. */
@Singleton
class DorksSource @Inject constructor(private val client: OkHttpClient, private val robots: RobotsGuard, private val cache: HtmlPageCache) : JobSource {
    override val kind = "dorks"
    override val displayName = "Dorks · LinkedIn e Gupy"
    override val capabilities = SourceCapabilities(setOf("site", "cargo", "local"), setOf("todos os filtros confirmados no resumo"), false)
    private val queue = Mutex()
    private var lastRequest = 0L
    private var blockedUntil = 0L
    override suspend fun search(config: SourceConfig, query: SearchQuery) = search(config, query, CandidateProfile())
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile): SourceResult = withContext(Dispatchers.IO) {
        val queries = DorksPlanner.queries(config.board, query, profile)
        val jobs = linkedMapOf<String, Job>()
        val errors = mutableListOf<String>()
        val executed = mutableListOf<String>()
        var pages = 0; var hits = 0
        if (queries.isEmpty()) return@withContext SourceResult(config.key, error = "Defina um cargo para executar dorks.", limited = true)
        for (dork in queries) {
            val region = if (query.worldwide) "wt-wt" else "br-pt"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(dork, "UTF-8")}&kl=$region"
            try {
                val page = queue.withLock {
                    cache.read(url)?.let { hits++; return@withLock it }
                    if (System.currentTimeMillis() < blockedUntil) throw DorksBlockedException()
                    delay((lastRequest + 8_000 - System.currentTimeMillis()).coerceAtLeast(0))
                    robots.check(url)
                    lastRequest = System.currentTimeMillis()
                    executed += dork
                    val html = try { client.publicText(url).also { pages++ } }
                    catch (error: PublicFetchException) {
                        if (error.code in setOf(401, 403, 429)) { blockedUntil = System.currentTimeMillis() + 1_800_000; throw DorksBlockedException() }
                        throw error
                    }
                    // Validate before writing: CAPTCHA and unknown pages must never enter the success cache.
                    try { DorksPages.parse(html, config, query, dork, lastRequest) }
                    catch (blocked: DorksBlockedException) { blockedUntil = System.currentTimeMillis() + 1_800_000; throw blocked }
                    cache.write(url, html, lastRequest)
                }
                if (page.cached) executed += dork
                DorksPages.parse(page.html, config, query, dork, page.fetchedAt).forEach { job ->
                    jobs[job.url] = JobProvenance.merge(job, jobs[job.url])
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                errors += if (error is DorksBlockedException) error.message.orEmpty() else publicError(error)
                break // No retry or alternate endpoint after a refusal.
            }
        }
        SourceResult(config.key, jobs.values.toList(), error = errors.distinct().joinToString(" ").ifBlank { null }, limited = true,
            fetched = jobs.size, pagesRead = pages, cacheHits = hits, queries = executed)
    }
}

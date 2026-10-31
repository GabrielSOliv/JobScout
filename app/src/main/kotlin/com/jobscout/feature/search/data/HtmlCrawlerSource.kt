package com.jobscout.feature.search.data

import com.jobscout.domain.*
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Bounded local page cache. A cache hit retains the actual fetch time. No resume is written here. */
class HtmlPageCache(private val directory: File) {
    data class Page(val html: String, val fetchedAt: Long, val cached: Boolean)
    @Synchronized fun read(url: String, now: Long = System.currentTimeMillis()): Page? = runCatching {
        val file = File(directory, JobLinks.stableId("html", url))
        if (!file.isFile || now - file.lastModified() !in 0..1_800_000L || file.length() > 8_000_000) return null
        Page(file.readText(), file.lastModified(), true)
    }.getOrNull()
    @Synchronized fun clear() { directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() } }
    @Synchronized fun write(url: String, html: String, now: Long = System.currentTimeMillis()): Page {
        runCatching {
            directory.mkdirs()
            val file = File(directory, JobLinks.stableId("html", url))
            file.writeText(html); file.setLastModified(now)
            var size = directory.listFiles().orEmpty().sumOf { it.length() }
            directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
                if (size > 24_000_000) { val bytes = it.length(); if (it.delete()) size -= bytes }
            }
        }
        return Page(html, now, false)
    }
}

object HtmlJobPages {
    fun listing(html: String, pageUrl: String, source: SourceConfig, fetchedAt: Long): List<Job> {
        val doc = Jsoup.parse(html, pageUrl)
        fun card(url: String, title: String, company: String, location: String, preview: String, logo: String?, date: String? = null): Job? {
            val canonical = JobLinks.canonical(url) ?: return null
            if (title.isBlank()) return null
            val place = if (source.board in setOf("vagas", "empregos") && Regex("(?:/|,| -)\\s*(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)\\b").containsMatchIn(location)) "$location · Brasil" else location
            return Job(JobLinks.stableId(source.key, canonical), source.key, canonical, title.trim(), company.trim().ifBlank { "Empresa não informada" },
                canonical, place.trim(), inferWorkplace("$title $location $preview"), seniority = inferSeniority(title),
                description = preview.take(2_500), postedAt = parseDate(date), collectedAt = fetchedAt,
                discoveryMethod = "Coleta HTML · ${source.company}", descriptionComplete = false,
                logoUrl = logo?.let(JobLinks::canonical))
        }
        return when (source.board) {
            "vagas" -> doc.select("li.vaga").mapNotNull { item ->
                val link = item.selectFirst("a.link-detalhes-vaga") ?: return@mapNotNull null
                val place = item.selectFirst(".vaga-local")?.clone()?.also { it.select(".tooltip-place,.tooltip-text").remove() }?.text().orEmpty()
                card(link.absUrl("href"), link.attr("title").ifBlank { link.text() }, item.selectFirst(".emprVaga")?.text().orEmpty(),
                    place, item.selectFirst(".detalhes")?.text().orEmpty(), item.selectFirst(".logoEmpresa img")?.absUrl("src"))
            }
            "empregos" -> doc.select("[id=job-card]").mapNotNull { item ->
                val link = item.selectFirst("a[href^=/vaga/]") ?: return@mapNotNull null
                val preview = item.clone().also { it.select("[style*=display:none],script,button").remove() }.text()
                card(link.absUrl("href"), item.selectFirst("h2")?.text().orEmpty(), item.selectFirst("h3 a")?.text().orEmpty(),
                    item.selectFirst("h3[title]")?.attr("title").orEmpty(), preview,
                    item.selectFirst("img[alt^=Logo]")?.absUrl("src"))
            }
            "python" -> doc.select(".list-recent-jobs > li").mapNotNull { item ->
                val company = item.selectFirst(".listing-company-name") ?: return@mapNotNull null
                val link = company.selectFirst("a[href]") ?: return@mapNotNull null
                card(link.absUrl("href"), link.text(), company.ownText(), item.selectFirst(".listing-location")?.text().orEmpty(),
                    item.selectFirst(".listing-job-type")?.text().orEmpty(), null, item.selectFirst("time")?.attr("datetime"))
            }
            else -> doc.select("a[href]").filter { JobLinks.canonical(it.absUrl("href"))?.let { url ->
                URI(url).host == URI(pageUrl).host && Regex("/(jobs?|careers?|vagas?)/.+", RegexOption.IGNORE_CASE).containsMatchIn(URI(url).path.orEmpty())
            } == true }.mapNotNull { link -> card(link.absUrl("href"), link.text(), "", "", "", null) }
        }.distinctBy { it.url }
    }

    fun detail(html: String, card: Job, fetchedAt: Long): Job {
        val doc = Jsoup.parse(html, card.url)
        val structured = runCatching { JobPostingParser.parse(html, card.url, fetchedAt) }.getOrNull()
        val nativeBody = when {
            URI(card.url).host == "www.python.org" -> doc.selectFirst(".job-description")
            URI(card.url).host?.endsWith("vagas.com.br") == true -> doc.selectFirst(".job-description__text")
            else -> null
        }?.html()?.let(::cleanHtml)?.takeIf(String::isNotBlank)
        require(structured != null || nativeBody != null) { "Página sem detalhes de vaga reconhecíveis." }
        val full = structured ?: card
        return full.copy(id = card.id, source = card.source, sourceId = card.sourceId, url = card.url,
            company = full.company.takeUnless { it == "Empresa não informada" || it.isBlank() } ?: card.company,
            location = full.location.ifBlank { card.location },
            workplace = full.workplace.takeUnless { it == Workplace.UNKNOWN } ?: card.workplace,
            postedAt = full.postedAt ?: card.postedAt, description = nativeBody ?: full.description,
            logoUrl = full.logoUrl ?: card.logoUrl, descriptionComplete = (nativeBody ?: full.description).isNotBlank(),
            discoveryMethod = card.discoveryMethod, collectedAt = fetchedAt, lastSeen = fetchedAt,
            verification = structured?.verification ?: JobVerification(VerificationState.PAGE_READ, fetchedAt,
                "Página original contém o bloco de descrição de vaga reconhecido deste portal."))
    }

    fun next(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val link = doc.selectFirst("a[rel~=next][href],link[rel~=next][href]") ?: doc.select("a[href]").firstOrNull {
            listOf(it.text(), it.attr("aria-label"), it.attr("title")).map(::normalized).any { label ->
                label in setOf("proxima", "proximo", "proxima pagina", "pagina seguinte", "next", "next page", "seguinte")
            }
        }
        return link?.absUrl("href")?.let(JobLinks::canonical)
    }
    private fun parseDate(raw: String?): Long? = raw?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(it.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
    private fun inferWorkplace(text: String) = when {
        listOf("hibrido", "hybrid").any { containsTerm(text, it) } -> Workplace.HYBRID
        listOf("remoto", "remote", "home office").any { containsTerm(text, it) } -> Workplace.REMOTE
        containsTerm(text, "presencial") -> Workplace.ONSITE
        else -> Workplace.UNKNOWN
    }
}

@Singleton
class HtmlCrawlerSource @Inject constructor(private val client: OkHttpClient, private val robots: RobotsGuard, private val cache: HtmlPageCache) : JobSource {
    override val kind = "web"
    override val displayName = "Coletor de páginas web"
    override val capabilities = SourceCapabilities(setOf("texto"), setOf("cargo", "local", "perfil", "modalidade"), true)
    override suspend fun search(config: SourceConfig, query: SearchQuery) = search(config, query, CandidateProfile())
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile): SourceResult = search(config, query, profile) {}
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult = withContext(Dispatchers.IO) {
        val terms = DiscoveryPlanner.webTerms(query, profile)
        val candidates = linkedMapOf<String, Job>()
        val errors = mutableListOf<String>()
        val visited = hashSetOf<String>()
        var pages = 0; var hits = 0; var details = 0
        val knownBoard = config.board in setOf("vagas", "empregos", "python")
        fun report(batch: List<Job>, coverage: String) = SourceResult(config.key,
            batch.filter { knownBoard || it.descriptionComplete }, error = errors.distinct().joinToString(" ").ifBlank { null },
            limited = true, fetched = candidates.size, pagesRead = pages, cacheHits = hits, detailsRead = details, queries = terms, coverage = coverage)
        suspend fun read(url: String): HtmlPageCache.Page {
            cache.read(url)?.let { hits++; return it }
            if (pages > 0) delay(750)
            robots.check(url)
            return retryPublic { cache.write(url, client.publicText(url)) }.also { pages++ }
        }
        fun slug(term: String) = URLEncoder.encode(normalized(term).replace(Regex("[^a-z0-9+#. ]"), " ").trim().replace(Regex("\\s+"), "-"), "UTF-8")
        val starts = when (config.board) {
            "vagas" -> terms.ifEmpty { listOf("") }.map { "https://www.vagas.com.br/vagas-de-${slug(it)}?ordenar_por=mais_recentes&pagina=1" }
            "empregos" -> terms.ifEmpty { listOf("") }.map { "https://www.empregos.com.br/vagas/${slug(it)}".trimEnd('/') }
            else -> listOfNotNull(config.endpoint?.let(JobLinks::canonical))
        }
        var blocked = false
        for (start in starts) {
            var url: String? = start
            val chainIds = hashSetOf<String>()
            while (url != null) {
                val current = url
                if (!visited.add(current)) { errors += "A fonte repetiu um endereço de paginação; coleta parcial preservada."; break }
                try {
                    val page = read(current)
                    val found = HtmlJobPages.listing(page.html, current, config, page.fetchedAt)
                    if (found.isNotEmpty() && found.none { it.url !in chainIds }) throw PaginationStopped("A fonte repetiu os mesmos links em outra página; coleta parcial preservada.")
                    chainIds += found.map { it.url }
                    found.forEach { candidates.putIfAbsent(it.url, it) }
                    onProgress(report(found, "Lendo os links de paginação publicados no site…"))
                    val next = HtmlJobPages.next(page.html, current)
                    if (next != null && URI(next).host != URI(start).host) throw PaginationStopped("Paginação apontou para outro domínio; endereço não foi acessado.")
                    url = next
                    if (found.isEmpty()) {
                        val text = normalized(Jsoup.parse(page.html).text())
                        if (listOf("nenhuma vaga", "nao encontramos vagas", "no jobs found", "no vacancies found").none(text::contains))
                            errors += "A página não apresentou vagas reconhecíveis no HTML; conteúdo dinâmico ou formato diferente pode impedir a coleta."
                        break
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { errors += publicError(error); blocked = error is PublicFetchException && error.code in setOf(401, 403, 429); break }
            }
            if (blocked) break
        }
        // Read every discovered detail; ranking changes order, never coverage.
        val shortlist = candidates.values.sortedByDescending { RankingEngine.score(it, profile, query).total }
        for (card in if (blocked) emptyList() else shortlist) {
            try {
                val page = read(card.url)
                val full = HtmlJobPages.detail(page.html, card, page.fetchedAt)
                candidates[card.url] = full; details++
                onProgress(report(listOf(full), "Lendo as descrições encontradas ($details/${shortlist.size})…"))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                errors += publicError(error)
                if (error is PublicFetchException && error.code in setOf(404, 410)) {
                    val expired = card.copy(verification = JobVerification(VerificationState.EXPIRED, System.currentTimeMillis(), "Página original removida (HTTP ${error.code})."))
                    candidates[card.url] = expired; onProgress(report(listOf(expired), "Página removida pela fonte."))
                } else if (error is PublicFetchException || error is java.io.IOException) break
            }
        }
        report(candidates.values.toList(), if (errors.isEmpty()) "Fim dos links de paginação publicados; todas as descrições encontradas foram lidas. O site pode ter vagas não expostas no HTML."
            else "Coleta parcial: confira os erros. O site pode exigir JavaScript ou limitar o acesso.")
    }
}

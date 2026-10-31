package com.jobscout.feature.search.data

import com.google.gson.JsonObject
import com.jobscout.domain.*
import com.jobscout.domain.Job
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PublicFetchException(val code: Int) : Exception("HTTP $code")
internal class PaginationStopped(message: String) : Exception(message)

internal suspend fun OkHttpClient.publicText(url: String, accept: String = "text/html,application/xhtml+xml", maxChars: Int = 2_000_000): String =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(Request.Builder().url(url).header("Accept", accept).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw PublicFetchException(it.code)
                        val body = it.body ?: throw IOException("Resposta vazia")
                        require(body.contentLength() <= maxChars * 4L) { "Resposta maior que o limite de leitura." }
                        body.charStream().use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (result.length + count > maxChars) throw IOException("Resposta muito extensa.")
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

internal fun publicError(error: Exception): String = when (error) {
    is PaginationStopped -> error.message.orEmpty()
    is PublicFetchException -> when (error.code) {
        400, 414 -> "A fonte recusou a consulta ou a próxima página (HTTP ${error.code}). Resultados anteriores preservados."
        403, 401 -> "Fonte bloqueou o acesso. Consulte a página original."
        429 -> "Limite da fonte atingido. Tente novamente mais tarde."
        else -> "Fonte respondeu HTTP ${error.code}."
    }
    else -> sourceError(error)
}

interface FreehireApi {
    @GET("api/v1/jobs/search")
    suspend fun search(@Query("q") query: String?, @Query("countries") countries: String?,
                       @Query("limit") limit: Int = 60, @Query("offset") offset: Int = 0,
                       @Header("Authorization") authorization: String): JsonObject
}

class FreehireSource @Inject constructor(private val api: FreehireApi, private val credentials: FreehireTokenProvider) : JobSource {
    override val kind = "freehire"
    override val displayName = "Freehire · índice global"
    override val capabilities = SourceCapabilities(setOf("texto", "país", "paginação"), setOf("título", "local", "modalidade", "salário", "data"), true)
    override suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult = search(config, query, CandidateProfile()) {}
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult {
        val jobs = linkedMapOf<String, Job>()
        var pages = 0
        val executed = mutableListOf<String>()
        return try {
        val terms = query.titles.flatMap { Synonyms.expand(it) }.distinct()
        val countryCodes = mapOf("brasil" to "br", "brazil" to "br", "portugal" to "pt", "canada" to "ca", "united states" to "us", "estados unidos" to "us")
        val countries = if (!query.worldwide) "br" else query.locations.mapNotNull { countryCodes[normalized(it)] }.distinct().joinToString(",").ifBlank { null }
        for (term in terms.map { it as String? }.ifEmpty { listOf(null) }) {
        if (executed.isNotEmpty()) delay(750)
        val q = term?.let { "\"${it.replace("\"", " ")}\"" }
        term?.let { executed += it }
        var offset = 0
        val seen = hashSetOf<String>()
        do {
            val root = retryPublic {
                val token = credentials.token() ?: throw MissingFreehireKeyException()
                api.search(q, countries, offset = offset, authorization = "Bearer ${FreehireKeyInput.normalize(token)}")
            }
            pages++
            val page = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Resposta sem vagas")
            if (page.isEmpty) break
            val identities = page.map { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject?.let { it.text("public_slug") ?: it.text("external_id") ?: it.text("url") } ?: raw.toString().hashCode().toString() }
            if (identities.none { it !in seen }) throw PaginationStopped("A fonte repetiu uma página; coleta parcial preservada.")
            seen += identities
            val batch = page.mapNotNull { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject?.let { mapJob(it, config) } }
            batch.forEach { jobs[it.id] = it }
            onProgress(SourceResult(config.key, batch, limited = true, fetched = jobs.size, pagesRead = pages,
                queries = executed.toList(), coverage = "Lendo a paginação de cada cargo no índice…"))
            offset += page.size()
            val total = root.obj("meta")?.text("total")?.toIntOrNull()
            val lowerBound = root.obj("meta")?.text("total_is_lower_bound") == "true"
            if (page.size() < 60 && !lowerBound && total != null && offset >= total) break
            delay(750)
        } while (true)
        }
        // A global search is a window over an index, never a complete company snapshot.
        SourceResult(config.key, jobs.values.toList(), limited = true, fetched = jobs.size, pagesRead = pages, queries = executed.toList(),
            coverage = "Fim das páginas disponíveis para todos os cargos e variantes consultados. O índice não representa todas as vagas da internet.")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { SourceResult(config.key, jobs.values.toList(), error = if (error is MissingFreehireKeyException)
        "Configure sua chave pessoal Freehire nos ajustes antes de usar esta fonte." else publicError(error), limited = true, fetched = jobs.size, pagesRead = pages, queries = executed.toList(),
        coverage = "Coleta parcial: o índice não foi lido até o fim.") }
    }

    internal fun mapJob(raw: JsonObject, config: SourceConfig): Job? {
        val url = JobLinks.canonical(raw.text("url") ?: return null) ?: return null
        val title = raw.text("title") ?: return null
        val id = raw.text("public_slug") ?: raw.text("external_id") ?: url
        val countries = raw.array("countries").mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.map {
            when (it.lowercase()) { "br" -> "Brasil"; "pt" -> "Portugal"; "us" -> "United States"; "ca" -> "Canada"; else -> it.uppercase() }
        }
        val location = (listOf(raw.text("location").orEmpty()) + countries).filter(String::isNotBlank).distinct().joinToString(" · ")
        return Job(JobLinks.stableId(config.key, id), config.key, id, title, raw.text("company") ?: "Empresa não informada", url,
            location, when (raw.text("work_mode")) { "remote" -> Workplace.REMOTE; "hybrid" -> Workplace.HYBRID; "onsite", "on-site" -> Workplace.ONSITE; else -> Workplace.UNKNOWN },
            employment = raw.text("employment_type"), seniority = inferSeniority(title), description = cleanHtml(raw.text("description").orEmpty()),
            skills = raw.array("skills").mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString },
            salaryMin = raw.text("salary_min")?.toDoubleOrNull(), salaryMax = raw.text("salary_max")?.toDoubleOrNull(),
            currency = raw.text("salary_currency"), salaryPeriod = raw.text("salary_period"),
            postedAt = raw.text("posted_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            discoveryMethod = "Índice web · Freehire", descriptionComplete = false)
    }
}

class RssSource @Inject constructor(private val client: OkHttpClient, private val robots: RobotsGuard) : JobSource {
    override val kind = "rss"
    override val displayName = "Feeds públicos RSS / Atom"
    override val capabilities = SourceCapabilities(emptySet(), setOf("título", "local", "palavras", "data"), false)
    override suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult = search(config, query, CandidateProfile()) {}
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult {
        val jobs = linkedMapOf<String, Job>()
        var pages = 0
        return try {
        val start = JobLinks.canonical(config.endpoint ?: error("Configure o endereço RSS.")) ?: error("Feed precisa de HTTPS público.")
        val visited = hashSetOf<String>()
        val seenItems = hashSetOf<String>()
        var url: String? = start
        while (url != null) {
            val current = url
            if (!visited.add(current)) throw PaginationStopped("O feed repetiu o próximo endereço; coleta parcial preservada.")
            if (SourceCatalog.defaults.none { it.endpoint == current }) robots.check(current)
            val xml = retryPublic { client.publicText(current, "application/rss+xml,application/atom+xml,application/xml,text/xml") }
            pages++
            val batch = parse(xml, config)
            if (batch.isNotEmpty() && batch.none { it.id !in seenItems }) throw PaginationStopped("O feed repetiu os mesmos itens; coleta parcial preservada.")
            seenItems += batch.map { it.id }
            batch.forEach { jobs[it.id] = it }
            onProgress(SourceResult(config.key, batch, limited = true, fetched = jobs.size, pagesRead = pages, coverage = "Lendo os itens publicados no feed…"))
            val next = Jsoup.parse(xml, current, Parser.xmlParser()).select("[rel=next][href]").firstOrNull {
                it.tagName().substringAfter(':') == "link" && it.parents().none { parent -> parent.tagName().substringAfter(':') in setOf("item", "entry") }
            }?.absUrl("href")?.let(JobLinks::canonical)
            if (next != null && java.net.URI(next).host != java.net.URI(start).host) throw PaginationStopped("O feed apontou a paginação para outro domínio; coleta parcial preservada.")
            if (next != null && batch.isEmpty()) throw PaginationStopped("O feed publicou uma página vazia com continuação; leitura interrompida sem progresso.")
            url = next
            if (url != null) delay(750)
        }
        SourceResult(config.key, jobs.values.toList(), limited = true, fetched = jobs.size, pagesRead = pages, coverage = "Todos os itens disponibilizados pelo feed foram lidos. O feed pode omitir vagas antigas.")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { SourceResult(config.key, jobs.values.toList(), error = publicError(error), limited = true, fetched = jobs.size, pagesRead = pages, coverage = "Coleta parcial: o feed não foi lido até o fim.") }
    }

    internal fun parse(xml: String, config: SourceConfig): List<Job> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "Feed com entidades externas não suportadas." }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        require(doc.allElements.any { it.normalName().substringAfter(':') in setOf("rss", "feed") }) { "A fonte não retornou RSS/Atom." }
        return doc.allElements.filter { it.normalName().substringAfter(':') in setOf("item", "entry") }.mapNotNull { item ->
            fun value(tag: String) = item.children().firstOrNull { it.normalName().substringAfter(':') == tag.lowercase() }?.text().orEmpty()
            val rawTitle = value("title")
            val url = JobLinks.fromSharedText(value("link").ifBlank { item.children().firstOrNull {
                it.normalName().substringAfter(':') == "link" && it.hasAttr("href") && it.attr("rel") in setOf("", "alternate")
            }?.attr("href").orEmpty() }) ?: return@mapNotNull null
            if (rawTitle.isBlank()) return@mapNotNull null
            val wwr = config.board == "wwr"
            val python = config.board == "python"
            val company = when { wwr && rawTitle.contains(": ") -> rawTitle.substringBefore(": "); python && rawTitle.contains(", ") -> rawTitle.substringAfterLast(", "); else -> value("company").ifBlank { "Empresa não informada" } }
            val title = when { wwr && rawTitle.contains(": ") -> rawTitle.substringAfter(": "); python && rawTitle.contains(", ") -> rawTitle.substringBeforeLast(", "); else -> rawTitle }
            val description = cleanHtml(value("description").ifBlank { value("content") }.ifBlank { value("summary") })
            val location = if (wwr) listOf(value("region"), value("country"), value("state")).filter(String::isNotBlank).distinct().joinToString(" · ")
                else if (python) description.lineSequence().firstOrNull().orEmpty().take(200) else value("location")
            val date = listOf("pubDate", "published", "updated").firstNotNullOfOrNull { tag ->
                val raw = value(tag)
                runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            }
            Job(JobLinks.stableId(config.key, url), config.key, url, title, company, url, location,
                if (wwr) Workplace.REMOTE else Workplace.UNKNOWN, employment = value("type").ifBlank { null },
                seniority = inferSeniority(title), description = description, postedAt = date, discoveryMethod = "RSS · ${config.company}")
        }
    }
}

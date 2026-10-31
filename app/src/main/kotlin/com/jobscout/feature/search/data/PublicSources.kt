package com.jobscout.feature.search.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jobscout.domain.*
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GreenhouseApi {
    @GET("v1/boards/{board}/jobs")
    suspend fun jobs(@Path("board") board: String, @Query("content") content: Boolean = true): JsonObject
}
interface LeverApi {
    @GET("v0/postings/{board}")
    suspend fun jobs(@Path("board") board: String, @Query("mode") mode: String = "json",
                     @Query("skip") skip: Int = 0, @Query("limit") limit: Int = 100): JsonArray
}

internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
internal fun cleanHtml(value: String): String = Jsoup.parse(org.jsoup.parser.Parser.unescapeEntities(value, false)).also { doc ->
    doc.select("br").append("\n")
    doc.select("p,li,div,h1,h2,h3").prepend("\n")
}.wholeText().trim().take(100_000)

internal suspend fun <T> retryPublic(block: suspend () -> T): T {
    repeat(3) { attempt ->
        try { return block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val retryable = error is IOException || error is HttpException && error.code() in listOf(500, 502, 503, 504) ||
                error is PublicFetchException && error.code in listOf(500, 502, 503, 504)
            // Do not immediately retry 429; let the next manual/scheduled run respect the server limit.
            if (!retryable || attempt == 2) throw error
            delay(1_000L shl attempt)
        }
    }
    error("Unreachable")
}

internal fun sourceError(error: Exception): String = when (error) {
    is PaginationStopped -> error.message.orEmpty()
    is HttpException -> when (error.code()) {
        404 -> "Quadro não encontrado. Confira o identificador da empresa."
        429 -> "Limite de consultas da fonte. Tente mais tarde."
        401, 403 -> "Acesso não permitido pela fonte."
        else -> "Fonte indisponível (HTTP ${error.code()})."
    }
    is IOException -> "Falha de conexão ou tempo limite. Dados anteriores continuam no aparelho."
    else -> "Resposta incompatível da fonte; nenhuma vaga foi inventada."
}

class GreenhouseSource @Inject constructor(private val api: GreenhouseApi) : JobSource {
    override val kind = "greenhouse"
    override val displayName = "Greenhouse Job Board"
    override val capabilities = SourceCapabilities(emptySet(), setOf("título", "palavras", "local", "modalidade", "empresa", "senioridade", "salário", "data"), false)
    override suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult = try {
        val root = retryPublic { api.jobs(config.board) }
        val data = root.get("jobs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IOException("Invalid jobs response")
        val jobs = data.mapNotNull { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject?.let { mapJob(it, config) } }
        val incomplete = root.obj("meta")?.text("total")?.toIntOrNull()?.let { it > data.size() } == true
        SourceResult(config.key, jobs, limited = incomplete, fetched = jobs.size, pagesRead = 1,
            coverage = if (incomplete) "A fonte informou mais vagas do que entregou na resposta." else "Todas as vagas entregues pelo quadro foram lidas.")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { SourceResult(config.key, error = sourceError(error)) }

    internal fun mapJob(raw: JsonObject, config: SourceConfig): Job? {
        val sourceId = raw.text("id") ?: return null
        val url = JobLinks.canonical(raw.text("absolute_url") ?: return null) ?: return null
        val title = raw.text("title") ?: return null
        val location = raw.obj("location")?.text("name").orEmpty()
        val description = cleanHtml(raw.text("content").orEmpty())
        val workplace = when {
            containsTerm(location, "hybrid") || containsTerm(location, "hibrido") -> Workplace.HYBRID
            containsTerm(location, "remote") || containsTerm(location, "remoto") -> Workplace.REMOTE
            else -> Workplace.UNKNOWN
        }
        return Job(JobLinks.stableId(config.key, sourceId), config.key, sourceId, title, config.company, url,
            location, workplace, seniority = inferSeniority(title), description = description,
            postedAt = raw.text("first_published")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
        // updated_at is an edit timestamp, never a posting date. Salary and employment remain unknown.
    }
}

class LeverSource @Inject constructor(private val api: LeverApi) : JobSource {
    override val kind = "lever"
    override val displayName = "Lever Postings"
    override val capabilities = SourceCapabilities(setOf("paginação"), setOf("título", "palavras", "local", "modalidade", "empresa", "senioridade", "salário", "data"), true)
    override suspend fun search(config: SourceConfig, query: SearchQuery): SourceResult = search(config, query, CandidateProfile()) {}
    override suspend fun search(config: SourceConfig, query: SearchQuery, profile: CandidateProfile, onProgress: suspend (SourceResult) -> Unit): SourceResult {
        val jobs = linkedMapOf<String, Job>()
        var skip = 0
        var pages = 0
        val seen = hashSetOf<String>()
        return try {
        do {
            val page = retryPublic { api.jobs(config.board, skip = skip) }
            pages++
            if (page.isEmpty) break
            val identities = page.map { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject?.let { it.text("id") ?: it.text("hostedUrl") } ?: raw.toString().hashCode().toString() }
            if (identities.none { it !in seen }) throw PaginationStopped("A fonte repetiu uma página; coleta parcial preservada.")
            seen += identities
            val batch = page.mapNotNull { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject?.let { mapJob(it, config) } }
            batch.forEach { jobs[it.id] = it }
            onProgress(SourceResult(config.key, batch, limited = true, fetched = jobs.size, pagesRead = pages, coverage = "Lendo a paginação do quadro…"))
            skip += page.size()
            if (page.size() < 100) break
            delay(750)
        } while (true)
        SourceResult(config.key, jobs.values.toList(), fetched = jobs.size, pagesRead = pages, coverage = "Fim da paginação do quadro alcançado.")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { SourceResult(config.key, jobs.values.toList(), error = sourceError(error), limited = true, fetched = jobs.size, pagesRead = pages, coverage = "Coleta parcial: a paginação não foi concluída.") }
    }

    internal fun mapJob(raw: JsonObject, config: SourceConfig): Job? {
        val sourceId = raw.text("id") ?: return null
        val url = JobLinks.canonical(raw.text("hostedUrl") ?: return null) ?: return null
        val title = raw.text("text") ?: return null
        val categories = raw.obj("categories")
        val description = buildString {
            appendLine(raw.text("descriptionPlain") ?: cleanHtml(raw.text("description").orEmpty()))
            raw.array("lists").forEach { entry ->
                appendLine(entry.asJsonObject.text("text").orEmpty())
                appendLine(cleanHtml(entry.asJsonObject.text("content").orEmpty()))
            }
            appendLine(raw.text("additionalPlain") ?: cleanHtml(raw.text("additional").orEmpty()))
        }.trim().take(100_000)
        val salary = raw.obj("salaryRange")
        val period = when (salary?.text("interval")?.lowercase()) { "per-year", "year", "yearly" -> "year"; "per-month", "month", "monthly" -> "month"; "per-hour", "hour", "hourly" -> "hour"; else -> null }
        return Job(JobLinks.stableId(config.key, sourceId), config.key, sourceId, title, config.company, url,
            categories?.text("location").orEmpty(), when (raw.text("workplaceType")) {
                "remote" -> Workplace.REMOTE; "hybrid" -> Workplace.HYBRID; "on-site" -> Workplace.ONSITE; else -> Workplace.UNKNOWN
            }, categories?.text("commitment"), inferSeniority(title), description,
            salaryMin = salary?.text("min")?.toDoubleOrNull(), salaryMax = salary?.text("max")?.toDoubleOrNull(),
            currency = salary?.text("currency"), salaryPeriod = period,
            postedAt = raw.text("createdAt")?.toLongOrNull())
    }
}

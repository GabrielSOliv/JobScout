package com.jobscout.feature.search.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jobscout.domain.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Small RFC-style allow/disallow matcher; a missing robots file permits a public read. */
object RobotsPolicy {
    fun allows(text: String, url: String): Boolean {
        require(!text.trimStart().startsWith("<")) { "O site não retornou regras robots legíveis. Abra a vaga no navegador." }
        data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Pair<Boolean, String>> = mutableListOf())
        val groups = mutableListOf<Group>()
        var group = Group()
        text.lineSequence().forEach { line ->
            val clean = line.substringBefore('#').trim()
            val key = clean.substringBefore(':').trim().lowercase()
            val value = clean.substringAfter(':', "").trim()
            when (key) {
                "user-agent" -> {
                    if (group.rules.isNotEmpty()) { groups += group; group = Group() }
                    group.agents += value.lowercase()
                }
                "allow", "disallow" -> if (value.isNotEmpty()) group.rules += (key == "allow") to value
            }
        }
        groups += group
        val specific = groups.filter { it.agents.any { agent -> agent != "*" && "jobscout".contains(agent) } }
        val selected = specific.ifEmpty { groups.filter { "*" in it.agents } }
        val uri = URI(url)
        val path = uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty()
        val matching = selected.flatMap { it.rules }.filter { (_, rule) ->
            val pattern = rule.removeSuffix("$").split('*').joinToString(".*") { Regex.escape(it) }
            Regex("^$pattern${if (rule.endsWith('$')) "$" else ""}").containsMatchIn(path)
        }
        return matching.maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.length }.thenBy { it.first })?.first ?: true
    }
}

@Singleton
class RobotsGuard @Inject constructor(private val client: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    suspend fun check(url: String) {
        val uri = URI(url)
        val root = "https://${uri.host}"
        val cached = cache[root]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }
        val rules = cached?.second ?: try {
            client.publicText("$root/robots.txt", "text/plain", 250_000).also { cache[root] = System.currentTimeMillis() to it }
        } catch (error: PublicFetchException) {
            if (error.code == 404 || error.code == 410) "" else throw IllegalArgumentException("Não foi possível confirmar acesso permitido pelo site (robots HTTP ${error.code}).")
        }
        require(RobotsPolicy.allows(rules, url)) { "O site restringe essa leitura automatizada. Abra a vaga no navegador." }
        val seconds = Regex("(?im)^crawl-delay\\s*:\\s*(\\d+(?:\\.\\d+)?)").findAll(rules)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 1.0
        require(seconds <= 30) { "O site pede um intervalo longo de coleta. Abra a vaga no navegador." }
        delay((seconds.coerceAtLeast(1.0) * 1_000).toLong())
    }
}

object JobPostingParser {
    fun parse(html: String, pageUrl: String, fetchedAt: Long = System.currentTimeMillis()): Job {
        val canonical = JobLinks.canonical(pageUrl) ?: error("Escolha um link HTTPS público de uma vaga.")
        val document = Jsoup.parse(html, canonical)
        fun find(node: JsonElement): JsonObject? {
            if (node.isJsonArray) return node.asJsonArray.firstNotNullOfOrNull(::find)
            if (!node.isJsonObject) return null
            val obj = node.asJsonObject
            val type = obj.get("@type")
            if (type?.isJsonPrimitive == true && type.asString == "JobPosting" ||
                type?.isJsonArray == true && type.asJsonArray.any { it.isJsonPrimitive && it.asString == "JobPosting" }) return obj
            return obj.get("@graph")?.let(::find)
        }
        val job = document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { find(JsonParser.parseString(script.data())) }.getOrNull()
        } ?: error("Esta página não expõe uma vaga JobPosting legível. Abra o link original; páginas que exigem JavaScript podem não permitir importação.")
        val title = job.text("title")?.let(::cleanHtml)?.takeIf(String::isNotBlank) ?: error("Página sem título de vaga.")
        val organization = job.obj("hiringOrganization")?.text("name")?.let(::cleanHtml) ?: "Empresa não informada"
        val locations = job.get("jobLocation")?.let { if (it.isJsonArray) it.asJsonArray.toList() else listOf(it) }.orEmpty()
        val location = locations.mapNotNull { entry ->
            entry.takeIf { it.isJsonObject }?.asJsonObject?.obj("address")?.let { address ->
                listOf(address.text("addressLocality"), address.text("addressRegion"), address.text("addressCountry")
                    ?: address.obj("addressCountry")?.text("name")).filterNotNull().joinToString(", ").takeIf(String::isNotBlank)
            }
        }.distinct().joinToString(" · ")
        val date = job.text("datePosted")?.let { raw ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
        val validThrough = job.text("validThrough")?.let { raw ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(raw).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1 }.getOrNull()
        }
        val expired = validThrough?.let { it < fetchedAt } == true
        val salary = job.obj("baseSalary")
        val amount = salary?.obj("value")
        val unit = amount?.text("unitText")?.lowercase()
        val employment = job.text("employmentType") ?: job.get("employmentType")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonPrimitive }?.joinToString(", ") { it.asString }
        val description = cleanHtml(job.text("description").orEmpty())
        return Job(JobLinks.stableId("web", canonical), "web:${URI(canonical).host}", canonical, title, organization, canonical,
            location, if (job.text("jobLocationType") == "TELECOMMUTE") Workplace.REMOTE else Workplace.UNKNOWN,
            employment = employment, seniority = inferSeniority(title), description = description, descriptionComplete = description.isNotBlank(),
            salaryMin = amount?.text("minValue")?.toDoubleOrNull() ?: amount?.text("value")?.toDoubleOrNull(), salaryMax = amount?.text("maxValue")?.toDoubleOrNull(),
            currency = salary?.text("currency"), salaryPeriod = when (unit) { "month", "monthly" -> "month"; "year", "yearly" -> "year"; "hour", "hourly" -> "hour"; else -> null },
            logoUrl = job.obj("hiringOrganization")?.text("logo")?.let(JobLinks::canonical),
            postedAt = date, collectedAt = fetchedAt, discoveryMethod = "Página web · JobPosting",
            verification = JobVerification(if (expired) VerificationState.EXPIRED else VerificationState.PAGE_READ,
                fetchedAt, if (expired) "O prazo validThrough publicado pela fonte já passou." else "Página original contém uma publicação JobPosting legível.", validThrough))
    }
}

class LocalWebJobImporter @Inject constructor(private val client: OkHttpClient, private val robots: RobotsGuard) : WebJobImporter {
    override suspend fun importLink(url: String): Job = withContext(Dispatchers.IO) {
        val canonical = JobLinks.fromSharedText(url) ?: error("Cole o link HTTPS de uma vaga, sem anúncios ou redirecionadores de publicidade.")
        robots.check(canonical)
        val html = client.publicText(canonical)
        JobPostingParser.parse(html, canonical)
    }
}

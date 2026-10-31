package com.jobscout.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Search operators supported by the HTML search provider. No profile history is sent. */
object DorksPlanner {
    fun queries(board: String, query: SearchQuery, profile: CandidateProfile): List<String> {
        val scope = when (board) {
            "linkedin" -> "site:linkedin.com"
            "gupy" -> "site:gupy.io"
            else -> return emptyList()
        }
        fun clean(value: String) = value.replace(Regex("[\"\\r\\n:()]"), " ").trim().take(80)
        // Roles come only from typed titles; profile targets are never borrowed silently.
        val roles = query.titles.map(::clean).filter(String::isNotBlank).distinct().take(2)
        val acceptsRemote = query.workplaces.isEmpty() || Workplace.REMOTE in query.workplaces
        val places = (if (query.remoteAnywhere && acceptsRemote) emptyList() else Geography.targets(query.locations)).take(2)
            .ifEmpty { listOf(if (query.worldwide) "" else "Brasil") }
        val excluded = query.excludedKeywords.take(3).map(::clean).filter(String::isNotBlank).joinToString(" ") { "-\"$it\"" }
        // With two slots, give both selected roles/cities coverage instead of spending both on role one.
        val combinations = if (roles.size == 1) places.map { roles.single() to it }
            else roles.mapIndexed { index, role -> role to places[index % places.size] }
        return combinations.map { (role, place) ->
            "$scope $role ${if (place.isBlank()) "" else "\"${clean(place)}\""} $excluded".trim()
        }.distinct().take(2)
    }
}

/** Only individual listings are accepted; a search engine may ignore site: and return unrelated pages. */
object DorksLinks {
    // Gupy's observed /job/ links encode a small flat JSON object, e.g. jobId + source.
    // Validate the identity but preserve the original token URL rather than inventing another route.
    private val jsonString = "\"(?:[^\"\\\\\u0000-\u001F]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\""
    private val jsonScalar = "(?:$jsonString|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)"
    private val jsonPair = "$jsonString\\s*:\\s*$jsonScalar"
    private val flatJson = Regex("^\\s*\\{\\s*(?:$jsonPair(?:\\s*,\\s*$jsonPair)*)?\\s*\\}\\s*$")
    private val jobIdKey = Regex("\"jobId\"\\s*:")
    private val positiveJobId = Regex("\"jobId\"\\s*:\\s*([1-9][0-9]{0,18})(?=\\s*[,}])")

    private fun isGupyTokenJob(uri: URI): Boolean = runCatching {
        if (!uri.rawPath.startsWith("/job/")) return false
        val rawToken = uri.rawPath.removePrefix("/job/").removeSuffix("/")
        if (rawToken.length !in 8..6_144 || '/' in rawToken) return false
        // A plus in a path is literal; URLDecoder's query-style '+' conversion must not change it.
        val token = URLDecoder.decode(rawToken.replace("+", "%2B"), "UTF-8")
        if (!token.matches(Regex("[A-Za-z0-9+/=_-]{8,2048}"))) return false
        val bytes = (if ('-' in token || '_' in token) Base64.getUrlDecoder() else Base64.getDecoder()).decode(token)
        val json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        flatJson.matches(json) && jobIdKey.findAll(json).count() == 1 &&
            positiveJobId.find(json)?.groupValues?.get(1)?.toLongOrNull()?.let { it > 0 } == true
    }.getOrDefault(false)

    fun original(raw: String, board: String): String? = runCatching {
        val absolute = if (raw.startsWith("//")) "https:$raw" else raw
        val wrapper = URI(absolute)
        val resolved = if (wrapper.host in setOf("duckduckgo.com", "html.duckduckgo.com") && wrapper.path == "/l/") {
            wrapper.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "uddg" }
                ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        } else absolute
        val canonical = JobLinks.canonical(resolved) ?: return null
        val uri = URI(canonical)
        when (board) {
            "gupy" -> {
                if (!uri.host.endsWith(".gupy.io") ||
                    !uri.path.matches(Regex("/jobs/[1-9][0-9]*/?")) && !isGupyTokenJob(uri)) return null
                canonical
            }
            "linkedin" -> {
                if (uri.host != "linkedin.com" && !uri.host.endsWith(".linkedin.com")) return null
                val id = Regex("^/jobs/view/(?:[^/]*-)?([0-9]+)/?$").matchEntire(uri.path)?.groupValues?.get(1) ?: return null
                "https://www.linkedin.com/jobs/view/$id/"
            }
            else -> null
        }
    }.getOrNull()
}

package com.jobscout.domain

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object JobLinks {
    private val trackers = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gh_src", "lever-source", "lever-origin", "fbclid", "gclid")
    private val adHosts = setOf("googleadservices.com", "doubleclick.net", "googlesyndication.com", "adservice.google.com")
    fun canonical(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        if (host == "localhost" || !host.contains('.') || host.contains(':') || host.matches(Regex("[0-9.]+"))) return null
        if (adHosts.any { host == it || host.endsWith(".$it") } || uri.path in listOf("/aclk", "/pagead/aclk")) return null
        if ((host == "whatjobs.com" || host.endsWith(".whatjobs.com")) && uri.path.startsWith("/pub_api__cpl__")) return null
        val query = uri.rawQuery?.split('&')?.filter { entry ->
            val key = entry.substringBefore('=').lowercase()
            key !in trackers && !key.startsWith("utm_")
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        // Keep raw encoding and identity parameters, e.g. Stripe's gh_jid.
        val fragment = uri.rawFragment?.takeUnless { it.lowercase() in setOf("apply", "top", "application") }?.let { "#$it" }.orEmpty()
        URI("https://$host${uri.rawPath.ifEmpty { "/" }}${query?.let { "?$it" }.orEmpty()}$fragment").normalize().toASCIIString()
    }.getOrNull()

    fun fromSharedText(text: String): String? = runCatching {
        val raw = Regex("https://[^\\s<>\"]+").find(text.take(10_000))?.value?.trimEnd('.', ',', ')', ']') ?: return null
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        val resolved = if ((host == "google.com" || host == "google.com.br" || host.endsWith(".google.com") || host.endsWith(".google.com.br")) && uri.path == "/url") {
            uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
                if (part.substringBefore('=') in setOf("q", "url")) URLDecoder.decode(part.substringAfter('='), "UTF-8").takeIf { it.startsWith("https://") } else null
            } ?: return null
        } else raw
        canonical(resolved)
    }.getOrNull()

    fun stableId(source: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$source:$id".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    fun deduplicate(jobs: List<Job>): List<Job> {
        val ids = hashSetOf<String>()
        val urls = hashSetOf<String>()
        return jobs.filter { job ->
            val url = canonical(job.url) ?: return@filter false
            val key = "${job.source}:${job.sourceId}"
            if (key in ids || url in urls) false else { ids += key; urls += url; true }
        }
    }
}

package com.jobscout.domain

import java.net.URI
import java.net.IDN

object GoogleSites {
    private val blocked = setOf("localhost", "local", "internal", "lan", "test", "invalid", "onion")
    fun normalize(input: String): String? = runCatching {
        val text = input.trim()
        if (text.isBlank() || text.length > 500 || text.any(Char::isWhitespace)) return null
        val uri = URI(if ("://" in text) text else "https://$text")
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        val host = IDN.toASCII(uri.host ?: return null).lowercase().removeSuffix(".").removePrefix("www.")
        val labels = host.split('.')
        if (host.length > 253 || labels.size < 2 || labels.last() in blocked || labels.last().all(Char::isDigit) ||
            labels.any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } ||
            host == "google.com" || host.endsWith(".google.com") || host == "google.com.br" || host.endsWith(".google.com.br")) return null
        host
    }.getOrNull()

    fun additional(inputs: List<String>): List<String> = inputs.mapNotNull(::normalize)
        .filterNot { it == "linkedin.com" || it == "gupy.io" }.distinct()
    fun hostMatches(url: String, domain: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host.lowercase().let { it == domain || it.endsWith(".$domain") }
    }.getOrDefault(false)
}

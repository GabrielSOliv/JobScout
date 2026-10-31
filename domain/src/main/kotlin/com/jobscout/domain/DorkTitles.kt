package com.jobscout.domain

/** Search intent only. Aliases never add skills or experience to a candidate profile. */
object DorkTitles {
    private data class Key(val role: String, val dictionary: Map<String, List<String>>)
    private val cache = java.util.concurrent.ConcurrentHashMap<Key, List<List<String>>>()
    private val stop = setOf("de", "da", "do", "das", "dos", "em", "a", "o", "e", "and", "of", "the", "or")
    private val families = listOf(
        listOf("desenvolvedor", "developer", "desenvolvedora", "dev", "engenheiro de software", "software engineer"),
        listOf("analista", "analyst"), listOf("engenheiro", "engineer", "engenheira"),
        listOf("cientista", "scientist"), listOf("dados", "data"),
        listOf("assistente", "assistant"), listOf("administrativo", "administrative", "administrativa"),
        listOf("financeiro", "financial", "financeira"), listOf("contador", "accountant", "contadora"),
        listOf("BI Business Intelligence", "BI", "Business Intelligence", "inteligência de negócios"),
        listOf("otimização", "optimization", "optimisation", "otimizacao"),
        listOf("junior", "júnior", "jr", "entry level"), listOf("senior", "sênior", "sr"),
        listOf("pleno", "mid level", "mid"), listOf("estágio", "estagio", "intern", "internship"),
        listOf("automação", "automation", "automacao"), listOf("integrações", "integrations", "integration", "integracoes"),
        listOf("backend", "back-end", "back end"), listOf("frontend", "front-end", "front end"),
        listOf("fullstack", "full-stack", "full stack"), listOf(".NET", "dotnet", "ASP.NET"),
    )

    fun literal(value: String): String = value.replace(Regex("(?i)\\((a|o|as|os)\\)"), "")
        .replace(Regex("(?i)([\\p{L}]+)/[ao]\\b"), "$1")
        .replace(Regex("[\\p{Cntrl}\\\"“”‘’():|<>*]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun words(value: String) = literal(value).split(' ').filter { it.any(Char::isLetterOrDigit) && normalized(it) !in stop }

    fun concepts(role: String, dictionary: Map<String, List<String>> = Synonyms.defaults): List<List<String>> {
        if (cache.size > 256) cache.clear()
        return cache.getOrPut(Key(literal(role), dictionary)) { buildConcepts(role, dictionary) }
    }

    private fun buildConcepts(role: String, dictionary: Map<String, List<String>>): List<List<String>> {
        val allFamilies = dictionary.entries.map { (key, aliases) -> listOf(key) + aliases } + families
        val input = words(role)
        val result = mutableListOf<List<String>>()
        var index = 0
        while (index < input.size) {
            val match = allFamilies.flatMap { family -> family.map { it to family } }.mapNotNull { (alias, family) ->
                val parts = words(alias)
                if (parts.isEmpty() || index + parts.size > input.size || parts.indices.any { normalized(parts[it]) != normalized(input[index + it]) }) null
                else Triple(parts.size, alias, family)
            }.maxByOrNull { it.first }
            if (match == null) { result += listOf(input[index]); index++ }
            else {
                val aliases = match.third.map { words(it).map(::normalized) }.toSet()
                val connected = allFamilies.filter { family -> family.any { words(it).map(::normalized) in aliases } }.flatten()
                result += (listOf(match.second) + connected)
                    .map(::literal).filter(String::isNotBlank).distinctBy(::normalized)
                index += match.first
            }
        }
        return result.distinctBy { it.map(::normalized).toSet() }
    }

    fun matches(title: String, role: String, dictionary: Map<String, List<String>> = Synonyms.defaults): Boolean {
        val concepts = concepts(role, dictionary)
        val matcher = TermMatcher(title)
        return concepts.isNotEmpty() && concepts.all { group -> group.any(matcher::contains) }
    }
}

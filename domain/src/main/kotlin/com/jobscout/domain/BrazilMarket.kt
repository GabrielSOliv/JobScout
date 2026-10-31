package com.jobscout.domain

/** Country evidence comes from published location, never employer nationality, language or currency. */
object BrazilMarket {
    private fun cityKey(value: String) = normalized(value).replace(Regex("[^a-z0-9']+"), " ").trim()
    private val brazilNames = listOf("brasil", "brazil", "br")
    private val foreignNames = listOf("united states", "estados unidos", "usa", "us", "canada", "portugal", "mexico", "colombia", "argentina", "chile",
        "uruguay", "uruguai", "peru", "india", "germany", "alemanha", "france", "franca", "spain", "espanha", "united kingdom", "uk", "australia",
        "new zealand", "ireland", "irlanda", "south africa", "africa do sul", "california", "texas", "new york")
    private val distinctiveCities = listOf("São Paulo", "Rio de Janeiro", "Belo Horizonte", "Porto Alegre", "Florianópolis", "Curitiba", "Brasília",
        "Campinas", "Fortaleza", "Manaus", "Recife", "Goiânia", "Cuiabá", "João Pessoa", "Aracaju", "Osasco", "São José dos Campos",
        "Ribeirão Preto", "São Bernardo do Campo", "Uberlândia", "Sorocaba", "Maringá", "Londrina", "Joinville", "Niterói")
    private val municipalities by lazy {
        requireNotNull(BrazilMarket::class.java.getResourceAsStream("/brazil-municipalities.tsv")) { "Lista de municípios IBGE não empacotada." }
            .bufferedReader(Charsets.UTF_8).useLines { lines -> lines.map { it.split('\t') }.filter { it.size == 2 }
                .groupBy({ it[1].lowercase() }, { cityKey(it[0]) to it[0] }).mapValues { (_, cities) -> cities.toMap() } }
    }
    private val uf = Regex("\\b(ac|al|am|ap|ba|ce|df|es|go|ma|mg|ms|mt|pa|pb|pe|pi|pr|rj|rn|ro|rr|rs|sc|se|sp|to)\\b")
    private val countryCodes = java.util.Locale.getISOCountries().map { it.lowercase() }.toSet() - "br"
    private val finalCode = Regex("(?:^|[,/·;\\s-])([a-z]{2})\\s*$")
    private fun foreignSuffix(location: String) = finalCode.find(normalized(location))?.groupValues?.get(1) in countryCodes
    fun isBrazilTerm(value: String): Boolean = normalized(value) in brazilNames
    private fun foreign(text: String, freeText: Boolean = false) = foreignNames
        .filterNot { freeText && it in setOf("us", "usa", "uk") }.any { containsTerm(text, it) } ||
        freeText && Regex("\\b(?:US|USA|UK)\\s+(?:only|residents|citizens)\\b").containsMatchIn(text)
    private val exclusions = Regex("\\b(?:except|excluding|exceto|excluindo|menos)\\b[^;.!]{0,60}\\b(?:brasil|brazil|br)\\b|\\b(?:outside|fora do|fora de|not in)\\s+(?:brasil|brazil|br)\\b|\\b(?:brasil|brazil|br)\\s+(?:excluded|not eligible|nao incluido|nao elegivel)\\b")
    /** Keep published ineligibility when a preview is condensed to a location label. */
    fun exclusionHint(text: String): String? = exclusions.find(normalized(text))?.value
    private fun excludesBrazil(text: String) = exclusionHint(text) != null
    private fun withoutCity(text: String, place: String) = normalized(text).replaceFirst(normalized(place.substringBefore(" / ")), " ")
    private fun municipalityAndState(text: String): String? {
        val plain = normalized(text.take(3_000))
        for (state in uf.findAll(plain)) {
            val words = plain.substring(0, state.range.first).trimEnd(' ', '-', '/', ',', '·', ':')
                .split(Regex("[^a-z0-9']+")).filter(String::isNotBlank).takeLast(10)
            val cities = municipalities[state.value].orEmpty()
            for (start in words.indices) {
                val name = cities[words.drop(start).joinToString(" ")] ?: continue
                return "$name / ${state.value.uppercase()}"
            }
        }
        return null
    }
    fun matches(location: String): Boolean {
        if (location.isBlank()) return false
        if (excludesBrazil(location)) return false
        if (brazilNames.any { containsTerm(location, it) }) return true
        val place = municipalityAndState(location)
        if (place != null && !foreign(withoutCity(location, place))) return true
        if (foreign(location)) return false
        if (foreignSuffix(location)) return false
        return distinctiveCities.any { containsTerm(location, it) }
    }
    /** Distinguishes missing location evidence from an explicitly foreign/ineligible location. */
    fun hasForeignEvidence(location: String): Boolean = !matches(location) &&
        (excludesBrazil(location) || foreign(location) || foreignSuffix(location))
    /** A small indexed preview may expose a city/UF even when the query did not name it. */
    fun locationHint(text: String): String? {
        val place = municipalityAndState(text)
        if (excludesBrazil(text) || foreign(place?.let { withoutCity(text, it) } ?: text, freeText = true)) return null
        place?.let { return it }
        if (foreignSuffix(text)) return null
        return distinctiveCities.firstOrNull { containsTerm(text, it) }
            ?: "Brasil".takeIf { brazilNames.any { containsTerm(text, it) } }
    }
}

package com.jobscout.domain

/** Required content terms, shared by discovery and local filtering. No profile inference. */
object RequiredKeywords {
    // Built-ins also apply to older saved synonym dictionaries; no preference reset is needed.
    private val vocabulary = linkedMapOf(
        "inglês" to listOf("English"),
        "espanhol" to listOf("Spanish"),
        "português" to listOf("Portuguese"),
        "francês" to listOf("French"),
        "alemão" to listOf("German"),
        "Power BI" to listOf("PowerBI", "Power-BI"),
    )

    fun aliases(term: String, dictionary: Map<String, List<String>> = Synonyms.defaults): List<String> {
        val literal = DorkTitles.literal(term)
        if (literal.isBlank()) return emptyList()
        return (Synonyms.expand(literal, dictionary) + Synonyms.expand(literal, vocabulary))
            .map(DorkTitles::literal).filter(String::isNotBlank).distinctBy(::normalized)
    }

    fun missing(matcher: TermMatcher, terms: List<String>, dictionary: Map<String, List<String>> = Synonyms.defaults): List<String> =
        terms.filter { term -> aliases(term, dictionary).none(matcher::contains) }

    fun missing(job: Job, terms: List<String>, dictionary: Map<String, List<String>> = Synonyms.defaults): List<String> =
        if (terms.isEmpty()) emptyList() else missing(TermMatcher("${job.title} ${job.description} ${job.skills.joinToString(" ")}"), terms, dictionary)
}

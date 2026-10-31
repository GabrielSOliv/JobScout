package com.jobscout.domain

import java.net.URLEncoder

object SourceCatalog {
    val defaults = listOf(
        SourceConfig("dorks", "linkedin", "DuckDuckGo · LinkedIn (legado)", enabled = false),
        SourceConfig("dorks", "gupy", "DuckDuckGo · Gupy (legado)", enabled = false),
        SourceConfig("web", "vagas", "Vagas.com.br · páginas web", enabled = false, endpoint = "https://www.vagas.com.br/vagas-de-"),
        SourceConfig("web", "empregos", "Empregos.com.br · páginas web", enabled = false, endpoint = "https://www.empregos.com.br/vagas"),
        SourceConfig("web", "python", "Python.org · páginas web", endpoint = "https://www.python.org/jobs/"),
        SourceConfig("freehire", "global", "Freehire · busca global", enabled = false),
        SourceConfig("rss", "wwr", "We Work Remotely", endpoint = "https://weworkremotely.com/remote-jobs.rss"),
        SourceConfig("rss", "python", "Python.org Jobs", endpoint = "https://www.python.org/jobs/feed/rss/"),
        SourceConfig("greenhouse", "quintoandar", "QuintoAndar", enabled = false),
        SourceConfig("lever", "ciandt", "CI&T", enabled = false),
    )
    val kinds = setOf("dorks", "web", "freehire", "rss", "greenhouse", "lever")
    fun upgrade(previous: AppPreferences): AppPreferences = previous.copy(
        sources = (previous.sources + defaults.filter { source -> previous.sources.none { it.key == source.key } })
            .distinctBy { it.key }.map { if (it.kind == "dorks") it.copy(enabled = false) else it },
        discoveryVersion = 6,
        autoScanOnOpen = if (previous.discoveryVersion < 3) true else previous.autoScanOnOpen,
    )
}

data class DiscoveryQuery(val label: String, val query: String) {
    val googleUrl: String get() = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
}
object DiscoveryPlanner {
    /** Discovery terms may use confirmed skills, without inventing experience or new career targets. */
    fun webTerms(query: SearchQuery, profile: CandidateProfile): List<String> {
        val roles = query.titles.ifEmpty { profile.targets }.map(String::trim).filter(String::isNotBlank).distinct()
        return roles.ifEmpty { query.keywords.map(String::trim).filter(String::isNotBlank).distinct() }
    }
    private val scopes = listOf(
        "Gupy" to "site:gupy.io/jobs/",
        "Greenhouse" to "(site:job-boards.greenhouse.io OR site:boards.greenhouse.io)",
        "Lever e Ashby" to "(site:jobs.lever.co OR site:jobs.ashbyhq.com)",
        "Carreiras e vagas" to "(intitle:vaga OR intitle:job OR intitle:careers) -inurl:aclk -inurl:ads",
    )
    fun dorks(query: SearchQuery, profile: CandidateProfile): List<DiscoveryQuery> {
        val roles = query.titles.ifEmpty { profile.targets }.take(4)
        if (roles.isEmpty()) return emptyList()
        fun quote(value: String) = "\"${value.replace(Regex("[\"\\r\\n]"), " ").take(100)}\""
        val titles = roles.joinToString(" OR ", "(", ")", transform = ::quote)
        val locations = query.locations.take(3).takeIf { it.isNotEmpty() }?.joinToString(" OR ", "(", ")", transform = ::quote).orEmpty()
        val exclusions = query.excludedKeywords.take(5).joinToString(" ") { "-${quote(it)}" }
        return scopes.map { (label, scope) -> DiscoveryQuery(label, "$scope $titles $locations $exclusions".trim()) }
    }
    fun automaticQuery(preferences: AppPreferences): SearchQuery = preferences.query.let { query ->
        if (query.titles.isEmpty() && preferences.profile.targets.isNotEmpty()) query.copy(titles = preferences.profile.targets) else query
    }
}

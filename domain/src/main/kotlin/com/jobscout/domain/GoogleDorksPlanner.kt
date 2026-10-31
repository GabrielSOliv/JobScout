package com.jobscout.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Queries for the user's Google browser session, not an HTTP search API. */
object GoogleDorksPlanner {
    private val scopes = listOf(
        "linkedin" to "site:linkedin.com",
        "gupy" to "site:gupy.io",
    )
    private val searchHosts = setOf("google.com", "www.google.com", "google.com.br", "www.google.com.br")
    private val consentHosts = setOf("consent.google.com", "consent.google.com.br")

    fun plan(query: SearchQuery, profile: CandidateProfile, now: Long = System.currentTimeMillis(),
             dictionary: Map<String, List<String>> = Synonyms.defaults, sites: List<String> = emptyList()): List<GoogleDorkTask> {
        fun clean(value: String) = DorkTitles.literal(value)
        // Optimized writing pattern requested by the user: bare single words; only multi-word terms keep quotes.
        fun word(value: String) = clean(value).let { if (it.contains(' ')) "\"$it\"" else it }
        fun group(parts: List<String>) = parts.filter(String::isNotBlank).distinct()
            .let { if (it.size > 1) it.joinToString(" OR ", "(", ")") else it.firstOrNull().orEmpty() }
        // Roles come only from typed titles. Profile targets/keywords are never borrowed silently.
        val roles = query.titles.map(DorkTitles::literal).filter(String::isNotBlank).distinctBy(::normalized)
        if (roles.isEmpty()) return emptyList()
        if (query.methods.isNotEmpty() && DiscoveryMethod.DORKS !in query.methods) return emptyList()
        val acceptsRemote = query.workplaces.isEmpty() || Workplace.REMOTE in query.workplaces
        val targetPlaces = Geography.targets(query.locations)
        val places = targetPlaces.map(::clean).filter(String::isNotBlank).distinctBy(::normalized)
        val remoteTerms = listOf("remoto", "remote", "home office").map(::word)
        val modeTerms = if (query.workplaces.isEmpty()) emptyList() else query.workplaces.flatMap { when (it) {
            Workplace.REMOTE -> listOf("remoto", "remote", "home office")
            Workplace.HYBRID -> listOf("híbrido", "hybrid")
            Workplace.ONSITE -> listOf("presencial", "onsite")
            Workplace.UNKNOWN -> emptyList()
        } }.map(::word)
        val placeTerms = places.flatMap { if (BrazilMarket.isBrazilTerm(it)) listOf("Brasil", "Brazil") else listOf(it) }.map(::word)
        val countryTerms = query.locations.filterNot { it in targetPlaces }.map(::clean).filter(String::isNotBlank)
            .flatMap { if (BrazilMarket.isBrazilTerm(it)) listOf("Brasil", "Brazil") else listOf(it) }.map(::word)
        // One flat location group: typed places, explicit countries, remote exception and selected modes.
        val location = group(placeTerms + countryTerms +
            (if (placeTerms.isNotEmpty() && query.remoteAnywhere && acceptsRemote && places.any { !BrazilMarket.isBrazilTerm(it) }) remoteTerms else emptyList()) + modeTerms)
        // Country context stays only when no typed place carries it, mirroring the optimized template.
        val market = if (query.worldwide || location.isNotBlank()) "" else group(listOf("Brasil", "Brazil").map(::word))
        val required = query.keywords.map(DorkTitles::literal).filter(String::isNotBlank).distinctBy(::normalized).map { term ->
            group(RequiredKeywords.aliases(term, dictionary).map(::word))
        }
        val excluded = (query.excludedKeywords + query.excludedCompanies).map(::clean).filter(String::isNotBlank)
            .distinctBy(::normalized).map { "-${word(it)}" }
        val company = query.company.takeIf(String::isNotBlank)?.let(::word).orEmpty()
        val employment = query.employment.takeIf(String::isNotBlank)?.let { group(EmploymentFilter.aliases(it).map(::word)) }.orEmpty()
        // Discovery freshness and acceptance of unknown publication dates are independent choices.
        val date = query.postedWithinDays?.takeIf { it in 1..3650 && query.dateBasis == DateBasis.PUBLICATION }?.let { days ->
            val after = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().minusDays(days.toLong())
            "after:" + after.format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
        }.orEmpty()
        val levels = group(query.seniorities.flatMap { when (it) {
            Seniority.INTERN -> listOf("estágio", "internship", "intern")
            Seniority.JUNIOR -> listOf("junior", "júnior", "jr")
            Seniority.MID -> listOf("pleno")
            Seniority.SENIOR -> listOf("senior", "sênior", "sr")
            Seniority.UNKNOWN -> emptyList()
        } }.map(::word))
        val talent = if (query.excludeTalentPools) listOf("banco de talentos", "cadastro reserva", "talent pool").map { "-intitle:${word(it)}" } else emptyList()
        val extra = GoogleSites.additional(sites)
        val targets = scopes + extra.map { "site:$it" to "site:$it" }
        // Flat title group: every concept of every role in the batch becomes an OR term of the same group.
        fun titleClause(batch: List<String>, strategy: DorkStrategy): String {
            val distinctRoles = batch.distinctBy { role -> DorkTitles.concepts(role, dictionary)
                .map { group -> group.map(::normalized).sorted().joinToString("|") }.sorted().joinToString("&") }
            val terms = distinctRoles.flatMap { DorkTitles.concepts(it, dictionary) }.flatten()
                .map(::clean).filter(String::isNotBlank).distinct()
            return group(terms.map { term ->
                if (strategy == DorkStrategy.TITLE) (if (term.contains(' ')) "intitle:\"$term\"" else "intitle:$term") else word(term)
            })
        }
        // Pack complete roles, never truncate the role list or cut a Boolean expression.
        val batches = mutableListOf<List<String>>()
        roles.forEach { role ->
            val last = batches.lastOrNull().orEmpty()
            if (last.isNotEmpty() && last.size < 3 && titleClause(last + role, DorkStrategy.TITLE).length <= 600)
                batches[batches.lastIndex] = last + role
            else batches += listOf(role)
        }
        fun task(board: String, scope: String, batch: List<String>, strategy: DorkStrategy, parent: String? = null): GoogleDorkTask {
                // Never truncate values, lists or expressions. Provider refusals stay visible.
                val clauses = listOf(scope, titleClause(batch, strategy), location, levels) + required +
                    listOf(company, employment) + excluded + talent + listOf(market, date)
                val dork = clauses.filter(String::isNotBlank).distinct().joinToString(" ")
                val locale = if (query.worldwide) "hl=pt-BR" else "hl=pt-BR&gl=br"
                return GoogleDorkTask(JobLinks.stableId("google", dork), board, dork,
                    "https://www.google.com/search?q=${URLEncoder.encode(dork, "UTF-8")}&as_sitesearch=${URLEncoder.encode(scope.removePrefix("site:"), "UTF-8")}&$locale",
                    batch.singleOrNull(), strategy, parent, batch)
        }
        val main = batches.flatMap { batch -> targets.map { (board, scope) -> task(board, scope, batch, DorkStrategy.TITLE) } }
        val wider = main.map { primary -> task(primary.board, targets.first { it.first == primary.board }.second,
            primary.searchRoles, DorkStrategy.EXPANDED, primary.id) }
        return (main + wider).distinctBy { it.query }
    }

    fun siteHost(board: String): String? = when (board) {
        "linkedin" -> "linkedin.com"
        "gupy" -> "gupy.io"
        else -> board.takeIf { it.startsWith("site:") }?.removePrefix("site:")?.let(GoogleSites::normalize)
    }

    /** Domain filter from Google's advanced-search form, independent of Boolean text in q. */
    fun scopedUrl(url: String, board: String): String {
        val host = siteHost(board) ?: return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (!allowedNavigation(url) || uri.path != "/search") return url
        val params = uri.rawQuery.orEmpty().split('&').filterNot { it.substringBefore('=') in setOf("as_sitesearch", "as_dt") }
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?" + (params + "as_sitesearch=${URLEncoder.encode(host, "UTF-8")}").joinToString("&")
    }

    /** Only main-frame navigation for the Google search/consent/challenge session. */
    fun allowedNavigation(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        if (host in consentHosts) return path == "/" || path == "/m" || path.startsWith("/ml") || path.startsWith("/save")
        if (host !in searchHosts) return false
        when {
            path == "/search" -> queryText(url)?.isNotBlank() == true
            path == "/" || path == "/webhp" -> true
            path == "/sorry" || path.startsWith("/sorry/") -> true
            path.startsWith("/recaptcha/") -> true
            path == "/setprefs" || path == "/preferences" -> true
            path == "/goto" -> opaqueRedirect(url)
            else -> false
        }
    }.getOrDefault(false)

    /** Observed Google redirect format. Never invents/decrypts the opaque destination. */
    fun opaqueRedirect(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in searchHosts && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.path == "/goto" && uri.rawQuery?.split('&')?.singleOrNull { it.substringBefore('=') == "url" }
                ?.substringAfter('=')?.matches(Regex("[A-Za-z0-9_%=-]{12,2048}")) == true
    }.getOrDefault(false)

    fun queryText(url: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "q" }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    fun sameQuery(url: String, query: String): Boolean {
        fun compact(value: String) = value.replace(Regex("\\s+"), " ").trim()
        return queryText(url)?.let(::compact) == compact(query)
    }
}

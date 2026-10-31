package com.jobscout.feature.search.data

import com.jobscout.domain.*
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Reads the current DOM supplied by the browser. It never requests Google or opens destination links. */
object GoogleResultsParser {
    // Standard rendered SERPs and the alternative title span observed in googlesearch's parser.
    private const val headings = "h3,[role=heading][aria-level=3],.CVA68e"
    fun parse(html: String, url: String, task: GoogleDorkTask, query: SearchQuery,
              fetchedAt: Long = System.currentTimeMillis(), dictionary: Map<String, List<String>> = Synonyms.defaults): GooglePage {
        if (html.length > 2_100_000) return GooglePage(GooglePageKind.UNKNOWN, message = "Página maior que o limite de leitura.")
        if (!GoogleDorksPlanner.allowedNavigation(url)) return GooglePage(GooglePageKind.UNKNOWN, message = "A página atual não pertence à sessão de busca Google permitida.")
        val uri = URI(url)
        val doc = Jsoup.parse(html, url)
        val text = doc.body()?.text().orEmpty()
        val plain = normalized(text.take(50_000))
        val challenge = uri.path.orEmpty().let { it == "/sorry" || it.startsWith("/sorry/") || it.startsWith("/recaptcha/") } ||
            doc.select("#captcha-form,form[action*=/sorry],input[name=captcha]").isNotEmpty() ||
            listOf("unusual traffic from your computer network", "trafego incomum na sua rede", "nossos sistemas detectaram trafego incomum").any(plain::contains)
        if (challenge) return GooglePage(GooglePageKind.CAPTCHA, message = "O Google pediu verificação. Abra a pendência, resolva e confirme para continuar.")
        if (task.board == "careers") return GooglePage(GooglePageKind.UNKNOWN, message = "Consulta aberta de uma versão anterior. Pule esta consulta ou inicie uma nova busca restrita aos sites configurados.")
        if (uri.host.orEmpty().startsWith("consent.google.") ||
            plain.contains("before you continue to google") || plain.contains("antes de continuar para o google"))
            return GooglePage(GooglePageKind.CONSENT, message = "O Google pediu uma escolha de consentimento. Confira a página e confirme para continuar.")
        if (uri.path != "/search" || !GoogleDorksPlanner.sameQuery(url, task.query))
            return GooglePage(GooglePageKind.UNKNOWN, message = "A consulta no navegador mudou. Volte à consulta planejada ou inicie outra busca no app.")
        if (uri.rawQuery?.split('&')?.any { it == "udm=50" } == true)
            return GooglePage(GooglePageKind.UNKNOWN, message = "O Google está no modo IA. Volte à pesquisa normal para conferir os links da consulta planejada.")

        val expectedHost = GoogleDorksPlanner.siteHost(task.board)
        val organic = doc.select("a[href]").filter { it.selectFirst(headings) != null && !isAd(it, resultContainer(it)) }
            .mapNotNull { JobLinks.fromSharedText(it.absUrl("href")) }
            .filterNot { runCatching { URI(it).host.orEmpty().let { host -> host == "google.com" || host.endsWith(".google.com") || host == "google.com.br" || host.endsWith(".google.com.br") } }.getOrDefault(true) }
        if (expectedHost != null && organic.isNotEmpty() && organic.none { GoogleSites.hostMatches(it, expectedHost) })
            return GooglePage(GooglePageKind.UNKNOWN, message = "O Google exibiu resultados orgânicos fora de $expectedHost. Isso não confirma busca vazia. Use Restaurar consulta para reaplicar o filtro de domínio ou pule esta consulta.")

        val jobs = linkedMapOf<String, Job>()
        val pending = linkedMapOf<String, GoogleLinkPreview>()
        doc.select("a[href]").filter { it.selectFirst(headings) != null }.forEach { anchor ->
            val container = resultContainer(anchor)
            if (isAd(anchor, container)) return@forEach
            val raw = anchor.absUrl("href")
            val link = original(raw, task.board)
            if (link == null && !GoogleDorksPlanner.opaqueRedirect(raw)) return@forEach
            val heading = anchor.selectFirst(headings) ?: return@forEach
            val title = heading.text().removePrefix("Página da Vaga | ").removeSuffix(" | LinkedIn")
                .removeSuffix(" - LinkedIn").removeSuffix(" | Gupy").trim().take(240)
            if (title.isBlank()) return@forEach
            val snippet = container.selectFirst(".VwiC3b,.IsZvec,.aCOpRe,[data-sncf]")?.text()
                ?: container.clone().also { it.select("a,h3,[role=heading],script,style,button,nav").remove() }.text()
            val preview = snippet.trim().take(2_500)
            if (!relevant(title, preview, task, query, dictionary)) return@forEach
            val candidate = GoogleLinkPreview(raw, title, preview, task.board, task.query, fetchedAt)
            if (link == null) {
                val visible = normalized(anchor.text())
                if (expectedHost != null && visible.contains(expectedHost)) pending.putIfAbsent(raw, candidate)
                return@forEach
            }
            jobs.putIfAbsent(link, makeJob(candidate, link, query))
        }
        if (jobs.isNotEmpty() || pending.isNotEmpty()) return GooglePage(GooglePageKind.RESULTS, jobs.values.take(100), nextUrl(doc, url, task.query),
            pendingLinks = pending.values.take(20))
        val hasHeadings = doc.select("a[href]").any { it.selectFirst(headings) != null }
        val empty = !hasHeadings && listOf("did not match any documents", "no results found", "nenhum resultado encontrado", "nao encontrou nenhum documento",
            "nao encontrou resultados", "nao foram encontrados resultados").any(plain::contains)
        if (empty) return GooglePage(GooglePageKind.EMPTY, message = "O Google informou que não há resultados para esta consulta.")
        // A recognized SERP can contain organic results outside our individual-job targets.
        if (doc.select("#search,#rso").isNotEmpty() && hasHeadings)
            return GooglePage(GooglePageKind.RESULTS, nextUrl = nextUrl(doc, url, task.query), message = "A página foi lida, sem links individuais de vaga aceitos nesta página.")
        return GooglePage(GooglePageKind.UNKNOWN, message = "Não reconhecemos resultados nesta página. Confira o navegador antes de continuar.")
    }

    private fun relevant(title: String, snippet: String, task: GoogleDorkTask, query: SearchQuery,
                         dictionary: Map<String, List<String>>): Boolean {
        val roles = task.searchRoles.ifEmpty { query.titles }
        if (roles.isNotEmpty() && roles.none { DorkTitles.matches(title, it, dictionary) }) return false
        if (query.excludeTalentPools && listOf("banco de talentos", "banco talentos", "cadastro reserva", "talent pool", "talent community")
                .any { containsTerm(title, it) }) return false
        val text = "$title $snippet"
        if (query.excludedKeywords.any { containsTerm(text, it) }) return false
        val level = inferSeniority(title)
        if (query.seniorities.isNotEmpty() && level != Seniority.UNKNOWN && level !in query.seniorities) return false
        if (!query.worldwide && BrazilMarket.exclusionHint(text) != null) return false
        val place = BrazilMarket.locationHint(text)
        val remote = listOf("remoto", "remote", "home office").any { containsTerm(text, it) }
        if (place != null && !BrazilMarket.isBrazilTerm(place) && query.locations.isNotEmpty() &&
            !(query.remoteAnywhere && remote) && !Geography.matches(place, query.locations, dictionary)) return false
        return true
    }

    fun resolve(preview: GoogleLinkPreview, target: String, query: SearchQuery): Job? =
        original(target, preview.board)?.let { makeJob(preview, it, query) }

    private fun makeJob(preview: GoogleLinkPreview, link: String, query: SearchQuery): Job {
        val text = "${preview.title} ${preview.snippet}"
        val hint = BrazilMarket.locationHint(text)
        val mentioned = if (query.worldwide && hint == null) Geography.targets(query.locations)
            .filter { !BrazilMarket.isBrazilTerm(it) && containsTerm(text, it) } else emptyList()
        val location = BrazilMarket.exclusionHint(text) ?: hint ?: mentioned.joinToString(" · ")
        val workplace = when {
            listOf("hybrid", "hibrido").any { containsTerm(text, it) } -> Workplace.HYBRID
            listOf("remote", "remoto", "home office").any { containsTerm(text, it) } -> Workplace.REMOTE
            listOf("on-site", "onsite", "presencial").any { containsTerm(text, it) } -> Workplace.ONSITE
            else -> Workplace.UNKNOWN
        }
        val source = "google:${preview.board}"
        return Job(JobLinks.stableId(source, link), source, link, preview.title, "Empresa não informada", link,
            location = location, workplace = workplace, seniority = inferSeniority(preview.title), description = preview.snippet,
            collectedAt = preview.fetchedAt, firstSeen = preview.fetchedAt, lastSeen = preview.fetchedAt,
            discoveryMethod = "Dorks · Google", descriptionComplete = false,
            discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, source, "Google", preview.fetchedAt, preview.fetchedAt, preview.query)),
            verification = JobVerification(VerificationState.UNVERIFIED, reason = "Link e resumo do Google; publicação original ainda não lida."))
    }

    private fun resultContainer(anchor: Element): Element {
        val parents = anchor.parents().takeWhile { it.tagName() !in setOf("body", "html") }
        return parents.firstOrNull { (it.hasClass("g") || it.hasClass("MjjYud") || it.hasAttr("data-hveid")) &&
            it.select(headings).size == 1 }
            ?: parents.take(4).lastOrNull { it.select(headings).size == 1 } ?: anchor
    }

    private fun isAd(anchor: Element, container: Element): Boolean {
        if ((listOf(anchor) + anchor.parents()).any {
                it.id() in setOf("tads", "tadsb") || it.hasClass("uEierd") || it.hasClass("ads-ad") ||
                    it.hasAttr("data-text-ad") || it.hasAttr("data-sponsored") || it.attr("aria-label").lowercase() in setOf("ads", "sponsored", "patrocinado", "anuncio", "anúncio")
            }) return true
        return container.select("span,strong,[role=note]").any { normalized(it.text()) in setOf("sponsored", "patrocinado", "anuncio", "ads") }
    }

    private fun original(raw: String, board: String): String? {
        val resolved = JobLinks.fromSharedText(raw) ?: return null
        return when (board) {
            "linkedin", "gupy" -> DorksLinks.original(resolved, board)
            "careers" -> null
            else -> board.takeIf { it.startsWith("site:") }?.removePrefix("site:")
                ?.takeIf { GoogleSites.normalize(it) == it && GoogleSites.hostMatches(resolved, it) }
                ?.let { DorksLinks.original(resolved, "linkedin") ?: DorksLinks.original(resolved, "gupy") ?: resolved.takeIf(::individualCareerLink) }
        }
    }

    private fun individualCareerLink(url: String): Boolean {
        val uri = URI(url)
        val host = uri.host.lowercase()
        if (host == "linkedin.com" || host.endsWith(".linkedin.com") || host == "gupy.io" || host.endsWith(".gupy.io") ||
            host == "google.com" || host.endsWith(".google.com") || host.endsWith(".google.com.br")) return false
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.isEmpty() || segments.any { it.lowercase() in setOf("blog", "blogs", "articles", "news", "privacy", "login", "search", "categories") }) return false
        val last = segments.last().lowercase()
        if (last in setOf("jobs", "job", "careers", "career", "vagas", "positions", "all-jobs", "engineering", "technology")) return false
        val knownAts = host in setOf("jobs.lever.co", "jobs.ashbyhq.com", "boards.greenhouse.io", "job-boards.greenhouse.io", "jobs.smartrecruiters.com", "apply.workable.com")
        val jobRoute = segments.dropLast(1).any { it.lowercase() in setOf("job", "jobs", "vaga", "vagas", "careers", "career", "positions", "position", "opportunities", "j") }
        val identity = last.any(Char::isDigit) || last.count { it == '-' } >= 2 || last.count { it == '_' } >= 2
        return identity && (knownAts || jobRoute)
    }

    private fun nextUrl(doc: org.jsoup.nodes.Document, current: String, dork: String): String? {
        val currentStart = URI(current).rawQuery?.split('&')?.firstOrNull { it.startsWith("start=") }?.substringAfter('=')?.toIntOrNull() ?: 0
        return doc.select("a[href]").asSequence().filter { link ->
            link.id() == "pnnext" || link.attr("rel").split(' ').contains("next") ||
                normalized(link.attr("aria-label")) in setOf("next", "next page", "proxima", "proxima pagina", "proximo", "mais resultados") ||
                normalized(link.text()) in setOf("next", "proxima", "proximo", "mais resultados")
        }.map { it.absUrl("href") }.firstOrNull { next ->
            if (!GoogleDorksPlanner.allowedNavigation(next) || !GoogleDorksPlanner.sameQuery(next, dork)) return@firstOrNull false
            val uri = URI(next)
            val start = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("start=") }?.substringAfter('=')?.toIntOrNull()
            uri.path == "/search" && next != current && start != null && start > currentStart
        }
    }
}

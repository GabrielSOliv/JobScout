package com.jobscout.feature.search.data

import com.jobscout.domain.*
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class GoogleResultsParserTest {
    @Test fun `foreign organic entertainment results pause rather than becoming a completed empty task`() {
        val parsed = page(result("https://www.instagram.com/junior_oficial/", "JUNIOR Instagram") +
            result("https://www.facebook.com/JuniorLima/", "Junior Lima"))
        assertEquals(GooglePageKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.message.orEmpty().contains("fora de"))
        assertTrue(parsed.jobs.isEmpty())
    }
    @Test fun `required English is confirmed from job content and absent snippet evidence stays an unconfirmed capture`() {
        val query = SearchQuery(titles = listOf("Java"), keywords = listOf("Inglês"))
        val planned = GoogleDorksPlanner.plan(query, CandidateProfile()).first { it.board == "gupy" }
        val withEnglish = page(result("https://fixture.gupy.io/jobs/1", snippet = "Brasil. Requirements include English."), task = planned, query = query).jobs.single()
        assertTrue(JobFilter.matches(withEnglish, query))
        val preview = page(result("https://fixture.gupy.io/jobs/2", snippet = "Brasil. Java development."), task = planned, query = query).jobs.single()
        assertFalse(JobFilter.matches(preview, query))
        assertEquals(listOf("Inglês"), RequiredKeywords.missing(preview, query.keywords))
    }
    @Test fun `grouped query accepts each role and rejects titles from a different group`() {
        val query = SearchQuery(titles = listOf("Java Developer", "Analista Financeiro", "Accountant", "Otimização"))
        val batch = GoogleDorksPlanner.plan(query, CandidateProfile()).first { it.board == "gupy" }
        val html = result("https://fixture.gupy.io/jobs/1", "Desenvolvedor Java") +
            result("https://fixture.gupy.io/jobs/2", "Analista Financeiro") +
            result("https://fixture.gupy.io/jobs/3", "Accountant") +
            result("https://fixture.gupy.io/jobs/4", "Otimização") +
            result("https://fixture.gupy.io/jobs/5", "Gerente Comercial")
        assertEquals(setOf("https://fixture.gupy.io/jobs/1", "https://fixture.gupy.io/jobs/2", "https://fixture.gupy.io/jobs/3"),
            page(html, task = batch, query = query).jobs.map { it.url }.toSet())
    }
    @Test fun `alternative title spans are accepted only on validated individual links`() {
        val html = "<div class='g'><a href='https://fixture.gupy.io/jobs/555'><span class='CVA68e'>Java Developer</span></a><span class='FrIlee'>Brasil</span></div>" +
            "<a href='https://fixture.gupy.io/jobs/'><span class='CVA68e'>Java Developer</span></a>"
        assertEquals(listOf("https://fixture.gupy.io/jobs/555"), page(html).jobs.map { it.url })
    }
    @Test fun `AI mode or changed query must return to the planned normal search before capture`() {
        assertEquals(GooglePageKind.UNKNOWN, page(result("https://fixture.gupy.io/jobs/1"), current = url + "&udm=50").kind)
        assertEquals(GooglePageKind.UNKNOWN, page(result("https://fixture.gupy.io/jobs/1"), current = searchUrl("Different")).kind)
    }
    @Test fun `empty phrase in an unrelated result does not turn a populated SERP into a Google empty page`() {
        assertEquals(GooglePageKind.RESULTS, page(result("https://fixture.gupy.io/help", "Help", "Nenhum resultado encontrado no programa Java")).kind)
    }
    @Test fun `custom site captures only relevant individual links inside the registered host`() {
        val query = SearchQuery(titles = listOf("Java"))
        val custom = GoogleDorksPlanner.plan(query, CandidateProfile(), sites = listOf("example.com")).first { it.board == "site:example.com" }
        fun card(link: String, title: String = "Java Developer") = """<div class="g"><a href="$link"><h3>$title</h3></a><div class="VwiC3b">Brasil. Vaga de desenvolvimento.</div></div>"""
        val html = "<div id='search'>" + card("https://jobs.example.com/jobs/123") + card("https://example.com.evil.com/jobs/123") +
            card("https://example.com/jobs/") + card("https://example.com/articles/java-123") + card("https://example.com/jobs/456", "Assistente administrativo") + "</div>"
        val page = GoogleResultsParser.parse(html, custom.url, custom, query)
        assertEquals(listOf("https://jobs.example.com/jobs/123"), page.jobs.map { it.url })
        val preview = GoogleLinkPreview("https://www.google.com/goto?url=CAESfixtureToken12345", "Java Developer", "Brasil", custom.board, custom.query, 1L)
        assertNotNull(GoogleResultsParser.resolve(preview, "https://example.com/jobs/456", query))
        assertNull(GoogleResultsParser.resolve(preview, "https://example.com.evil.com/jobs/456", query))
    }
    private val dork = "site:gupy.io Java"
    private val url = searchUrl(dork)
    private val task = GoogleDorkTask("fixture", "gupy", dork, url)
    @Test fun `first page can queue all ten organic destinations without truncating to five`() {
        val body = (1..10).joinToString("") { index ->
            result("https://www.google.com/goto?url=CAESfixtureToken$index", title = "Java $index https://fixture.gupy.io/jobs")
        }
        val parsed = page(body)
        assertEquals(10, parsed.pendingLinks.size)
        assertTrue(parsed.jobs.isEmpty())
    }
    @Test fun `opaque organic links are queued but never stored as original jobs and ads stay excluded`() {
        val wrapper = "https://www.google.com/goto?url=CAESfixtureToken12345"
        val body = result(wrapper, title = "Java Developer https://fixture.gupy.io/jobs", attributes = "data-sponsored='1'") +
            result(wrapper, title = "Java Developer https://fixture.gupy.io/jobs")
        val parsed = page(body)
        assertTrue(parsed.jobs.isEmpty())
        assertEquals(1, parsed.pendingLinks.size)
        val preview = parsed.pendingLinks.single()
        assertEquals(wrapper, preview.wrapperUrl)
        val resolved = GoogleResultsParser.resolve(preview, "https://fixture.gupy.io/jobs/555?utm_source=google", SearchQuery())!!
        assertEquals("https://fixture.gupy.io/jobs/555", resolved.url)
        assertEquals(VerificationState.UNVERIFIED, resolved.verification.state)
        assertEquals(dork, resolved.discoveryOrigins.single().query)
        assertNull(GoogleResultsParser.resolve(preview, "https://www.linkedin.com/jobs/view/555", SearchQuery()))
        assertNull(GoogleResultsParser.resolve(preview, "https://www.googleadservices.com/pagead/aclk?id=555", SearchQuery()))
    }
    private fun searchUrl(query: String, start: Int? = null) = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}" + (start?.let { "&start=$it" }.orEmpty())
    private fun result(link: String, title: String = "Página da Vaga | Java Developer", snippet: String = "Local de trabalho: São Paulo - SP. Java e SQL", attributes: String = "") =
        "<div class='g' $attributes><a href='$link'><h3>$title</h3></a><div class='VwiC3b'>$snippet</div></div>"
    private fun page(body: String, task: GoogleDorkTask = this.task, current: String = task.url, query: SearchQuery = SearchQuery()) =
        GoogleResultsParser.parse("<html><body><div id='search'>$body</div></body></html>", current, task, query, 123L)

    @Test fun `organic links unwrap and keep unverified source evidence without inventing dates`() {
        val target = "https://fixture.gupy.io/jobs/123?utm_source=google"
        val wrapped = "https://www.google.com/url?q=${URLEncoder.encode(target, "UTF-8")}&amp;sa=U"
        val parsed = page(result(wrapped, snippet = "Há 3 dias. Local de trabalho: São Paulo - SP. Java e SQL"))
        assertEquals(GooglePageKind.RESULTS, parsed.kind)
        val job = parsed.jobs.single()
        assertEquals("https://fixture.gupy.io/jobs/123", job.url)
        assertEquals("Java Developer", job.title); assertEquals("São Paulo / SP", job.location)
        assertNull(job.postedAt); assertEquals(123L, job.collectedAt); assertFalse(job.descriptionComplete)
        assertEquals(VerificationState.UNVERIFIED, job.verification.state)
        assertEquals("google:gupy", job.source); assertEquals(dork, job.discoveryOrigins.single().query)
        assertEquals(DiscoveryMethod.DORKS, job.discoveryOrigins.single().method)
    }

    @Test fun `paid results and redirect ads are discarded even if a heading resembles a vacancy`() {
        val body = result("https://fixture.gupy.io/jobs/1", attributes = "data-text-ad='1'") +
            "<div id='tads'>${result("https://fixture.gupy.io/jobs/2")}</div>" +
            result("https://www.google.com/aclk?adurl=https://fixture.gupy.io/jobs/3") +
            result("https://fixture.gupy.io/jobs/4", snippet = "<span>Patrocinado</span> Java") +
            result("https://fixture.gupy.io/jobs/5")
        assertEquals(listOf("https://fixture.gupy.io/jobs/5"), page(body).jobs.map { it.url })
    }

    @Test fun `challenge page cannot be mistaken for an empty successful search`() {
        val parsed = page("<form id='captcha-form'><div>Resolva a verificação</div></form>", current = "https://www.google.com/sorry/index?continue=search")
        assertEquals(GooglePageKind.CAPTCHA, parsed.kind); assertTrue(parsed.jobs.isEmpty())
        assertEquals(GooglePageKind.CAPTCHA, page("Nossos sistemas detectaram tráfego incomum na sua rede de computadores.").kind)
    }

    @Test fun `consent requires human choice rather than automatically accepting it`() {
        val parsed = page("Before you continue to Google", current = "https://consent.google.com/m?continue=search")
        assertEquals(GooglePageKind.CONSENT, parsed.kind); assertTrue(parsed.jobs.isEmpty())
    }

    @Test fun `only explicit empty state is accepted as empty and unknown markup stays unknown`() {
        assertEquals(GooglePageKind.EMPTY, page("<div id='topstuff'>Sua pesquisa não encontrou nenhum documento.</div>").kind)
        assertEquals(GooglePageKind.UNKNOWN, page("<h1>Welcome</h1>").kind)
        assertEquals(GooglePageKind.UNKNOWN, page(result("https://fixture.gupy.io/jobs/1"), current = searchUrl("Other")).kind)
    }

    @Test fun `pagination follows a real same query Google link and never invents an offset`() {
        val next = searchUrl(dork, 10)
        val parsed = page(result("https://fixture.gupy.io/jobs/1") + "<a id='pnnext' href='${next.replace("&", "&amp;")}'>Próxima</a>")
        assertEquals(next, parsed.nextUrl)
        assertNull(page(result("https://fixture.gupy.io/jobs/1")).nextUrl)
        assertNull(page(result("https://fixture.gupy.io/jobs/1") + "<a id='pnnext' href='${searchUrl("Other", 10)}'>Next</a>").nextUrl)
        assertNull(page(result("https://fixture.gupy.io/jobs/1") + "<a id='pnnext' href='https://evil.com/search?q=x&amp;start=10'>Next</a>").nextUrl)
    }

    @Test fun `Gupy token links survive original path and identity parameters`() {
        val target = "https://pagseguro.gupy.io/job/eyJqb2JJZCI6MTI2NzA3NTgsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0%3D?jobBoardSource=gupy_portal"
        assertEquals(target, page(result(target)).jobs.single().url)
        assertTrue(page(result("https://portal.gupy.io/job-search/term=Java") + result("https://fixture.gupy.io/")).jobs.isEmpty())
    }

    @Test fun `legacy unrestricted career task cannot import jobs outside configured sites`() {
        val career = task.copy(board = "careers")
        val body = result("https://careers.example.com/jobs/java-developer-senior-123", title = "Java Developer", snippet = "Vaga em São Paulo / SP. Candidate-se.") +
            result("https://careers.example.com/careers", title = "Java jobs") + result("https://careers.example.com/careers/engineering", title = "Java jobs") +
            result("https://example.com/blog/jobs/java-developer-123", title = "Java jobs") +
            result("https://jobs.lever.co/fixture/1234-5678", title = "Java Developer", snippet = "Apply for this job in São Paulo / SP")
        val parsed = page(body, task = career)
        assertEquals(GooglePageKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.jobs.isEmpty())
        assertTrue(parsed.pendingLinks.isEmpty())
        assertNull(GoogleResultsParser.resolve(GoogleLinkPreview(task.url, "Java Developer", "Brasil", "careers", task.query, 1L), "https://jobs.lever.co/fixture/1234-5678", SearchQuery()))
    }

    @Test fun `Brazil restriction remains in indexed location rather than becoming Brazil eligible`() {
        val parsed = page(result("https://fixture.gupy.io/jobs/1", snippet = "Worldwide except Brazil. Java remote."), query = SearchQuery(locations = listOf("Brasil")))
        assertTrue(parsed.jobs.isEmpty())
        val job = page(result("https://fixture.gupy.io/jobs/1", snippet = "Worldwide except Brazil. Java remote."), query = SearchQuery(worldwide = true)).jobs.single()
        assertTrue(job.location.contains("except brazil"))
        assertFalse(JobFilter.matches(job, SearchQuery(remoteAnywhere = true)))
        assertTrue(JobFilter.matches(job, SearchQuery(worldwide = true)))
    }

    @Test fun `unrelated titles are rejected before direct capture or opaque resolution`() {
        val scoped = task.copy(role = "Java Developer")
        val body = result("https://fixture.gupy.io/jobs/1", "Auxiliar Administrativo", "A empresa usa Java em São Paulo") +
            result("https://www.google.com/goto?url=CAESfixtureToken12345", "Gerente Comercial https://fixture.gupy.io", "Java e SQL, São Paulo") +
            result("https://fixture.gupy.io/jobs/2", "JavaScript Developer") +
            result("https://fixture.gupy.io/jobs/3", "Desenvolvedor(a) Backend Java")
        val parsed = page(body, task = scoped)
        assertEquals(listOf("https://fixture.gupy.io/jobs/3"), parsed.jobs.map { it.url })
        assertTrue(parsed.pendingLinks.isEmpty())
    }
    @Test fun `known wrong city seniority exclusions and talent pools do not consume capture budget`() {
        val body = result("https://fixture.gupy.io/jobs/1", "Java Developer Sênior", "Recife / PE") +
            result("https://fixture.gupy.io/jobs/2", "Banco de Talentos Java Developer", "São Paulo / SP") +
            result("https://fixture.gupy.io/jobs/3", "Java Developer", "Recife / PE") +
            result("https://fixture.gupy.io/jobs/4", "Java Developer Júnior", "São Paulo / SP")
        val parsed = page(body, task = task.copy(role = "Java Developer"),
            query = SearchQuery(titles = listOf("Java Developer"), locations = listOf("São Paulo"), seniorities = setOf(Seniority.JUNIOR)))
        assertEquals(listOf("https://fixture.gupy.io/jobs/4"), parsed.jobs.map { it.url })
    }

    @Test fun `city cannot be copied merely because the query requested it`() {
        val job = page(result("https://fixture.gupy.io/jobs/1", snippet = "Conhecimento de Java e SQL"), query = SearchQuery(locations = listOf("Campinas"))).jobs.single()
        assertEquals("", job.location)
    }

    @Test fun `recognized SERP with same-site non-job links is a read page but creates no fake job`() {
        val parsed = page(result("https://fixture.gupy.io/help/Java", title = "Java documentation"))
        assertEquals(GooglePageKind.RESULTS, parsed.kind); assertTrue(parsed.jobs.isEmpty())
    }

    @Test fun `oversized or external browser page is not parsed as Google results`() {
        assertEquals(GooglePageKind.UNKNOWN, page("x".repeat(2_100_001)).kind)
        assertEquals(GooglePageKind.UNKNOWN, page(result("https://fixture.gupy.io/jobs/1"), current = "https://evil.com/search?q=Java").kind)
    }
}

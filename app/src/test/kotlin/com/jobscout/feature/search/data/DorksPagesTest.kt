package com.jobscout.feature.search.data

import com.jobscout.domain.*
import org.junit.Assert.*
import org.junit.Test

class DorksPagesTest {
    private val config = SourceConfig("dorks", "gupy", "Gupy")
    private val dork = "site:gupy.io inurl:job Java \"São Paulo\""
    private fun result(url: String, title: String = "Página da Vaga | Java Developer", snippet: String = "SQL em São Paulo", attributes: String = "") =
        "<div class='result $attributes'><a class='result__a' href='$url'>$title</a><a class='result__snippet'>$snippet</a></div>"
    private fun parse(html: String, query: SearchQuery = SearchQuery(locations = listOf("São Paulo"))) =
        DorksPages.parse("<div class='results'>$html</div>", config, query, dork, 123L)

    @Test fun `only organic individual Gupy links produce incomplete jobs with query evidence`() {
        val jobs = parse(result("https://company.gupy.io/jobs/123") + result("https://company.gupy.io/jobs/123") +
            result("https://company.gupy.io/jobs/456", attributes = "result--ad") + result("https://portal.gupy.io/job-search/term=Java") + result("https://example.com/jobs/1"))
        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals("Java Developer", job.title); assertEquals("São Paulo", job.location)
        assertEquals("Empresa não informada", job.company); assertNull(job.postedAt); assertNull(job.salaryMin)
        assertFalse(job.descriptionComplete); assertEquals(123L, job.collectedAt)
        assertEquals(dork, job.discoveryOrigins.single().query)
        assertEquals(DiscoveryMethod.DORKS, job.discoveryOrigins.single().method)
    }
    @Test fun `location cannot be invented from search criteria`() {
        assertEquals("", parse(result("https://company.gupy.io/jobs/123", snippet = "Conhecimento em Java e SQL")).single().location)
    }
    @Test fun `new Gupy job route is accepted and city state evidence survives a broad Brazil query`() {
        val url = "https://pagseguro.gupy.io/job/eyJqb2JJZCI6MTI2NzA3NTgsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0%3D?jobBoardSource=gupy_portal"
        val jobs = parse(result(url, title = "Página da Vaga | Engenheiro Java", snippet = "Local de trabalho: Pato Branco - PR. Conhecimento em Java."), SearchQuery(locations = listOf("Brasil")))
        assertEquals(url, jobs.single().url)
        assertEquals("Pato Branco / PR", jobs.single().location)
        assertFalse(jobs.single().descriptionComplete)
        assertEquals(dork, jobs.single().discoveryOrigins.single().query)
    }
    @Test fun `published Brazil exclusions survive preview condensation and cannot become Brazilian results`() {
        val cases = listOf(
            "Worldwide except Brazil; remote Java role." to listOf("Brazil"),
            "Remoto exceto Brasil; conhecimento em Java." to listOf("Brasil"),
            "Brasil não elegível; vaga Java remota." to listOf("Brasil"),
            "São Paulo / SP; worldwide except Brazil; Java remote." to listOf("São Paulo"))
        for ((snippet, locations) in cases) {
            val query = SearchQuery(titles = listOf("Java"), locations = locations, remoteAnywhere = true)
            val job = parse(result("https://fixture.gupy.io/jobs/123", snippet = snippet), query).single()
            assertNotNull(snippet, BrazilMarket.exclusionHint(job.location))
            assertFalse(snippet, JobFilter.matches(job, query))
            assertTrue(snippet, JobFilter.matches(job, query.copy(worldwide = true, locations = emptyList())))
        }
    }
    @Test(expected = DorksBlockedException::class) fun `captcha is a block rather than zero jobs`() {
        DorksPages.parse("<form id='challenge-form' action='anomaly.js'>CAPTCHA</form>", config, SearchQuery(), dork, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun `unknown markup is an explicit error`() {
        DorksPages.parse("<html><h1>Unavailable</h1></html>", config, SearchQuery(), dork, 1)
    }
    @Test fun `dork preview preserves richer existing data and all origins`() {
        val previous = Job("saved", "lever:fixture", "1", "Java", "Real Company", "https://company.gupy.io/jobs/123", "São Paulo", description = "Full verified description")
        val preview = parse(result(previous.url, snippet = "Short summary")).single()
        val merged = JobProvenance.merge(preview, previous)
        assertEquals("saved", merged.id); assertEquals("Real Company", merged.company); assertEquals("São Paulo", merged.location)
        assertEquals(previous.description, merged.description); assertTrue(merged.descriptionComplete)
        assertEquals(setOf(DiscoveryMethod.API, DiscoveryMethod.DORKS), merged.discoveryOrigins.map { it.method }.toSet())
    }
    @Test fun `synthetic search markup yields only individual Gupy listings`() {
        val html = javaClass.getResource("/dorks/gupy-search.html")!!.readText()
        val jobs = DorksPages.parse(html, config, SearchQuery(locations = listOf("São Paulo")), dork, 123)
        assertTrue(jobs.isNotEmpty())
        assertTrue(jobs.all { it.url.contains(".gupy.io/jobs/") && !it.descriptionComplete })
        assertTrue(jobs.none { it.url.contains("portal.gupy.io") || it.url.contains("infojobs") })
    }
}

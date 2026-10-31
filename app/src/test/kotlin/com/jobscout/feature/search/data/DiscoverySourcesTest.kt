package com.jobscout.feature.search.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jobscout.domain.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DiscoverySourcesTest {
    private suspend fun requestedCountries(query: SearchQuery): String? {
        var captured: String? = "not-called"
        var calls = 0
        val api = object : FreehireApi {
            override suspend fun search(query: String?, countries: String?, limit: Int, offset: Int, authorization: String): JsonObject {
                captured = countries; calls++
                return JsonParser.parseString("""{"data":[],"meta":{"total":0}}""").asJsonObject
            }
        }
        val result = FreehireSource(api, FreehireTokenProvider { "fixture-local-token" }).search(SourceConfig("freehire", "global", "Fixture"), query)
        assertNull(result.error); assertEquals(1, calls)
        return captured
    }

    @Test fun `global index is restricted to Brazil even with no typed country`() = runTest {
        assertEquals("br", requestedCountries(SearchQuery()))
        assertEquals("br", requestedCountries(SearchQuery(locations = listOf("Campinas"))))
        assertEquals("br", requestedCountries(SearchQuery(locations = listOf("Canada"), remoteAnywhere = true)))
    }

    @Test fun `world opt in removes default country but preserves explicit foreign country`() = runTest {
        assertNull(requestedCountries(SearchQuery(worldwide = true)))
        assertEquals("ca", requestedCountries(SearchQuery(locations = listOf("Canada"), worldwide = true)))
        assertEquals("pt", requestedCountries(SearchQuery(locations = listOf("Portugal"), worldwide = true)))
    }

    @Test fun `global connector uses actual q parameter and maps direct gupy url`() = runTest {
        var capturedQuery: String? = null
        var capturedCountries: String? = null
        val api = object : FreehireApi {
            override suspend fun search(query: String?, countries: String?, limit: Int, offset: Int, authorization: String): JsonObject {
                capturedQuery = query; capturedCountries = countries
                return JsonParser.parseString("""{"data":[{"public_slug":"java-123","title":"Java Developer","company":"Fixture","url":"https://fixture.gupy.io/jobs/123?utm_source=freehire.me","location":"São Paulo","countries":["br"],"work_mode":"remote","description":"<p>SQL Java</p>"}],"meta":{"total":1}}""").asJsonObject
            }
        }
        val result = FreehireSource(api, FreehireTokenProvider { "fixture-local-token" }).search(SourceConfig("freehire", "global", "Freehire"), SearchQuery(titles = listOf("Java"), locations = listOf("Brasil")))
        assertNull(result.error)
        assertEquals("\"Java\"", capturedQuery)
        assertEquals("br", capturedCountries)
        assertEquals("https://fixture.gupy.io/jobs/123", result.jobs.single().url)
        assertTrue(result.jobs.single().location.contains("Brasil"))
        assertEquals(Workplace.REMOTE, result.jobs.single().workplace)
        assertFalse(result.jobs.single().descriptionComplete)
        assertTrue(result.limited)
    }
    @Test fun `rss preserves source attribution date and unknown salary`() {
        val client = OkHttpClient()
        val xml = """<rss><channel><item><title>Fixture: Java Developer</title><link>https://weworkremotely.com/remote-jobs/fixture-java</link><region>Anywhere in the World</region><description><![CDATA[<p>Java and SQL</p><script>bad()</script>]]></description><pubDate>Sun, 25 Oct 2026 07:31:02 +0000</pubDate></item></channel></rss>"""
        val job = RssSource(client, RobotsGuard(client)).parse(xml, SourceConfig("rss", "wwr", "We Work Remotely")).single()
        assertEquals("Java Developer", job.title)
        assertEquals("Fixture", job.company)
        assertEquals(Workplace.REMOTE, job.workplace)
        assertTrue(job.discoveryMethod.contains("We Work Remotely"))
        assertNotNull(job.postedAt)
        assertNull(job.salaryMin)
        assertFalse(job.description.contains("bad()"))
    }
    @Test fun `rss rejects external entity declarations`() {
        val client = OkHttpClient()
        assertTrue(runCatching { RssSource(client, RobotsGuard(client)).parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///secret'>]><rss/>", SourceConfig("rss", "test", "Fixture")) }.isFailure)
    }
    @Test fun `jobposting graph is parsed as data without executing embedded html`() {
        val html = """<html><script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"Organization"},{"@type":"JobPosting","title":"Java Developer","hiringOrganization":{"name":"Fixture"},"description":"<p>SQL</p><script>bad()</script>","jobLocationType":"TELECOMMUTE","datePosted":"2026-10-20","jobLocation":{"address":{"addressLocality":"São Paulo","addressCountry":"BR"}}}]}</script></html>"""
        // JSON-LD cannot contain a literal closing script token inside its own HTML script element.
        val safeHtml = html.replace("<script>bad()</script>", "&lt;script&gt;bad()&lt;/script&gt;")
        val job = JobPostingParser.parse(safeHtml, "https://careers.example.com/jobs/123?utm_source=google")
        assertEquals("https://careers.example.com/jobs/123", job.url)
        assertEquals("Fixture", job.company)
        assertEquals(Workplace.REMOTE, job.workplace)
        assertTrue(job.location.contains("São Paulo"))
        assertFalse(job.description.contains("bad()"))
    }
    @Test fun `ordinary article cannot become a fabricated job`() {
        assertTrue(runCatching { JobPostingParser.parse("<h1>Java news</h1>", "https://example.com/news") }.isFailure)
    }
    @Test fun `robots longest allow and specific user agent are respected`() {
        assertTrue(runCatching { RobotsPolicy.allows("<!DOCTYPE html><h1>Robots page</h1>", "https://example.com/jobs/1") }.isFailure)
        val rules = "User-agent: *\nDisallow: /jobs\nAllow: /jobs/public\n"
        assertFalse(RobotsPolicy.allows(rules, "https://example.com/jobs/private"))
        assertTrue(RobotsPolicy.allows(rules, "https://example.com/jobs/public/1"))
        assertFalse(RobotsPolicy.allows("User-agent: *\nAllow: /\nUser-agent: JobScout\nDisallow: /\n", "https://example.com/jobs/1"))
        assertFalse(RobotsPolicy.allows("User-agent: *\nDisallow: /search\n", "https://www.google.com/search?q=java"))
    }
}

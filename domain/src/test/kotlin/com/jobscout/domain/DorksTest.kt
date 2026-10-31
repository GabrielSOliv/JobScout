package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class DorksTest {
    @Test fun `remote outside selected cities broadens discovery within chosen country scope`() {
        val query = SearchQuery(titles = listOf("Java"), locations = listOf("São Paulo"), remoteAnywhere = true)
        val brazil = DorksPlanner.queries("gupy", query, CandidateProfile()).single()
        assertTrue(brazil.contains("\"Brasil\""))
        assertFalse(brazil.contains("São Paulo"))
        assertFalse(DorksPlanner.queries("gupy", query.copy(worldwide = true), CandidateProfile()).single().contains("Brasil"))
    }
    @Test fun `queries target Linkedin and Gupy with actual roles and city without private history`() {
        val profile = CandidateProfile(skills = listOf("PRIVATE SKILL"), experience = "PRIVATE HISTORY", targets = listOf("Kotlin"))
        val query = SearchQuery(titles = listOf("Java"), locations = listOf("São Paulo", "Brasil"), excludedKeywords = listOf("senior"), remoteAnywhere = false)
        val linkedin = DorksPlanner.queries("linkedin", query, profile).single()
        assertTrue(linkedin.startsWith("site:linkedin.com Java"))
        assertFalse(linkedin.contains("inurl:"))
        assertTrue(linkedin.contains("\"São Paulo\"")); assertFalse(linkedin.contains("Brasil"))
        assertTrue(linkedin.contains("-\"senior\"")); assertFalse(linkedin.contains("PRIVATE"))
        val gupy = DorksPlanner.queries("gupy", query, profile).single()
        assertTrue(gupy.startsWith("site:gupy.io Java"))
        assertFalse(gupy.contains("inurl:"))
        assertTrue(DorksPlanner.queries("other", query, profile).isEmpty())
    }
    @Test fun `queries are bounded and require a real role`() {
        assertTrue(DorksPlanner.queries("gupy", SearchQuery(), CandidateProfile(skills = listOf("SQL"))).isEmpty())
        assertEquals(2, DorksPlanner.queries("gupy", SearchQuery(titles = listOf("Java", "Kotlin", "SQL"), locations = listOf("Recife", "São Paulo")), CandidateProfile()).size)
        // Cleared positions must not resurrect profile targets or keywords.
        assertTrue(DorksPlanner.queries("gupy", SearchQuery(), CandidateProfile(targets = listOf("Java"))).isEmpty())
        assertTrue(DorksPlanner.queries("gupy", SearchQuery(keywords = listOf("java")), CandidateProfile(targets = listOf("Java"))).isEmpty())
    }
    @Test fun `two slots cover both roles and both selected cities`() {
        val queries = DorksPlanner.queries("gupy", SearchQuery(titles = listOf("Java", "Kotlin"), locations = listOf("Recife", "São Paulo"), remoteAnywhere = false), CandidateProfile())
        assertEquals(2, queries.size)
        assertTrue(queries[0].contains("Java \"Recife\""))
        assertTrue(queries[1].contains("Kotlin \"São Paulo\""))
        val singleRole = DorksPlanner.queries("gupy", SearchQuery(titles = listOf("Java"), locations = listOf("Recife", "São Paulo"), remoteAnywhere = false), CandidateProfile())
        assertEquals(2, singleRole.size)
        assertTrue(singleRole.all { it.contains("Java") })
    }
    @Test fun `Brazil is the default query context and world opt in does not insert Brazil`() {
        val query = SearchQuery(titles = listOf("Java"))
        assertTrue(DorksPlanner.queries("linkedin", query, CandidateProfile()).single().endsWith("\"Brasil\""))
        assertFalse(DorksPlanner.queries("linkedin", query.copy(worldwide = true), CandidateProfile()).single().contains("Brasil"))
    }
    @Test fun `search redirects unwrap only into individual allowed job links`() {
        assertEquals("https://company.gupy.io/jobs/123", DorksLinks.original("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fcompany.gupy.io%2Fjobs%2F123%3Futm_source%3Dx&rut=abc", "gupy"))
        assertEquals("https://www.linkedin.com/jobs/view/123456/", DorksLinks.original("https://br.linkedin.com/jobs/view/java-developer-at-company-123456?trk=ads&refId=abc", "linkedin"))
        listOf("https://portal.gupy.io/job-search/term=java", "https://company.gupy.io/jobs/", "https://company.gupy.io/jobs/123/apply", "https://evilgupy.io/jobs/123", "https://company.gupy.io.evil.com/jobs/123", "https://www.googleadservices.com/aclk").forEach { assertNull(it, DorksLinks.original(it, "gupy")) }
        assertNull(DorksLinks.original("https://linkedin.com/jobs/search?currentJobId=123", "linkedin"))
        assertNull(DorksLinks.original("https://linkedin.com.evil.com/jobs/view/123", "linkedin"))
    }
    @Test fun `observed Gupy token URLs preserve original identity and encoding`() {
        val pagBank = "https://pagseguro.gupy.io/job/eyJqb2JJZCI6MTI2NzA3NTgsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0%3D?jobBoardSource=gupy_portal"
        val nuclea = "https://nuclea.gupy.io/job/eyJqb2JJZCI6MTI3MjQ3NDYsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0%3D?jobBoardSource=gupy_portal"
        assertEquals(pagBank, DorksLinks.original(pagBank, "gupy"))
        assertEquals(nuclea, DorksLinks.original(nuclea, "gupy"))
        assertEquals(pagBank, DorksLinks.original("https://duckduckgo.com/l/?uddg=${URLEncoder.encode(pagBank, "UTF-8")}&rut=tracking", "gupy"))
    }
    @Test fun `Gupy tokens must be valid flat job objects rather than company or arbitrary pages`() {
        fun url(payload: String) = "https://fixture.gupy.io/job/" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        listOf("{\"source\":\"gupy_portal\"}", "{\"jobId\":0}", "{\"jobId\":-1}", "{\"jobId\":\"123\"}",
            "{\"jobId\":1e5}", "{\"jobId\":9223372036854775808}", "{\"jobId\":12,\"jobId\":13}",
            "{\"nested\":{\"jobId\":123}}", "{\"jobId\":12}junk", "{\"jobId\":12,}").forEach { assertNull(it, DorksLinks.original(url(it), "gupy")) }
        val valid = url("{\"jobId\":123,\"source\":\"gupy_portal\"}")
        assertEquals(valid, DorksLinks.original(valid, "gupy"))
        assertNull(DorksLinks.original(valid + "/apply", "gupy"))
        assertNull(DorksLinks.original(valid.replace("fixture.gupy.io", "fixture.gupy.io.evil.com"), "gupy"))
        assertNull(DorksLinks.original("https://fixture.gupy.io/job/this-is-not-base64-json", "gupy"))
        assertNull(DorksLinks.original("https://fixture.gupy.io/jobs/0", "gupy"))
    }
    @Test fun `upgrade adds priority dorks while retaining user startup and disabled source preferences`() {
        val previous = AppPreferences(discoveryVersion = 3, autoScanOnOpen = false,
            sources = listOf(SourceConfig("lever", "ciandt", "CI&T", false)))
        val upgraded = SourceCatalog.upgrade(previous)
        assertFalse(upgraded.autoScanOnOpen)
        assertEquals(listOf("dorks:linkedin", "dorks:gupy"), upgraded.sources.filter { it.kind == "dorks" }.map { it.key })
        assertTrue(upgraded.sources.filter { it.kind == "dorks" }.none { it.enabled })
        assertFalse(upgraded.sources.first { it.kind == "lever" }.enabled)
        assertEquals(upgraded, SourceCatalog.upgrade(upgraded))
    }
    @Test fun `dorks require recorded executed query even when source key says dorks`() {
        val job = Job("1", "dorks:linkedin", "1", "Java", "Fixture", "https://www.linkedin.com/jobs/view/123/")
        assertEquals(DiscoveryMethod.UNKNOWN, JobProvenance.origins(job).single().method)
        val evidenced = job.copy(discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, job.source, "LinkedIn", 1, 2, "site:linkedin.com/jobs/view Java")))
        assertEquals(DiscoveryMethod.DORKS, JobProvenance.origins(evidenced).single().method)
    }
}

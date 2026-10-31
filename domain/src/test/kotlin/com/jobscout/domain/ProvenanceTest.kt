package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class ProvenanceTest {
    private val api = Job("old", "freehire:global", "x", "Java", "Fixture", "https://example.com/jobs/1", discoveryMethod = "Índice web · Freehire", descriptionComplete = false)
    @Test fun `preview and older cached page cannot erase a recent unavailable check`() {
        val latest = api.copy(verification = JobVerification(VerificationState.UNAVAILABLE, 300L, "HTTP404"))
        assertEquals(latest.verification, JobProvenance.merge(api, latest).verification)
        val cached = api.copy(verification = JobVerification(VerificationState.PAGE_READ, 200L))
        assertEquals(latest.verification, JobProvenance.merge(cached, latest).verification)
        val reread = cached.copy(verification = JobVerification(VerificationState.PAGE_READ, 400L))
        assertEquals(reread.verification, JobProvenance.merge(reread, latest).verification)
    }
    @Test fun `an index API is never mislabelled HTML or dorks`() {
        assertEquals(DiscoveryMethod.API, JobProvenance.origins(api).single().method)
        assertEquals(DiscoveryMethod.API, JobProvenance.origins(api.copy(source = "greenhouse:fixture", discoveryMethod = "ATS")).single().method)
        assertEquals(DiscoveryMethod.RSS, JobProvenance.origins(api.copy(source = "rss:wwr")).single().method)
    }
    @Test fun `HTML collectors and imported links have distinct origins`() {
        assertEquals(DiscoveryMethod.HTML, JobProvenance.origins(api.copy(source = "web:empregos", discoveryMethod = "Coleta HTML · Empregos")).single().method)
        assertEquals(DiscoveryMethod.SHARED_LINK, JobProvenance.origins(api.copy(source = "web:example.com", discoveryMethod = "Página web · JobPosting")).single().method)
    }
    @Test fun `merge keeps multiple actual origins stable id and complete description`() {
        val html = api.copy(id = "new", source = "web:empregos", discoveryMethod = "Coleta HTML · Empregos", description = "Complete SQL requirements", descriptionComplete = true)
        val merged = JobProvenance.merge(html, api)
        assertEquals("old", merged.id)
        assertEquals(setOf(DiscoveryMethod.API, DiscoveryMethod.HTML), merged.discoveryOrigins.map { it.method }.toSet())
        val refreshed = JobProvenance.merge(api.copy(collectedAt = api.collectedAt + 1000), merged)
        assertEquals(2, refreshed.discoveryOrigins.size)
        assertEquals("Complete SQL requirements", refreshed.description)
        assertTrue(refreshed.descriptionComplete)
    }
    @Test fun `historical salary bundle is retained but never mixed with another currency`() {
        val before = api.copy(salaryMin = 5000.0, currency = "BRL", salaryPeriod = "month")
        assertEquals("BRL", JobProvenance.merge(api, before).currency)
        val changed = JobProvenance.merge(api.copy(salaryMax = 120000.0, currency = "USD", salaryPeriod = "year"), before)
        assertNull(changed.salaryMin)
        assertEquals("USD", changed.currency)
        assertEquals("year", changed.salaryPeriod)
    }
    @Test fun `dorks require recorded evidence and never follow a random word in a description`() {
        assertEquals(DiscoveryMethod.API, JobProvenance.origins(api.copy(description = "dorks google advanced search")).single().method)
        val unverified = api.copy(discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, "dorks", "Google", 1, 2)))
        assertFalse(JobProvenance.origins(unverified).any { it.method == DiscoveryMethod.DORKS })
        val evidence = unverified.copy(discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, "dorks", "Google", 1, 2, "site:careers.example.com Java")))
        assertEquals(DiscoveryMethod.DORKS, JobProvenance.origins(evidence).single().method)
    }
}

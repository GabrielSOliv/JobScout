package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun `city is not broadened by its country while multiple cities remain alternatives`() {
        val job = Job("x", "fixture", "x", "Java Developer", "Fixture", "https://example.com/job/1", "Recife, PE · Brasil")
        assertFalse(JobFilter.matches(job, SearchQuery(locations = listOf("São Paulo", "Brasil"))))
        assertTrue(JobFilter.matches(job, SearchQuery(locations = listOf("São Paulo", "Recife", "Brasil"))))
        assertTrue(JobFilter.matches(job, SearchQuery(locations = listOf("Brasil"))))
    }
    @Test fun `web queries preserve every selected role without narrowing it to profile skills`() {
        val profile = CandidateProfile(skills = listOf("SQL"), experience = "PRIVATE HISTORY", targets = listOf("automação"))
        val terms = DiscoveryPlanner.webTerms(SearchQuery(titles = listOf("Java")), profile)
        assertEquals(listOf("Java"), terms)
        assertTrue(terms.none { "PRIVATE" in it || "automação" in it })
        assertTrue(DiscoveryPlanner.webTerms(SearchQuery(), CandidateProfile()).isEmpty())
        assertEquals(listOf("SQL"), profile.skills)
        val roles = (1..12).map { "Cargo $it" }
        assertEquals(roles, DiscoveryPlanner.webTerms(SearchQuery(titles = roles), profile))
        assertEquals(roles, DiscoveryPlanner.automaticQuery(AppPreferences(profile = profile.copy(targets = roles))).titles)
    }
    @Test fun `dorks use desired roles and locations without sending resume history`() {
        val profile = CandidateProfile(skills = listOf("SQL"), experience = "PRIVATE HISTORY", targets = listOf("Java Developer"))
        val queries = DiscoveryPlanner.dorks(SearchQuery(locations = listOf("São Paulo", "Brasil"), excludedKeywords = listOf("senior")), profile)
        assertEquals(4, queries.size)
        assertTrue(queries.first().query.contains("site:gupy.io/jobs/"))
        assertTrue(queries.first().query.contains("\"Java Developer\""))
        assertTrue(queries.first().query.contains("-\"senior\""))
        assertFalse(queries.any { it.query.contains("PRIVATE HISTORY") || it.query.contains("SQL") })
        assertTrue(queries.all { it.googleUrl.startsWith("https://www.google.com/search?q=") })
    }
    @Test fun `no empty dorks or inferred professional skills`() {
        assertTrue(DiscoveryPlanner.dorks(SearchQuery(), CandidateProfile(skills = listOf("VBA"))).isEmpty())
        val prefs = AppPreferences(profile = CandidateProfile(skills = listOf("SQL"), targets = listOf("Java")))
        assertEquals(listOf("Java"), DiscoveryPlanner.automaticQuery(prefs).titles)
        assertEquals(listOf("SQL"), prefs.profile.skills)
    }
    @Test fun `upgrade retains profile presets disabled sources and adds wider discovery`() {
        val old = AppPreferences(sources = listOf(SourceConfig("lever", "ciandt", "CI&T", false)),
            profile = CandidateProfile(skills = listOf("SQL"), experience = "Confirmed work"),
            presets = listOf(SearchPreset("Minha busca", SearchQuery(titles = listOf("Java")))))
        val upgraded = SourceCatalog.upgrade(old)
        assertEquals(old.profile, upgraded.profile)
        assertEquals(old.presets, upgraded.presets)
        assertFalse(upgraded.sources.first { it.key == "lever:ciandt" }.enabled)
        assertTrue(upgraded.sources.any { it.kind == "rss" })
        assertTrue(upgraded.sources.any { it.kind == "freehire" })
        assertEquals(6, upgraded.discoveryVersion)
        assertEquals(3, upgraded.sources.count { it.kind == "web" })
        assertEquals(upgraded.sources.size, upgraded.sources.map { it.key }.distinct().size)
    }
    @Test fun `hash routed job identity survives while cosmetic anchors and trackers go away`() {
        assertNotEquals(JobLinks.canonical("https://careers.example.com/#/jobs/1"), JobLinks.canonical("https://careers.example.com/#/jobs/2"))
        assertEquals("https://example.com/job/1", JobLinks.canonical("https://example.com/job/1?utm_source=ads#apply"))
    }
    @Test fun `shared google links unwrap to actual job and reject ad destinations`() {
        assertEquals("https://company.gupy.io/jobs/123", JobLinks.fromSharedText("Vaga https://www.google.com/url?q=https%3A%2F%2Fcompany.gupy.io%2Fjobs%2F123%3Futm_source%3Dgoogle&sa=t"))
        assertNull(JobLinks.fromSharedText("https://www.googleadservices.com/pagead/aclk?id=123"))
    }
}

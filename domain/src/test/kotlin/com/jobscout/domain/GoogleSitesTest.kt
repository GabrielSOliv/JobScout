package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class GoogleSitesTest {
    @Test fun `every valid site is kept beyond the old four site limit`() {
        val sites = List(8) { "jobs$it.example.com" }
        assertEquals(sites, GoogleSites.additional(sites))
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = List(8) { "Position$it" }), CandidateProfile(), sites = sites)
        sites.forEach { site ->
            assertEquals(8, tasks.filter { it.board == "site:$site" && it.strategy == DorkStrategy.TITLE }.flatMap { it.searchRoles }.size)
        }
    }
    @Test fun `domain input is literal and invalid or local targets are rejected`() {
        assertEquals("empresa.com.br", GoogleSites.normalize("https://www.empresa.com.br/carreiras?q=dev"))
        listOf("localhost", "127.0.0.1", "https://[::1]/", "http://example.com", "https://user@example.com", "https://example.com:8080", "example.com OR site:evil.com", "empresa.local", "google.com").forEach {
            assertNull(it, GoogleSites.normalize(it))
        }
        assertTrue(GoogleSites.hostMatches("https://jobs.example.com/jobs/123", "example.com"))
        assertFalse(GoogleSites.hostMatches("https://example.com.evil.com/jobs/123", "example.com"))
    }
    @Test fun `every configured site receives all supported roles before alternatives`() {
        val sites = listOf("one.example.com", "two.example.com", "three.example.com", "four.example.com")
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java", "Kotlin", "Analista")), CandidateProfile(), sites = sites)
        assertTrue(tasks.size <= 18)
        sites.forEach { site -> assertEquals(setOf("Java", "Kotlin", "Analista"), tasks.filter { it.board == "site:$site" && it.strategy == DorkStrategy.TITLE }.flatMap { it.searchRoles }.toSet()) }
        assertTrue(tasks.none { "inurl:" in it.query })
        assertTrue(tasks.all { GoogleDorksPlanner.sameQuery(it.url, it.query) })
    }
}

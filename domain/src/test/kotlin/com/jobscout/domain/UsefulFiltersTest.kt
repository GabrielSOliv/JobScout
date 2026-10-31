package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class UsefulFiltersTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun job() = Job("fixture", "lever:fixture", "fixture", "Java Developer", "Fixture", "https://example.com/jobs/1",
        location = "São Paulo, Brasil", postedAt = now - day, firstSeen = now - day, collectedAt = now)
    private fun matches(job: Job = job(), query: SearchQuery) = JobFilter.matches(job, query, now = now)

    @Test fun `date windows include boundary dates and reject older or future publication dates`() {
        val query = SearchQuery(postedWithinDays = 7)
        assertTrue(matches(job().copy(postedAt = now - 7 * day), query))
        assertFalse(matches(job().copy(postedAt = now - 7 * day - 1), query))
        assertFalse(matches(job().copy(postedAt = now + 1), query))
    }

    @Test fun `unknown publication dates are an explicit choice rather than invented timestamps`() {
        val unknown = job().copy(postedAt = null, collectedAt = now)
        assertTrue(matches(unknown, SearchQuery(postedWithinDays = 7, includeUnknownDates = true)))
        assertFalse(matches(unknown, SearchQuery(postedWithinDays = 7, includeUnknownDates = false)))
        assertFalse(matches(job().copy(postedAt = now - 30 * day), SearchQuery(postedWithinDays = 7, includeUnknownDates = true)))
        assertNull(unknown.postedAt)
    }

    @Test fun `discovery periods use first seen and do not reset when a cached listing is refreshed`() {
        val oldPublication = job().copy(postedAt = now - 60 * day)
        assertTrue(matches(oldPublication, SearchQuery(postedWithinDays = 3, dateBasis = DateBasis.DISCOVERY)))
        assertFalse(matches(oldPublication, SearchQuery(postedWithinDays = 3, dateBasis = DateBasis.PUBLICATION)))
        val refreshed = oldPublication.copy(firstSeen = now - 30 * day, collectedAt = now)
        assertFalse(matches(refreshed, SearchQuery(postedWithinDays = 3, dateBasis = DateBasis.DISCOVERY)))
    }

    @Test fun `invalid date windows do not silently act as any date`() {
        assertFalse(matches(query = SearchQuery(postedWithinDays = -1)))
        assertFalse(matches(query = SearchQuery(postedWithinDays = 0)))
    }

    @Test fun `original page filter needs actual read evidence and a nonexpired publication`() {
        val query = SearchQuery(onlyVerifiedPages = true)
        assertFalse(matches(query = query))
        assertFalse(matches(job().copy(verification = JobVerification(VerificationState.PAGE_READ)), query))
        val read = job().copy(verification = JobVerification(VerificationState.PAGE_READ, checkedAt = now))
        assertTrue(matches(read, query))
        assertFalse(matches(read.copy(verification = read.verification.copy(validThrough = now - 1)), query))
        assertFalse(matches(read.copy(verification = read.verification.copy(state = VerificationState.RESTRICTED)), query))
    }

    @Test fun `known unavailable nonjob and expired pages are excluded even without verification only filter`() {
        listOf(VerificationState.UNAVAILABLE, VerificationState.NOT_A_JOB, VerificationState.EXPIRED).forEach { state ->
            assertFalse(state.name, matches(job().copy(verification = JobVerification(state, checkedAt = now)), SearchQuery()))
        }
        assertTrue(matches(job().copy(verification = JobVerification(VerificationState.RESTRICTED, checkedAt = now)), SearchQuery()))
    }

    @Test fun `unconfirmed location opt in does not admit explicit foreign or excluded Brazil locations`() {
        val query = SearchQuery(locations = listOf("Brasil"), includeUnknownLocations = true)
        assertTrue(matches(job().copy(location = ""), query))
        assertTrue(matches(job().copy(location = "Remote"), query))
        assertFalse(matches(job().copy(location = "Austin, United States"), query))
        assertFalse(matches(job().copy(location = "Worldwide except Brazil"), query))
        assertFalse(matches(job().copy(location = ""), query.copy(includeUnknownLocations = false)))
    }

    @Test fun `unconfirmed location does not override a different published city`() {
        val query = SearchQuery(locations = listOf("São Paulo"), includeUnknownLocations = true)
        assertFalse(matches(job().copy(location = "Lisboa"), query))
        assertFalse(matches(job().copy(location = "Campinas, Brasil"), query))
        assertTrue(matches(job().copy(location = ""), query))
    }

    @Test fun `talent pools can be explicitly included without excluding ordinary ads mentioning a pool`() {
        val pool = job().copy(title = "Banco de talentos - Java")
        assertFalse(matches(pool, SearchQuery()))
        assertTrue(matches(pool, SearchQuery(excludeTalentPools = false)))
        assertTrue(matches(job().copy(description = "Não é uma inscrição em banco de talentos."), SearchQuery()))
    }

    @Test fun `contract filters use contract evidence and never infer CLT or PJ from full time hours`() {
        assertFalse(matches(job().copy(employment = "FULL_TIME"), SearchQuery(employment = "CLT")))
        assertFalse(matches(job().copy(employment = "Full-time"), SearchQuery(employment = "PJ")))
        assertFalse(matches(job().copy(employment = null), SearchQuery(employment = "CLT")))
        assertTrue(matches(job().copy(employment = "Contrato CLT"), SearchQuery(employment = "CLT")))
        assertTrue(matches(job().copy(employment = "Pessoa jurídica"), SearchQuery(employment = "PJ")))
        assertTrue(matches(job().copy(employment = "INTERNSHIP"), SearchQuery(employment = "Estágio")))
    }

    @Test fun `method filters accept any recorded provenance and require dork query evidence`() {
        assertTrue(matches(query = SearchQuery(methods = setOf(DiscoveryMethod.API))))
        assertFalse(matches(query = SearchQuery(methods = setOf(DiscoveryMethod.DORKS))))
        val dork = DiscoveryOrigin(DiscoveryMethod.DORKS, "google:gupy", "Google", now, now, "site:gupy.io Java")
        val sourced = job().copy(discoveryOrigins = listOf(dork))
        assertTrue(matches(sourced, SearchQuery(methods = setOf(DiscoveryMethod.DORKS))))
        assertFalse(matches(sourced.copy(discoveryOrigins = listOf(dork.copy(query = null))), SearchQuery(methods = setOf(DiscoveryMethod.DORKS))))
    }

    @Test fun `unknown data and verification options never relax other hard criteria`() {
        val query = SearchQuery(titles = listOf("Designer"), includeUnknownDates = true, includeUnknownLocations = true)
        assertFalse(matches(job().copy(location = "", postedAt = null), query))
        assertFalse(matches(query = SearchQuery(salaryMin = 1_000.0, includeUnknownDates = true)))
        assertFalse(matches(query = SearchQuery(workplaces = setOf(Workplace.ONSITE), includeUnknownLocations = true)))
    }
}

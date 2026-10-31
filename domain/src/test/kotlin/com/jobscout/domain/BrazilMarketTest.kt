package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class BrazilMarketTest {
    private fun job(location: String, workplace: Workplace = Workplace.REMOTE) =
        Job("fixture", "fixture:board", "fixture", "Java Developer", "Fixture", "https://example.com/jobs/1", location, workplace)

    @Test fun `published Brazilian country names and codes are accepted by default`() {
        listOf("Brasil", "Brazil", "Remote · BR", "São Paulo, Brazil", "Brazil · United States").forEach {
            assertTrue(it, JobFilter.matches(job(it), SearchQuery()))
        }
    }

    @Test fun `complete IBGE municipality and state pairs recognize places beyond common cities`() {
        listOf("São Gabriel da Palha / ES", "Corumbá - MS", "Santa Maria, RS", "sao gabriel da palha, es", "Embu-Guaçu / SP", "Franca / SP", "Colômbia / SP").forEach {
            assertTrue(it, BrazilMarket.matches(it))
        }
        assertEquals("São Gabriel da Palha / ES", BrazilMarket.locationHint("Local de trabalho: São Gabriel da Palha - ES"))
        assertFalse(BrazilMarket.matches("São Gabriel da Palha / SP"))
    }

    @Test fun `ambiguous cities and foreign state abbreviations are not Brazilian evidence`() {
        listOf("Santa Maria", "Santa Maria, CA", "Madrid, ES", "San Francisco, CA", "Toronto, Canada", "New York, US").forEach {
            assertFalse(it, BrazilMarket.matches(it))
        }
        assertTrue(BrazilMarket.matches("Campinas"))
    }

    @Test fun `unknown locations and worldwide feeds require world opt in`() {
        listOf("", "Remote", "Worldwide", "Anywhere in the World", "LATAM", "Europe").forEach {
            assertFalse(it, JobFilter.matches(job(it), SearchQuery()))
            assertTrue(it, JobFilter.matches(job(it), SearchQuery(worldwide = true)))
        }
    }

    @Test fun `remote anywhere does not bypass Brazil default`() {
        val foreign = job("New York, United States")
        assertFalse(JobFilter.matches(foreign, SearchQuery(remoteAnywhere = true)))
        assertTrue(JobFilter.matches(foreign, SearchQuery(remoteAnywhere = true, worldwide = true)))
    }

    @Test fun `world scope still respects selected city and other hard criteria`() {
        val foreign = job("Toronto, Canada")
        assertTrue(JobFilter.matches(foreign, SearchQuery(worldwide = true)))
        assertFalse(JobFilter.matches(foreign, SearchQuery(locations = listOf("São Paulo"), worldwide = true, remoteAnywhere = false)))
        assertTrue(JobFilter.matches(foreign, SearchQuery(locations = listOf("Toronto"), worldwide = true)))
        assertFalse(JobFilter.matches(foreign, SearchQuery(titles = listOf("Designer"), worldwide = true)))
    }

    @Test fun `employer language salary or description cannot turn a foreign job into a Brazilian job`() {
        val foreign = job("San Francisco, US").copy(company = "Empresa Brasileira", description = "Equipe no Brasil usa Java.",
            currency = "BRL", salaryMin = 8_000.0)
        assertFalse(JobFilter.matches(foreign, SearchQuery()))
        assertFalse(JobFilter.matches(foreign.copy(location = ""), SearchQuery()))
    }

    @Test fun `an explicit exclusion of Brazil is not mistaken for eligibility`() {
        listOf("Worldwide except Brazil", "Remote excluding Brazil", "Remoto, exceto Brasil", "Todos os países menos o Brasil").forEach {
            assertFalse(it, BrazilMarket.matches(it))
            assertFalse(it, JobFilter.matches(job(it), SearchQuery(remoteAnywhere = true)))
            assertFalse(it, JobFilter.matches(job(it), SearchQuery(locations = listOf("Brasil"), worldwide = true, remoteAnywhere = false)))
        }
    }

    @Test fun `a foreign country suffix overrides a distinctive city name but not a valid Brazilian UF`() {
        listOf("Campinas, PT", "São Paulo, CA", "Curitiba - DE", "Brasília / AU").forEach { assertFalse(it, BrazilMarket.matches(it)) }
        assertTrue(BrazilMarket.matches("São Gabriel da Palha / ES"))
        assertTrue(Geography.matches("São Gabriel da Palha / ES", listOf("Brasil")))
        assertFalse(Geography.matches("Worldwide except Brazil", listOf("Brasil")))
        assertEquals("São Paulo", BrazilMarket.locationHint("Java Developer São Paulo. Join us to work on Java!"))
    }
}

package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun job(id: String = "1", url: String = "https://jobs.example.com/jobs/1") = Job(id, "fixture:company", id,
        "Junior Java Developer", "Example", url, "São Paulo, Brazil", Workplace.REMOTE,
        description = "Java, SQL, Spring and integrations.")

    @Test fun `links remove tracking but preserve identifiers and encoding`() {
        assertEquals("https://stripe.com/jobs/search?gh_jid=123", JobLinks.canonical("https://stripe.com/jobs/search?gh_jid=123&utm_source=ads&gh_src=spam#apply"))
        assertEquals("https://example.com/a%20b?id=a%2Fb", JobLinks.canonical("https://EXAMPLE.com/a%20b?id=a%2Fb"))
    }
    @Test fun `unsafe schemes trackers credentials and local addresses are rejected`() {
        listOf("javascript:alert(1)", "http://example.com/jobs/1", "https://user:pass@example.com/a", "https://localhost/a",
            "https://127.0.0.1/a", "https://googleadservices.com/pagead/aclk?id=1", "https://x.doubleclick.net/job").forEach {
            assertNull(it, JobLinks.canonical(it))
        }
    }
    @Test fun `known paid CPL redirects from job indexes are not original job links`() {
        assertNull(JobLinks.canonical("https://br.whatjobs.com/pub_api__cpl__465216284__7076?utm_source=freehire.me"))
        assertNull(JobLinks.canonical("https://en-id.whatjobs.com/pub_api__cpl__17623037__7105"))
        assertEquals("https://br.whatjobs.com/jobs/123", JobLinks.canonical("https://br.whatjobs.com/jobs/123"))
        assertFalse(JobFilter.matches(job().copy(url = "https://br.whatjobs.com/pub_api__cpl__465216284__7076"), SearchQuery()))
    }
    @Test fun `dedup does not merge separate jobs with same title and company`() {
        val original = job()
        val copies = listOf(original, original.copy(url = original.url + "?utm_medium=x"), job("2", "https://jobs.example.com/jobs/2"))
        assertEquals(listOf("1", "2"), JobLinks.deduplicate(copies).map { it.sourceId })
    }
    @Test fun `filters support multiple targets and exact technology boundaries`() {
        assertTrue(JobFilter.matches(job(), SearchQuery(titles = listOf("Designer", "Developer"), locations = listOf("Recife", "Sao Paulo"), keywords = listOf("Java", "SQL"))))
        assertFalse(containsTerm("JavaScript", "Java"))
        assertTrue(containsTerm("Develop C++ and C# systems in .NET.", "C++"))
        assertTrue(containsTerm("Develop C++ and C# systems in .NET.", ".NET"))
        assertFalse(JobFilter.matches(job(), SearchQuery(excludedKeywords = listOf("Spring"))))
    }
    @Test fun `hard salary and date filters reject unknown fields`() {
        assertFalse(JobFilter.matches(job(), SearchQuery(salaryMin = 1000.0)))
        assertFalse(JobFilter.matches(job(), SearchQuery(postedWithinDays = 7, includeUnknownDates = false)))
        assertFalse(JobFilter.matches(job().copy(salaryMin = 9000.0, currency = "BRL", salaryPeriod = "year"), SearchQuery(salaryMin = 1000.0)))
        assertTrue(JobFilter.matches(job().copy(salaryMin = 9000.0, currency = "BRL", salaryPeriod = "month"), SearchQuery(salaryMin = 1000.0)))
    }
    @Test fun `remote jobs respect cities when outside city option is disabled`() {
        assertFalse(JobFilter.matches(job(), SearchQuery(locations = listOf("Canada"), remoteAnywhere = false)))
        assertTrue(JobFilter.matches(job(), SearchQuery(locations = listOf("Canada"), remoteAnywhere = true)))
    }
    @Test fun `aspirations do not become skill or experience evidence`() {
        val score = RankingEngine.score(job(), CandidateProfile(targets = listOf("Java")), SearchQuery(remoteAnywhere = false))
        assertEquals(20, score.total)
        assertEquals(0.0, score.parts[0].earned, 0.0)
        assertEquals(0.0, score.parts[1].earned, 0.0)
        assertTrue(score.matched.isEmpty())
    }
    @Test fun `weight normalization handles zero and custom totals`() {
        val profile = CandidateProfile(skills = listOf("Java", "Python"))
        assertEquals(50, RankingEngine.score(job(), profile, SearchQuery(), ScoreWeights(80, 0, 0, 0)).total)
        assertEquals(20, RankingEngine.score(job(), profile, SearchQuery(remoteAnywhere = false), ScoreWeights(0, 0, 0, 0)).total)
    }
    @Test fun `resume separates work section from projects and leaves targets empty`() {
        val draft = ResumeParser.parse("""
            Nome Sobrenome
            Experiência profissional
            Analista de automação VBA e SQL na Empresa de 2020 a 2024.
            Projetos
            Curso e projeto pessoal em Java e Kotlin.
            Formação
            Sistemas de Informação
            Idiomas
            Inglês
        """.trimIndent())
        assertTrue(draft.profile.skills.containsAll(listOf("VBA", "SQL", "Java", "Kotlin")))
        assertFalse(draft.profile.experience.contains("Kotlin"))
        assertTrue(draft.profile.targets.isEmpty())
        assertEquals("Sistemas de Informação", draft.profile.education)
    }
    @Test fun `image only resume produces explicit OCR message`() {
        val error = runCatching { ResumeParser.parse("  \n ") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("OCR"))
    }
    @Test fun `editable dictionary expands titles without inventing qualifications`() {
        assertTrue(JobFilter.matches(job(), SearchQuery(titles = listOf("desenvolvedor"))))
        assertTrue(JobFilter.matches(job(), SearchQuery(titles = listOf("programador")), mapOf("programador" to listOf("developer"))))
    }
    @Test fun `profile query suggestions preserve aspirations separately from evidence`() {
        val profile = CandidateProfile(skills = listOf("VBA", "SQL"), targets = listOf("junior Java developer"))
        val suggestions = ProfileQueryPlanner.suggestedTitles(profile)
        assertTrue("junior Java developer" in suggestions)
        assertTrue("automation developer" in suggestions)
        assertFalse("Java" in profile.skills)
        assertEquals("", profile.experience)
    }
}

package com.jobscout.domain

import java.net.URI
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GoogleDorksPlannerTest {
    @Test fun `domain filter is independent of OR syntax and reapplied to legacy search URLs`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java", "BI", "Accountant"),
            seniorities = setOf(Seniority.JUNIOR, Seniority.MID)), CandidateProfile(), sites = listOf("jobs.example.com"))
        tasks.forEach { task ->
            val host = GoogleDorksPlanner.siteHost(task.board)!!
            assertTrue(task.url.contains("as_sitesearch=$host"))
            val legacy = task.url.replace("&as_sitesearch=$host", "") + "&as_dt=e&as_sitesearch=facebook.com"
            val restored = GoogleDorksPlanner.scopedUrl(legacy, task.board)
            assertTrue(restored.contains("as_sitesearch=$host"))
            assertFalse(restored.contains("as_dt=")); assertFalse(restored.contains("facebook.com"))
            assertEquals(task.query, GoogleDorksPlanner.queryText(restored))
            assertEquals(1, "as_sitesearch=".toRegex().findAll(restored).count())
        }
        val redirect = "https://www.google.com/goto?url=CAESfixtureToken12345"
        assertEquals(redirect, GoogleDorksPlanner.scopedUrl(redirect, "linkedin"))
    }
    @Test fun `all required terms use content scope and English aliases rather than forcing English into the title`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java Developer"),
            keywords = listOf("Inglês", "SQL", "Excel", "Python", "Word")), CandidateProfile(), dictionary = emptyMap())
        tasks.forEach { task ->
            assertTrue(task.query.contains("(Inglês OR English)"))
            listOf("SQL", "Excel", "Python", "Word").forEach { assertTrue(task.query.contains(it)) }
            assertFalse(task.query.contains("intitle:Inglês"))
            assertFalse(task.query.contains("intitle:English"))
        }
    }
    @Test fun `long required lists and values are sent intact rather than rejected or truncated`() {
        val terms = List(30) { "requirement$it" + "x".repeat(100) }
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java"), keywords = terms), CandidateProfile())
        assertTrue(tasks.all { it.query.length > 980 })
        tasks.forEach { task -> terms.forEach { assertTrue(task.query.contains(it)) } }
    }
    @Test fun `all locations exclusions company contract and selected modes reach every dork`() {
        val cities = listOf("Recife", "Campinas", "Curitiba", "Salvador", "Manaus", "Fortaleza")
        val exclusions = List(12) { "Excluded company $it" }
        val aliases = List(40) { "Technology alias $it" }
        val query = SearchQuery(titles = listOf("Java"), locations = cities + "Brasil", excludedCompanies = exclusions,
            excludedKeywords = listOf("Exclude this phrase"), company = "Desired company", employment = "PJ", workplaces = setOf(Workplace.REMOTE),
            seniorities = setOf(Seniority.JUNIOR), postedWithinDays = 7)
        val tasks = GoogleDorksPlanner.plan(query, CandidateProfile(), dictionary = mapOf("Java" to aliases))
        tasks.forEach { task ->
            cities.forEach { assertTrue(task.query.contains(it)) }
            exclusions.forEach { assertTrue(task.query.contains("-\"$it\"")) }
            aliases.forEach { assertTrue(task.query.contains("\"$it\"")) }
            listOf("Desired company", "Exclude this phrase", "pessoa juridica").forEach { assertTrue(it, task.query.contains("\"$it\"")) }
            listOf("pj", "remoto", "junior").forEach { assertTrue(it, task.query.contains(it)) }
            assertTrue(task.query.contains("after:"))
        }
    }
    @Test fun `excluding dorks from discovery methods prevents Google searches`() {
        assertTrue(GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java"), methods = setOf(DiscoveryMethod.API)), CandidateProfile()).isEmpty())
    }
    @Test fun `published opaque redirects are limited to real Google HTTPS host path and token format`() {
        assertTrue(GoogleDorksPlanner.opaqueRedirect("https://www.google.com/goto?url=CAESfixtureToken12345"))
        assertFalse(GoogleDorksPlanner.opaqueRedirect("https://www.google.com.evil.com/goto?url=CAESfixtureToken12345"))
        assertFalse(GoogleDorksPlanner.opaqueRedirect("https://www.google.com/aclk?url=CAESfixtureToken12345"))
        assertFalse(GoogleDorksPlanner.opaqueRedirect("https://www.google.com/goto?url=https://evil.com"))
        assertFalse(GoogleDorksPlanner.opaqueRedirect("https://www.google.com/goto?url=CAESfixtureToken12345&url=CAESsecondToken12345"))
    }
    @Test fun `every Google query stays in configured source families without private profile history`() {
        val profile = CandidateProfile(skills = listOf("PRIVATE SKILL"), experience = "PRIVATE HISTORY", targets = listOf("Kotlin"))
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java Developer")), profile)
        assertEquals(setOf("linkedin", "gupy"), tasks.map { it.board }.toSet())
        assertTrue(tasks.all { it.query.contains("site:") && !it.query.startsWith("-site:") })
        assertTrue(tasks.all { it.query.contains("Brasil") && !it.query.contains("PRIVATE") && !it.query.contains("Kotlin") })
        assertTrue(tasks.all { URI(it.url).host == "www.google.com" && URI(it.url).path == "/search" })
        assertTrue(tasks.all { GoogleDorksPlanner.sameQuery(it.url, it.query) && GoogleDorksPlanner.allowedNavigation(it.url) })
        assertTrue(tasks.all { it.url.contains("hl=pt-BR") && it.url.contains("gl=br") })
        assertTrue(tasks.first().query.startsWith("site:linkedin.com "))
        assertTrue(tasks.any { it.query.startsWith("site:gupy.io ") })
        assertTrue(tasks.none { it.query.contains("inurl:") })
        assertTrue(tasks.first().query.contains("intitle:Java"))
        assertTrue(tasks.filter { it.strategy == DorkStrategy.EXPANDED }.all { it.fallbackFor != null })
    }

    @Test fun `bounded role groups cover all eleven inputs on every site without truncation`() {
        val roles = listOf("desenvolvedor", "software engineer", "data analyst", "data", "engineer",
            "assistente administrativo", "analista administrativo", "analista financeiro", "accountant",
            "Analista de BI Business Intelligence", "Otimização")
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = roles), CandidateProfile())
        listOf("linkedin", "gupy").forEach { board ->
            val primary = tasks.filter { it.board == board && it.strategy == DorkStrategy.TITLE }
            assertEquals(roles.toSet(), primary.flatMap { it.searchRoles }.toSet())
            assertTrue(primary.all { it.searchRoles.size in 1..3 })
        }
        assertTrue(tasks.all { it.query.count { c -> c == '"' } % 2 == 0 })
        assertTrue(tasks.any { normalized(it.query).contains(normalized("Otimização")) })
        assertTrue(tasks.filter { it.strategy == DorkStrategy.EXPANDED }.all { task -> tasks.any { it.id == task.fallbackFor && it.searchRoles == task.searchRoles } })
    }
    @Test fun `groups preserve later positions and primary coverage precedes alternatives`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java", "Kotlin", "SQL", "Python"), locations = listOf("Recife", "Campinas")), CandidateProfile())
        val primary = tasks.takeWhile { it.strategy == DorkStrategy.TITLE && it.board != "careers" }
        listOf("linkedin", "gupy").forEach { board ->
            assertEquals(setOf("Java", "Kotlin", "SQL", "Python"), primary.filter { it.board == board }.flatMap { it.searchRoles }.toSet())
        }
        assertTrue(tasks.any { it.query.contains("Python") })
        assertEquals(tasks.size, tasks.map { it.id }.distinct().size)
    }

    @Test fun `world opt in removes Brazil context but preserves explicit user criteria`() {
        val query = SearchQuery(titles = listOf("C++ Developer"), keywords = listOf("SQL"), worldwide = true,
            company = "Fixture", excludedKeywords = listOf("senior"), excludedCompanies = listOf("Excluded"))
        val task = GoogleDorksPlanner.plan(query, CandidateProfile()).first()
        assertFalse(task.query.contains("Brasil")); assertFalse(task.url.contains("gl=br"))
        assertEquals("C++ Developer", task.role)
        assertTrue(task.query.contains("intitle:C++")); assertTrue(task.query.contains("SQL"))
        assertTrue(task.query.contains("-senior")); assertTrue(task.query.contains("-Excluded"))
        assertTrue(GoogleDorksPlanner.sameQuery(task.url, task.query))
    }

    @Test fun `remote exception joins the flat location group in the optimized writing pattern`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java"), locations = listOf("São Paulo"), remoteAnywhere = true), CandidateProfile(experience = "Private"))
        assertTrue(tasks.all { it.query.contains("São Paulo") && it.query.contains("remoto") && !it.query.contains("Private") })
        assertTrue(tasks.all { !it.query.contains("Brasil") })
        assertTrue(GoogleDorksPlanner.plan(SearchQuery(), CandidateProfile(skills = listOf("Java"))).isEmpty())
    }

    @Test fun `cleared positions never borrow profile targets or keywords as roles`() {
        val profile = CandidateProfile(targets = listOf("data analyst", "data engineer"), skills = listOf("Kotlin"))
        assertTrue(GoogleDorksPlanner.plan(SearchQuery(), profile).isEmpty())
        assertTrue(GoogleDorksPlanner.plan(SearchQuery(keywords = listOf("java")), profile).isEmpty())
        val cities = GoogleDorksPlanner.plan(SearchQuery(locations = listOf("São Paulo")), profile)
        assertTrue(cities.isEmpty())
    }

    @Test fun `publication discovery date operator does not apply to first seen window`() {
        val now = Instant.parse("2026-10-25T15:00:00Z").toEpochMilli()
        val query = SearchQuery(titles = listOf("Java"), postedWithinDays = 7, includeUnknownDates = false)
        assertTrue(GoogleDorksPlanner.plan(query, CandidateProfile(), now).all { it.query.contains("after:10-18-2026") })
        assertTrue(GoogleDorksPlanner.plan(query.copy(dateBasis = DateBasis.DISCOVERY), CandidateProfile(), now).none { it.query.contains("after:") })
        assertTrue(GoogleDorksPlanner.plan(query.copy(includeUnknownDates = true), CandidateProfile(), now).all { it.query.contains("after:10-18-2026") })
        assertTrue(GoogleDorksPlanner.plan(query.copy(postedWithinDays = 1), CandidateProfile(), now).all { it.query.contains("after:10-24-2026") })
        val crowded = query.copy(includeUnknownDates = true, keywords = List(8) { "x".repeat(90) }, excludedKeywords = List(8) { "y".repeat(90) })
        assertTrue(GoogleDorksPlanner.plan(crowded, CandidateProfile(), now).all { it.query.contains("after:10-18-2026") })
    }

    @Test fun `default remote exception never broadens onsite or hybrid only searches`() {
        listOf(Workplace.ONSITE, Workplace.HYBRID).forEach { mode ->
            val query = SearchQuery(titles = listOf("Java"), locations = listOf("Recife"), workplaces = setOf(mode))
            assertTrue(query.remoteAnywhere)
            assertTrue(GoogleDorksPlanner.plan(query, CandidateProfile()).all { it.query.contains("Recife") })
            assertTrue(DorksPlanner.queries("gupy", query, CandidateProfile()).all { it.contains("\"Recife\"") })
        }
    }

    @Test fun `city alternatives apply to every role rather than pairing a single city per role`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java Developer", "Data Analyst"), locations = listOf("Recife", "Campinas"), remoteAnywhere = false), CandidateProfile())
        assertTrue(tasks.all { it.query.contains("(Recife OR Campinas)") })
        assertTrue(tasks.first().query.contains("intitle:Java"))
        assertTrue(tasks.first().query.contains(" OR intitle:"))
    }
    @Test fun `literal values cannot introduce a domain or OR operator and long exclusions are not dropped`() {
        val tasks = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Java\" OR site:evil.com"),
            keywords = List(8) { "x".repeat(90) }, excludedKeywords = List(8) { "excluded$it".repeat(12) }), CandidateProfile())
        assertTrue(tasks.isNotEmpty())
        tasks.forEach {
            assertTrue(it.query.contains("excluded7".repeat(12)))
            assertFalse(it.query.contains("site:evil.com"))
            assertTrue(GoogleDorksPlanner.sameQuery(it.url, it.query))
            assertEquals(0, it.query.count { c -> c == '"' } % 2)
        }
    }
    @Test fun `custom job aliases and explicit modality are sent without private history`() {
        val task = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("Programador Java"), workplaces = setOf(Workplace.REMOTE)),
            CandidateProfile(experience = "PRIVATE", skills = listOf("PRIVATE SKILL")), dictionary = mapOf("programador" to listOf("coder"))).first()
        assertTrue(task.query.contains("intitle:coder"))
        assertTrue(task.query.contains("remoto OR remote"))
        assertFalse(task.query.contains("PRIVATE"))
    }

    @Test fun `optimized writing pattern follows the user template with every configured filter intact`() {
        val now = Instant.parse("2026-10-28T15:00:00Z").toEpochMilli()
        val query = SearchQuery(titles = listOf("Data Engineer", "Assistente Administrativo", "Analista Administrativo"),
            locations = listOf("Natal", "Goiânia", "Anápolis"),
            workplaces = setOf(Workplace.REMOTE, Workplace.HYBRID, Workplace.ONSITE),
            seniorities = setOf(Seniority.JUNIOR, Seniority.MID), postedWithinDays = 2)
        val task = GoogleDorksPlanner.plan(query, CandidateProfile(), now).first()
        assertEquals("site:linkedin.com (intitle:data OR intitle:dados OR intitle:engineer OR intitle:engenheiro OR intitle:engenheira OR intitle:assistente OR intitle:assistant OR intitle:administrativo OR intitle:administrative OR intitle:administrativa OR intitle:analista OR intitle:analyst) (Natal OR Goiânia OR Anápolis OR remoto OR remote OR \"home office\" OR híbrido OR hybrid OR presencial OR onsite) (junior OR júnior OR jr OR pleno) -intitle:\"banco de talentos\" -intitle:\"cadastro reserva\" -intitle:\"talent pool\" after:10-26-2026", task.query)
        assertTrue(task.query.endsWith("after:10-26-2026"))
        assertTrue(task.query.startsWith("site:linkedin.com "))
        assertTrue(task.url.contains("as_sitesearch=linkedin.com"))
        assertEquals(task.query, GoogleDorksPlanner.queryText(task.url))
    }

    @Test fun `browser allows Google search and human challenge but rejects external and ad navigation`() {
        listOf("https://www.google.com/search?q=Java", "https://www.google.com.br/search?q=Java", "https://www.google.com/sorry/index?continue=search",
            "https://consent.google.com/m?continue=search", "https://www.google.com/recaptcha/api2/anchor").forEach { assertTrue(it, GoogleDorksPlanner.allowedNavigation(it)) }
        listOf("http://www.google.com/search?q=Java", "https://google.com.evil.com/search?q=Java", "https://user:pass@google.com/search?q=Java",
            "https://www.google.com:444/search?q=Java", "https://www.google.com/search", "https://www.google.com/aclk?adurl=job",
            "https://example.com/search?q=Java", "file:///search?q=Java", "javascript:alert(1)").forEach { assertFalse(it, GoogleDorksPlanner.allowedNavigation(it)) }
    }

    @Test fun `query identity survives URL encoding without treating another query as this task`() {
        val task = GoogleDorksPlanner.plan(SearchQuery(titles = listOf("C++"), locations = listOf("São Paulo")), CandidateProfile()).first()
        assertTrue(GoogleDorksPlanner.sameQuery(task.url, task.query))
        assertFalse(GoogleDorksPlanner.sameQuery("https://www.google.com/search?q=Other", task.query))
    }
}

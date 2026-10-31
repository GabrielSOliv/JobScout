package com.jobscout.feature.search.data

import com.jobscout.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HtmlCrawlerTest {
    @Test fun `vagas cards retain actual title company original link and city without tooltip noise`() {
        val html = """<ul><li class="vaga"><h2><a class="link-detalhes-vaga" title="Java Developer" href="/vagas/v123/java?utm_source=test">Ver vaga</a></h2><span class="emprVaga">Fixture</span><div class="detalhes">Java SQL</div><div class="vaga-local">São Paulo / SP <div class="tooltip-place">Também aceitamos Campinas</div></div><figure class="logoEmpresa"><img src="https://example.com/logo.png"></figure></li></ul>"""
        val job = HtmlJobPages.listing(html, "https://www.vagas.com.br/vagas-de-java", SourceConfig("web", "vagas", "Vagas"), 1234).single()
        assertEquals("Java Developer", job.title)
        assertEquals("Fixture", job.company)
        assertEquals("São Paulo / SP · Brasil", job.location)
        assertEquals("https://www.vagas.com.br/vagas/v123/java", job.url)
        assertEquals(1234L, job.collectedAt)
        assertFalse(job.descriptionComplete)
        assertNotNull(job.logoUrl)
    }
    @Test fun `empregos SSR cards map only posting URLs and expose remote policy`() {
        val html = """<div id="job-card"><h2>Analista Java Remoto</h2><h3><a href="/empresa/fixture">Fixture</a></h3><h3 title="Porto Alegre, RS">Porto Alegre, RS</h3><div style="display:none">irrelevant modal</div><a href="/vaga/123/java">Candidatar</a></div>"""
        val job = HtmlJobPages.listing(html, "https://www.empregos.com.br/vagas/java", SourceConfig("web", "empregos", "Empregos"), 1).single()
        assertEquals("Fixture", job.company)
        assertEquals(Workplace.REMOTE, job.workplace)
        assertFalse(job.description.contains("irrelevant modal"))
        assertTrue(job.location.contains("Brasil"))
    }
    @Test fun `python discovery and native detail work without JSON LD or feeds`() {
        val html = """<ol class="list-recent-jobs"><li><span class="listing-company-name"><a href="/jobs/123/">Python Developer</a> Fixture</span><span class="listing-location">Remote, Brazil</span><time datetime="2026-10-20"></time></li></ol>"""
        val card = HtmlJobPages.listing(html, "https://www.python.org/jobs/", SourceConfig("web", "python", "Python"), 1).single()
        val job = HtmlJobPages.detail("<div class='job-description'><p>Python SQL</p><script>bad()</script></div>", card, 22)
        assertEquals("Fixture", job.company)
        assertTrue(job.description.contains("Python SQL"))
        assertFalse(job.description.contains("bad()"))
        assertEquals(card.id, job.id)
        assertEquals(22L, job.collectedAt)
        assertTrue(job.descriptionComplete)
        assertEquals(VerificationState.PAGE_READ, job.verification.state)
        assertEquals(22L, job.verification.checkedAt)
    }
    @Test fun `ordinary pages cannot become completed job details`() {
        val card = Job("1", "web:custom", "1", "Java", "Fixture", "https://example.com/jobs/1")
        assertTrue(runCatching { HtmlJobPages.detail("<h1>News article</h1>", card, 1) }.isFailure)
    }
    @Test fun `cache retains original collection time expires and clears only its own pages`() {
        val directory = Files.createTempDirectory("jobscout-html-test").toFile()
        try {
            val cache = HtmlPageCache(directory)
            cache.write("https://example.com/jobs/1", "<h1>Snapshot</h1>", 1_000)
            val page = cache.read("https://example.com/jobs/1", 2_000)!!
            assertEquals(1_000L, page.fetchedAt)
            assertTrue(page.cached)
            assertNull(cache.read("https://example.com/jobs/1", 1_802_000))
            cache.clear()
            assertNull(cache.read("https://example.com/jobs/1", 2_000))
        } finally { directory.delete() }
    }
    @Test fun `transient HTML failure retries but rate limit never loops`() = runTest {
        var attempts = 0
        assertEquals("ok", retryPublic { attempts++; if (attempts == 1) throw PublicFetchException(503); "ok" })
        assertEquals(2, attempts)
        attempts = 0
        assertTrue(runCatching { retryPublic { attempts++; throw PublicFetchException(429) } }.isFailure)
        assertEquals(1, attempts)
    }
    @Test fun `pagination resolves relative link preserving identity query`() {
        assertEquals("https://example.com/jobs?page=2", HtmlJobPages.next("<a rel='next' href='?page=2'>Next</a>", "https://example.com/jobs"))
    }
}

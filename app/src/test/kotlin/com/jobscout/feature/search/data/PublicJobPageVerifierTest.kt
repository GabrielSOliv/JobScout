package com.jobscout.feature.search.data

import com.jobscout.domain.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class PublicJobPageVerifierTest {
    private val preview = Job("saved-id", "google:gupy", "source-id", "Java Developer", "Empresa não informada",
        "https://fixture.example/jobs/123", description = "Indexed snippet", descriptionComplete = false,
        discoveryOrigins = listOf(DiscoveryOrigin(DiscoveryMethod.DORKS, "google:gupy", "Google", 1, 2, "site:gupy.io Java")))

    private fun page(validThrough: String? = null, extra: String = "") = """<html><head><script type="application/ld+json">{
        "@type":"JobPosting","title":"Java Developer","description":"<p>Full original description with SQL</p>",
        "hiringOrganization":{"name":"Published Company"},"jobLocation":{"address":{"addressLocality":"São Paulo","addressCountry":"BR"}}
        ${validThrough?.let { ",\"validThrough\":\"$it\"" }.orEmpty()}
        }</script></head><body>$extra</body></html>"""

    private suspend fun verify(response: MockResponse, robots: String = "User-agent: *\nAllow: /\n"): Pair<Job, Int> {
        val server = MockWebServer()
        server.start()
        return try {
            server.enqueue(MockResponse().setBody(robots))
            server.enqueue(response)
            // Test-only transport maps HTTPS public fixture URLs to the local server.
            // Production still uses the app's normal TLS/DNS/no-redirect client.
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                val original = chain.request()
                val target = server.url(original.url.encodedPath + original.url.encodedQuery?.let { "?$it" }.orEmpty())
                chain.proceed(original.newBuilder().url(target).build())
            }.build()
            PublicJobPageVerifier(client, RobotsGuard(client)).verify(preview) to server.requestCount
        } finally { server.shutdown() }
    }

    @Test fun `original jobposting confirms page content without losing discovery evidence`() = runTest {
        val (job, requests) = verify(MockResponse().setBody(page()))
        assertEquals(2, requests)
        assertEquals(VerificationState.PAGE_READ, job.verification.state)
        assertNotNull(job.verification.checkedAt)
        assertEquals(preview.id, job.id); assertEquals(preview.source, job.source); assertEquals(preview.sourceId, job.sourceId)
        assertEquals(preview.url, job.url); assertEquals(preview.firstSeen, job.firstSeen)
        assertEquals(preview.discoveryOrigins, job.discoveryOrigins)
        assertEquals("Published Company", job.company)
        assertTrue(job.description.contains("Full original description")); assertTrue(job.descriptionComplete)
        assertNull(job.postedAt)
    }

    @Test fun `embedded application captcha does not hide readable jobposting content`() = runTest {
        val (job, _) = verify(MockResponse().setBody(page(extra = "<div class='g-recaptcha' data-sitekey='fixture'></div>")))
        assertEquals(VerificationState.PAGE_READ, job.verification.state)
    }

    @Test fun `expired source deadline is not reported as an active verified page`() = runTest {
        val (job, _) = verify(MockResponse().setBody(page(validThrough = "2020-01-01T00:00:00Z")))
        assertEquals(VerificationState.EXPIRED, job.verification.state)
        assertNotNull(job.verification.validThrough)
        assertTrue(job.verification.validThrough!! < job.verification.checkedAt!!)
    }

    @Test fun `404 records unavailable at this check and preserves earlier listing text`() = runTest {
        val (job, requests) = verify(MockResponse().setResponseCode(404))
        assertEquals(2, requests)
        assertEquals(VerificationState.UNAVAILABLE, job.verification.state)
        assertTrue(job.verification.reason.orEmpty().contains("404"))
        assertEquals(preview.description, job.description)
        assertEquals(preview.discoveryOrigins, job.discoveryOrigins)
    }

    @Test fun `robots refusal never requests the original page`() = runTest {
        val (job, requests) = verify(MockResponse().setBody(page()), robots = "User-agent: *\nDisallow: /jobs/\n")
        assertEquals(1, requests)
        assertEquals(VerificationState.RESTRICTED, job.verification.state)
    }

    @Test fun `captcha is restricted and ordinary article is not a confirmed vacancy`() = runTest {
        val (challenge, _) = verify(MockResponse().setBody("<title>Just a moment</title><div id='cf-challenge-running'>Check</div>"))
        assertEquals(VerificationState.RESTRICTED, challenge.verification.state)
        val (article, _) = verify(MockResponse().setBody("<html><h1>Java news article</h1><p>Read our company news.</p></html>"))
        assertEquals(VerificationState.NOT_A_JOB, article.verification.state)
        assertEquals(preview.description, article.description)
    }

    @Test fun `redirect refusal is not followed or treated as page read`() = runTest {
        val (job, requests) = verify(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/jobs/123"))
        assertEquals(2, requests)
        assertEquals(VerificationState.RESTRICTED, job.verification.state)
    }

    @Test fun `TLS failure remains unverified without weakening certificate validation`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw SSLHandshakeException("fixture certificate failure") }.build()
        val job = PublicJobPageVerifier(client, RobotsGuard(client)).verify(preview)
        assertEquals(VerificationState.UNVERIFIED, job.verification.state)
        assertEquals(preview.description, job.description)
    }

    @Test fun `date only validThrough covers that date and missing publication date is not invented`() {
        val expiry = java.time.LocalDate.of(2026, 10, 25).plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() - 1
        val active = JobPostingParser.parse(page(validThrough = "2026-10-25"), preview.url, expiry)
        assertEquals(VerificationState.PAGE_READ, active.verification.state)
        assertEquals(expiry, active.verification.validThrough)
        assertNull(active.postedAt)
        val expired = JobPostingParser.parse(page(validThrough = "2026-10-25"), preview.url, expiry + 1)
        assertEquals(VerificationState.EXPIRED, expired.verification.state)
    }
}

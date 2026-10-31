package com.jobscout.feature.search.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jobscout.domain.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SourcesTest {
    private val gson = Gson()
    private fun retrofit(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
        .client(OkHttpClient()).addConverterFactory(GsonConverterFactory.create(gson)).build()

    @Test fun `greenhouse maps original url sanitizes html and does not misuse updated date`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"jobs":[{"id":12,"title":"Junior Java Developer","absolute_url":"https://jobs.example.com/jobs/12?utm_source=ad","location":{"name":"Brazil"},"updated_at":"2026-01-01T00:00:00Z","content":"&lt;p&gt;Java SQL&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;"}]}"""))
            val result = GreenhouseSource(retrofit(server).create(GreenhouseApi::class.java)).search(SourceConfig("greenhouse", "fixture", "Fixture"), SearchQuery())
            assertNull(result.error)
            val job = result.jobs.single()
            assertEquals("https://jobs.example.com/jobs/12", job.url)
            assertFalse(job.description.contains("alert"))
            assertNull(job.postedAt)
            assertNull(job.salaryMin)
            assertEquals(Workplace.UNKNOWN, job.workplace)
            assertEquals("/v1/boards/fixture/jobs?content=true", server.takeRequest().path)
        }
    }
    @Test fun `lever uses hosted posting not a redirect and supports unknown fields`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"abc","text":"Engineer","hostedUrl":"https://jobs.lever.co/fixture/abc?lever-source=ads","categories":{"location":"São Paulo","commitment":"Full-time"},"workplaceType":"remote","descriptionPlain":"SQL Developer"}]"""))
            val result = LeverSource(retrofit(server).create(LeverApi::class.java)).search(SourceConfig("lever", "fixture", "Fixture"), SearchQuery())
            assertNull(result.error)
            assertEquals("https://jobs.lever.co/fixture/abc", result.jobs.single().url)
            assertEquals(Workplace.REMOTE, result.jobs.single().workplace)
            assertNull(result.jobs.single().postedAt)
        }
    }
    @Test fun `source access errors are surfaced without fabricated jobs`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val source = GreenhouseSource(retrofit(server).create(GreenhouseApi::class.java))
            val result = source.search(SourceConfig("greenhouse", "fixture", "Fixture"), SearchQuery())
            assertTrue(result.jobs.isEmpty())
            assertTrue(result.error.orEmpty().contains("permitido"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `mock connector is deterministic and isolated from production sources`() = runTest {
        val source: JobSource = object : JobSource {
            override val kind = "mock"
            override val displayName = "Test fixture only"
            override val capabilities = SourceCapabilities(emptySet(), emptySet(), false)
            override suspend fun search(config: SourceConfig, query: SearchQuery) = SourceResult(config.key,
                listOf(Job("test", config.key, "1", "Java Developer", "Fixture", "https://example.com/jobs/1")))
        }
        assertEquals(source.search(SourceConfig("mock", "test", "Fixture"), SearchQuery()).jobs.map { it.id },
            source.search(SourceConfig("mock", "test", "Fixture"), SearchQuery()).jobs.map { it.id })
    }
}

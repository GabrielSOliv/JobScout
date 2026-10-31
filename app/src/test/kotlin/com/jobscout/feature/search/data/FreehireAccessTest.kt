package com.jobscout.feature.search.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jobscout.core.database.TokenCipher
import com.jobscout.domain.SearchQuery
import com.jobscout.domain.SourceConfig
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class FreehireAccessTest {
    @Test fun `missing credential makes no network request`() = runTest {
        var calls = 0
        val api = object : FreehireApi {
            override suspend fun search(query: String?, countries: String?, limit: Int, offset: Int, authorization: String): JsonObject {
                calls++; return JsonParser.parseString("""{"data":[],"meta":{"total":0}}""").asJsonObject
            }
        }
        val result = FreehireSource(api, FreehireTokenProvider { null }).search(SourceConfig("freehire", "global", "Fixture"), SearchQuery())
        assertNotNull(result.error); assertTrue(result.jobs.isEmpty()); assertEquals(0, calls)
    }

    @Test fun `personal bearer is sent only by Freehire API and errors do not echo it`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val retrofit = Retrofit.Builder().baseUrl(server.url("/"))
                .client(OkHttpClient.Builder().followRedirects(false).build())
                .addConverterFactory(GsonConverterFactory.create(Gson())).build()
            val token = "fixture-local-token"
            server.enqueue(MockResponse().setResponseCode(401).setBody(token))
            val result = FreehireSource(retrofit.create(FreehireApi::class.java), FreehireTokenProvider { token })
                .search(SourceConfig("freehire", "global", "Fixture"), SearchQuery())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertTrue(request.path!!.startsWith("/api/v1/jobs/search"))
            assertFalse(result.error.orEmpty().contains(token))
            server.enqueue(MockResponse().setBody("""{"jobs":[]}"""))
            retrofit.create(GreenhouseApi::class.java).jobs("fixture", content = false)
            assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }

    @Test fun `credential encryption is randomized and rejects tampering or another key`() {
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = generator.generateKey()
        val token = "fixture-local-token"
        val first = TokenCipher.encrypt(token, key)
        val second = TokenCipher.encrypt(token, key)
        assertNotEquals(first, second); assertFalse(first.contains(token))
        assertEquals(token, TokenCipher.decrypt(first, key))
        val parts = first.split(':').toMutableList()
        val bytes = java.util.Base64.getDecoder().decode(parts[2])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        parts[2] = java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { TokenCipher.decrypt(parts.joinToString(":"), key) }.isFailure)
        assertTrue(runCatching { TokenCipher.decrypt(first, generator.generateKey()) }.isFailure)
    }

    @Test fun `header injection and pasted bearer are rejected without echoing input`() {
        for (value in listOf("x\r\nAuthorization:y", "Bearer fixture", "", "a".repeat(2049))) {
            assertTrue(runCatching { FreehireKeyInput.normalize(value) }.isFailure)
        }
    }
}

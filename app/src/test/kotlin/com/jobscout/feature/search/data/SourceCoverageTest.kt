package com.jobscout.feature.search.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jobscout.domain.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SourceCoverageTest {
    @Test fun `prefixed Atom entries keep their alternate link and last item`() {
        val client = OkHttpClient()
        val xml = "<a:feed xmlns:a='http://www.w3.org/2005/Atom'>" + (1..302).joinToString("") {
            "<a:entry><a:title>Java $it</a:title><a:link rel='self' href='https://example.com/api/$it'/><a:link rel='alternate' href='https://example.com/jobs/$it'/></a:entry>"
        } + "</a:feed>"
        val jobs = RssSource(client, RobotsGuard(client)).parse(xml, SourceConfig("rss", "fixture", "Fixture"))
        assertEquals(302, jobs.size); assertEquals("https://example.com/jobs/302", jobs.last().url)
    }
    @Test fun `freehire reads beyond 180 and honors short server pages until total`() = runTest {
        val offsets = mutableListOf<Int>()
        val api = object : FreehireApi {
            override suspend fun search(query: String?, countries: String?, limit: Int, offset: Int, authorization: String): JsonObject {
                offsets += offset
                val data = JsonArray()
                for (id in offset until minOf(offset + 40, 241)) data.add(JsonParser.parseString("""{"public_slug":"$id","title":"Java","url":"https://example.com/jobs/$id"}"""))
                return JsonObject().apply { add("data", data); add("meta", JsonObject().apply { addProperty("total", 241) }) }
            }
        }
        val progress = mutableListOf<Int>()
        val result = FreehireSource(api, FreehireTokenProvider { "fixture-local-token" }).search(SourceConfig("freehire", "global", "Fixture"), SearchQuery(), CandidateProfile()) { progress += it.fetched }
        assertNull(result.error); assertEquals(241, result.jobs.size)
        assertEquals(listOf(0, 40, 80, 120, 160, 200, 240), offsets)
        assertEquals(241, progress.last()); assertEquals(7, result.pagesRead)
    }

    @Test fun `freehire visits every role without an artificial five term OR query`() = runTest {
        val requested = mutableListOf<String?>()
        val api = object : FreehireApi {
            override suspend fun search(query: String?, countries: String?, limit: Int, offset: Int, authorization: String): JsonObject {
                requested += query
                return JsonParser.parseString("""{"data":[],"meta":{"total":0}}""").asJsonObject
            }
        }
        val roles = (1..12).map { "Role number $it" }
        FreehireSource(api, FreehireTokenProvider { "fixture-local-token" }).search(SourceConfig("freehire", "global", "Fixture"), SearchQuery(titles = roles))
        assertEquals(roles.map { "\"$it\"" }, requested)
    }

    @Test fun `lever reads beyond 1000 and preserves pages when later access is denied`() = runTest {
        val offsets = mutableListOf<Int>()
        val api = object : LeverApi {
            override suspend fun jobs(board: String, mode: String, skip: Int, limit: Int): JsonArray {
                offsets += skip
                return JsonArray().apply { for (id in skip until minOf(skip + 100, 1201)) add(JsonParser.parseString("""{"id":"$id","text":"Java","hostedUrl":"https://jobs.lever.co/fixture/$id"}""")) }
            }
        }
        val config = SourceConfig("lever", "fixture", "Fixture")
        val full = LeverSource(api).search(config, SearchQuery())
        assertNull(full.error); assertEquals(1201, full.jobs.size)
        assertEquals(1200, offsets.last()); assertEquals(13, full.pagesRead); assertFalse(full.limited)
        val failing = object : LeverApi {
            override suspend fun jobs(board: String, mode: String, skip: Int, limit: Int): JsonArray {
                if (skip > 0) throw PaginationStopped("Fonte bloqueou a próxima página.")
                return api.jobs(board, mode, skip, limit)
            }
        }
        val partial = LeverSource(failing).search(config, SearchQuery())
        assertEquals(100, partial.jobs.size); assertTrue(partial.limited); assertNotNull(partial.error)
    }

    @Test fun `repeated lever page stops without retrying forever or discarding earlier jobs`() = runTest {
        var calls = 0
        val api = object : LeverApi {
            override suspend fun jobs(board: String, mode: String, skip: Int, limit: Int): JsonArray {
                calls++
                return JsonArray().apply { repeat(100) { id -> add(JsonParser.parseString("""{"id":"$id","text":"Java","hostedUrl":"https://jobs.lever.co/fixture/$id"}""")) } }
            }
        }
        val result = LeverSource(api).search(SourceConfig("lever", "fixture", "Fixture"), SearchQuery())
        assertEquals(2, calls); assertEquals(100, result.jobs.size)
        assertTrue(result.limited); assertTrue(result.error.orEmpty().contains("repetiu"))
    }

    @Test fun `greenhouse processes every posting in a response larger than 2000`() = runTest {
        val api = object : GreenhouseApi {
            override suspend fun jobs(board: String, content: Boolean): JsonObject = JsonObject().apply {
                add("jobs", JsonArray().apply { repeat(2003) { id -> add(JsonParser.parseString("""{"id":$id,"title":"Java","absolute_url":"https://example.com/jobs/$id"}""")) } })
            }
        }
        val result = GreenhouseSource(api).search(SourceConfig("greenhouse", "fixture", "Fixture"), SearchQuery())
        assertEquals(2003, result.jobs.size); assertEquals("2002", result.jobs.last().sourceId)
        assertFalse(result.limited)
    }

    @Test fun `rss reads more than 250 items and follows only published feed pagination`() = runTest {
        val urls = mutableListOf<String>()
        fun items(start: Int, end: Int) = (start..end).joinToString("") { "<item><title>Java $it</title><link>https://example.com/jobs/$it</link></item>" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString(); urls += url
            val body = when {
                url.endsWith("robots.txt") -> "User-agent: *\nAllow: /\n"
                url.endsWith("?page=2") -> "<rss><channel>${items(301, 301)}</channel></rss>"
                else -> "<rss><channel><link rel='next' href='?page=2'/>${items(1, 300)}</channel></rss>"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val result = RssSource(client, RobotsGuard(client)).search(SourceConfig("rss", "fixture", "Fixture", endpoint = "https://example.com/feed"), SearchQuery())
        assertNull(result.error); assertEquals(301, result.jobs.size); assertEquals(2, result.pagesRead)
        assertTrue(urls.contains("https://example.com/feed?page=2"))
    }

    @Test fun `HTML reads a third page and hydrates the twelfth job needed by a required keyword`() = runTest {
        val directory = Files.createTempDirectory("coverage-fixture").toFile()
        try {
            val cache = HtmlPageCache(directory)
            val start = "https://www.python.org/jobs/"
            for (page in 1..3) {
                val url = if (page == 1) start else "$start?page=$page"
                val cards = ((page - 1) * 4 + 1..page * 4).joinToString("") { id ->
                    "<li><span class='listing-company-name'><a href='/jobs/$id/'>Python Developer $id</a> Fixture</span><span class='listing-location'>Brasil</span></li>"
                }
                cache.write(url, "<ol class='list-recent-jobs'>$cards</ol>" + if (page < 3) "<a aria-label='Próxima página' href='?page=${page + 1}'>Próxima</a>" else "")
            }
            for (id in 1..12) cache.write("$start$id/", "<div class='job-description'>Python. ${if (id == 12) "English required" else "Descrição da vaga"}</div>")
            val client = OkHttpClient.Builder().addInterceptor { error("Fixture must use its local cache") }.build()
            var emitted = 0
            val result = HtmlCrawlerSource(client, RobotsGuard(client), cache).search(
                SourceConfig("web", "python", "Fixture", endpoint = start),
                SearchQuery(titles = listOf("Python"), keywords = listOf("Inglês")), CandidateProfile()) { emitted += it.jobs.size }
            assertNull(result.error); assertEquals(12, result.jobs.size)
            assertEquals(12, result.detailsRead); assertEquals(15, result.cacheHits)
            assertTrue(result.jobs.all { it.descriptionComplete })
            assertTrue(JobFilter.matches(result.jobs.last(), SearchQuery(titles = listOf("Python"), keywords = listOf("Inglês"))))
            assertTrue(emitted >= 24)
        } finally { directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
}

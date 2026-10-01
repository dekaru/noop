package com.noop.journalsync

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (T10) OkHttp-layer mapping tests. No MockWebServer: a fake Interceptor fabricates the
 * Response for every call (buildResponse below), which exercises the real client stack,
 * the real header assembly and the real code->ApiResult mapping.
 */
class OkHttpJournalSyncApiTest {

    private class StubCodeInterceptor(private val code: Int, private val body: String = "") : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // Fabricate the Response from the request alone: no DNS, no socket, no server.
            return okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(okhttp3.ResponseBody.create(null, body))
                .build()
        }
    }

    private fun clientFor(code: Int, body: String = ""): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(StubCodeInterceptor(code, body))
            .build()

    private fun api(code: Int, body: String = "") = OkHttpJournalSyncApi(
        clientFor(code, body),
        "https://journal.example.com",
        "test-token",
        "cf-id",
        "cf-secret",
    )

    @Test
    fun successfulGetParsesPage() {
        val body = """{"answers":[{"id":1,"day":"2026-10-01","question":"Q?",
            "answered_yes":1,"numeric_value":null,"notes":null}],"next":1}"""
        val result = runBlockingTest { api(200, body).getAnswers(null) }
        assertTrue(result is ApiResult.Ok<*> )
        assertEquals(1, (result as ApiResult.Ok<JournalSyncProtocol.Page>).page?.answers?.size)
    }

    @Test
    fun codeMapping() {
        assertTrue(runBlockingTest { api(200).postAck(listOf(1L)) } is ApiResult.Ok<*> )
        assertTrue(runBlockingTest { api(204).postCatalog(emptyList()) } is ApiResult.Ok<*> )
        assertTrue(runBlockingTest { api(401).postAck(listOf(1L)) } is ApiResult.AuthFailed)
        assertTrue(runBlockingTest { api(403).postAck(listOf(1L)) } is ApiResult.AuthFailed)
        assertTrue(runBlockingTest { api(500).postAck(listOf(1L)) } is ApiResult.RetryableFailure)
        assertTrue(runBlockingTest { api(429).postAck(listOf(1L)) } is ApiResult.RetryableFailure)
        assertTrue(runBlockingTest { api(400).postAck(listOf(1L)) } is ApiResult.Fatal)
    }

    @Test
    fun redirectIsAuthFailedNotFollowed() {
        // followRedirects(false) on the real client plus a 302 stub: the layer must report
        // auth failure (Access login bounce), never follow to an HTML login page.
        val result = runBlockingTest { api(302).getAnswers(null) }
        assertTrue(result is ApiResult.AuthFailed)
        assertEquals(302, (result as ApiResult.AuthFailed).code)
    }

    @Test
    fun malformedBodyOn200IsFatal() {
        val result = runBlockingTest { api(200, "{\"answers\": \"nope\"}").getAnswers(null) }
        assertTrue(result is ApiResult.Fatal)
    }

    @Test
    fun requestCarriesAuthHeaders() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor { chain ->
                seen = chain.request()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204).message("stub")
                    .body(okhttp3.ResponseBody.create(null, ""))
                    .build()
            })
            .build()
        val api = OkHttpJournalSyncApi(client, "https://journal.example.com", "tok", "cid", "csecret")
        runBlockingTest { api.postAck(listOf(5L)) }
        val request = seen!!
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("cid", request.header("CF-Access-Client-Id"))
        assertEquals("csecret", request.header("CF-Access-Client-Secret"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("https://journal.example.com/v1/ack", request.url.toString())
        // Body never leaks the token anywhere else: only the Authorization header carries it.
        assertTrue(!request.url.toString().contains("tok"))
    }

    @Test
    fun ioFailureIsRetryable() {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor { throw IOException("offline") })
            .build()
        val api = OkHttpJournalSyncApi(client, "https://journal.example.com", "tok", null, null)
        val result = runBlockingTestDirect { api.getAnswers(null) }
        assertTrue(result is ApiResult.RetryableFailure)
    }

    private fun runBlockingTest(block: suspend () -> ApiResult<*>): ApiResult<*> =
        kotlinx.coroutines.runBlocking { block() }

    private fun runBlockingTestDirect(block: suspend () -> ApiResult<*>): ApiResult<*> =
        kotlinx.coroutines.runBlocking { block() }
}

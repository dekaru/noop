package com.noop.journalsync

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * HTTP boundary for the journal-sync server (spec §4). Headers carry the device Bearer
 * plus the Cloudflare Access service token; header values are NEVER logged.
 *
 * Redirects are NOT followed: a 302 to the Access login page means "not authenticated"
 * and must surface as [ApiResult.AuthFailed], not as a fetch of an HTML login form.
 */
interface JournalSyncApi {
    suspend fun postCatalog(questions: List<JournalSyncProtocol.CatalogQuestion>): ApiResult<Unit>
    suspend fun getAnswers(after: Long?): ApiResult<JournalSyncProtocol.Page>
    suspend fun getPending(): ApiResult<JournalSyncProtocol.PendingPage>
    suspend fun postAck(ids: List<Long>): ApiResult<Unit>
}

sealed class ApiResult<out T> {
    /** 2xx. For getAnswers the parsed page rides along; discarded-count included. */
    data class Ok<T>(val page: T? = null) : ApiResult<T>()

    /** 401/403, or a redirect (Access login bounce). Credentials are wrong — do not retry. */
    data class AuthFailed(val code: Int) : ApiResult<Nothing>()

    /** 5xx, 429, or an I/O failure. Retry with backoff. */
    data class RetryableFailure(val code: Int?, val message: String?) : ApiResult<Nothing>()

    /** Anything else (4xx other than auth, protocol garbage). Do not retry. */
    data class Fatal(val code: Int?, val message: String?) : ApiResult<Nothing>()
}

class OkHttpJournalSyncApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val cfClientId: String?,
    private val cfClientSecret: String?,
) : JournalSyncApi {

    /** Simple client with fixed 15s timeouts; callers that need to inject a test client pass one in. */
    constructor(
        baseUrl: String,
        token: String,
        cfClientId: String?,
        cfClientSecret: String?,
    ) : this(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
        baseUrl, token, cfClientId, cfClientSecret,
    )

    override suspend fun postCatalog(questions: List<JournalSyncProtocol.CatalogQuestion>): ApiResult<Unit> {
        val response = try {
            execute(
                method = "POST",
                path = "/v1/catalog",
                body = JournalSyncProtocol.encodeCatalog(questions).toRequestBody(JSON_MEDIA),
            )
        } catch (error: IOException) {
            return apiResultFromIo(error)
        }
        return mapBareResponse(response)
    }

    override suspend fun getAnswers(after: Long?): ApiResult<JournalSyncProtocol.Page> {
        val suffix = if (after != null && after > 0) "?after=$after" else ""
        val response = try {
            execute(method = "GET", path = "/v1/answers$suffix", body = null)
        } catch (error: IOException) {
            return apiResultFromIo(error)
        }
        val code = response.code
        val body = runCatching { response.body?.string() }.getOrNull()
        if (response.isSuccessful) {
            if (body == null) return ApiResult.Fatal(code, "empty 2xx body")
            return when (val parsed = JournalSyncProtocol.parseAnswersPage(body)) {
                is JournalSyncProtocol.ParseResult.Ok -> ApiResult.Ok(parsed.page)
                is JournalSyncProtocol.ParseResult.Malformed -> ApiResult.Fatal(code, parsed.reason)
            }
        }
        return mapFailure(code)
    }

    override suspend fun getPending(): ApiResult<JournalSyncProtocol.PendingPage> {
        val response = try {
            execute(method = "GET", path = "/v1/pending", body = null)
        } catch (error: IOException) {
            return apiResultFromIo(error)
        }
        val code = response.code
        val body = runCatching { response.body?.string() }.getOrNull()
        if (response.isSuccessful) {
            if (body == null) return ApiResult.Fatal(code, "empty 2xx body")
            return when (val parsed = JournalSyncProtocol.parsePendingPage(body)) {
                is JournalSyncProtocol.PendingParseResult.Ok -> ApiResult.Ok(parsed.page)
                is JournalSyncProtocol.PendingParseResult.Malformed -> ApiResult.Fatal(code, parsed.reason)
            }
        }
        return mapFailure(code)
    }

    override suspend fun postAck(ids: List<Long>): ApiResult<Unit> {
        val response = try {
            execute(
                method = "POST",
                path = "/v1/ack",
                body = JournalSyncProtocol.encodeAck(ids).toRequestBody(JSON_MEDIA),
            )
        } catch (error: IOException) {
            return apiResultFromIo(error)
        }
        return mapBareResponse(response)
    }

    private suspend fun execute(method: String, path: String, body: RequestBody?): Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
            if (!cfClientId.isNullOrBlank() && !cfClientSecret.isNullOrBlank()) {
                builder.header("CF-Access-Client-Id", cfClientId)
                builder.header("CF-Access-Client-Secret", cfClientSecret)
            }
            when (method) {
                "GET" -> builder.get()
                else -> builder.post(body ?: ByteArray(0).toRequestBody(JSON_MEDIA))
            }
            val call: Call = client.newCall(builder.build())
            call.execute()
        }

    private fun mapBareResponse(response: Response): ApiResult<Unit> {
        val code = response.code
        if (response.isSuccessful) return ApiResult.Ok(null)
        return mapFailure(code)
    }

    private fun mapFailure(code: Int): ApiResult<Nothing> = when {
        code == 401 || code == 403 -> ApiResult.AuthFailed(code)
        code in 300..399 -> ApiResult.AuthFailed(code)
        code >= 500 || code == 429 -> ApiResult.RetryableFailure(code, null)
        else -> ApiResult.Fatal(code, null)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Bridges an OkHttp I/O failure into the retryable bucket at the call site if needed. */
fun apiResultFromIo(exception: IOException): ApiResult.RetryableFailure =
    ApiResult.RetryableFailure(code = null, message = exception.javaClass.simpleName)

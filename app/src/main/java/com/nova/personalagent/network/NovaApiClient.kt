package com.nova.personalagent.network

import com.nova.personalagent.network.models.ChatRequest
import com.nova.personalagent.network.models.ChatResponse
import com.nova.personalagent.network.models.HealthResponse
import com.nova.personalagent.network.models.ToolResultRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Interface defining the API contract for NOVA backend communication.
 * Enables clean dependency injection and testing with fakes/mocks.
 */
interface NovaApi {
    suspend fun getHealth(): NovaApiResult<HealthResponse>
    suspend fun sendChat(request: ChatRequest): NovaApiResult<ChatResponse>
    suspend fun sendToolResult(request: ToolResultRequest): NovaApiResult<ChatResponse>
}

/**
 * ============================================================================
 * NOVA API Client
 * ============================================================================
 * HTTP client connecting the NOVA Android app with the local FastAPI backend.
 *
 * Responsibilities:
 *  - Communicates with GET /health, POST /api/chat, and POST /api/chat/tool-result.
 *  - Dispatches all networking to [ioDispatcher] (default: Dispatchers.IO).
 *  - Configurable timeouts (connect: 10s, read: 30s, write: 10s by default).
 *  - Safe logging: never logs user prompts, conversational text, or sensitive data.
 *  - Robust error handling: maps HTTP status codes and transport errors into [NovaApiResult].
 *  - Independent of Android UI and Compose.
 */
class NovaApiClient(
    baseUrl: String = DEFAULT_BASE_URL,
    connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
    writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS,
    customOkHttpClient: OkHttpClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : NovaApi {

    companion object {
        /** Default development base URL for the Android emulator */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        /** Loopback base URL used with ADB reverse port forwarding (or physical devices) */
        const val LOOPBACK_BASE_URL = "http://127.0.0.1:8000"
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 120L
        const val DEFAULT_WRITE_TIMEOUT_SECONDS = 10L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    private var activeBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    // ── Endpoints ────────────────────────────────────────────────────────────────

    /**
     * Checks the health and operational status of the NOVA backend.
     * GET /health
     */
    override suspend fun getHealth(): NovaApiResult<HealthResponse> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(resolveUrl("health"))
            .get()
            .build()

        executeRequest(request) { body ->
            json.decodeFromString<HealthResponse>(body)
        }
    }

    /**
     * Sends a chat prompt and conversation history to the backend.
     * POST /api/chat
     */

    override suspend fun sendChat(
        request: ChatRequest
    ): NovaApiResult<ChatResponse> = withContext(ioDispatcher) {
        android.util.Log.d("NOVA_NETWORK", "REQUEST START: sendChat called")

        val url = resolveUrl("api/chat")
        android.util.Log.d("NOVA_NETWORK", "Request URL: $url")

        val jsonPayload = json.encodeToString(
            ChatRequest.serializer(),
            request
        )
        val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .post(body)
            .build()

        android.util.Log.d("NOVA_NETWORK", "Executing HTTP request")

        val result = executeRequest(httpRequest) { responseBody ->
            android.util.Log.d("NOVA_NETWORK", "Response received; decoding JSON")
            json.decodeFromString<ChatResponse>(responseBody)
        }


        when (result) {
            is NovaApiResult.Success -> {
                android.util.Log.d(
                    "NOVA_NETWORK",
                    "REQUEST SUCCESS: HTTP ${result.statusCode}"
                )
            }

            is NovaApiResult.NetworkFailure -> {
                android.util.Log.e(
                    "NOVA_NETWORK",
                    "NETWORK FAILURE: ${result.message}",
                    result.error
                )
            }

            is NovaApiResult.HttpError -> {
                android.util.Log.e(
                    "NOVA_NETWORK",
                    "HTTP ERROR ${result.statusCode}: ${result.message}"
                )
            }
        }
        result

    }


    /**
     * Sends the local tool execution result back to the backend.
     * POST /api/chat/tool-result
     */
    override suspend fun sendToolResult(request: ToolResultRequest): NovaApiResult<ChatResponse> = withContext(ioDispatcher) {
        val jsonPayload = json.encodeToString(ToolResultRequest.serializer(), request)
        val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(resolveUrl("api/chat/tool-result"))
            .post(body)
            .build()

        executeRequest(httpRequest) { responseBody ->
            json.decodeFromString<ChatResponse>(responseBody)
        }
    }

    // ── Internal Helpers ────────────────────────────────────────────────────────

    private fun resolveUrl(endpoint: String): String {
        val cleanEndpoint = endpoint.removePrefix("/")
        return "$activeBaseUrl$cleanEndpoint"
    }

    private inline fun <T> executeRequest(
        request: Request,
        parser: (String) -> T
    ): NovaApiResult<T> {
        return try {


            client.newCall(request).execute().use { response ->
                android.util.Log.d(
                    "NOVA_NETWORK",
                    "HTTP RESPONSE: ${response.code}"
                )
                handleResponse(response, parser)
            }


        } catch (e: SocketTimeoutException) {
            if (activeBaseUrl.contains("10.0.2.2")) {
                val fallbackBaseUrl = activeBaseUrl.replace("10.0.2.2", "127.0.0.1")
                val fallbackUrl = request.url.toString().replace("10.0.2.2", "127.0.0.1")
                android.util.Log.w(
                    "NOVA_NETWORK",
                    "Socket timeout on 10.0.2.2; attempting fallback to ADB reverse: $fallbackUrl"
                )
                try {
                    val fallbackRequest = request.newBuilder().url(fallbackUrl).build()
                    val result = client.newCall(fallbackRequest).execute().use { response ->
                        activeBaseUrl = fallbackBaseUrl
                        android.util.Log.i("NOVA_NETWORK", "Switched activeBaseUrl to $activeBaseUrl")
                        handleResponse(response, parser)
                    }
                    return result
                } catch (fallbackError: Exception) {
                    android.util.Log.e("NOVA_NETWORK", "Fallback to $fallbackUrl failed: ${fallbackError.message}")
                }
            }
            android.util.Log.e(
                "NOVA_NETWORK",
                "SOCKET TIMEOUT: ${request.method} ${request.url}",
                e
            )
            NovaApiResult.NetworkFailure(
                error = e,
                message = "Request timed out while communicating with NOVA backend."
            )
        }
        catch (e: UnknownHostException) {
            NovaApiResult.NetworkFailure(
                error = e,
                message = "Could not resolve backend host. Verify server URL: $activeBaseUrl"
            )
        } catch (e: IOException) {
            if (activeBaseUrl.contains("10.0.2.2")) {
                val fallbackBaseUrl = activeBaseUrl.replace("10.0.2.2", "127.0.0.1")
                val fallbackUrl = request.url.toString().replace("10.0.2.2", "127.0.0.1")
                android.util.Log.w(
                    "NOVA_NETWORK",
                    "Network error on 10.0.2.2 (${e.message}); attempting fallback to ADB reverse: $fallbackUrl"
                )
                try {
                    val fallbackRequest = request.newBuilder().url(fallbackUrl).build()
                    val result = client.newCall(fallbackRequest).execute().use { response ->
                        activeBaseUrl = fallbackBaseUrl
                        android.util.Log.i("NOVA_NETWORK", "Switched activeBaseUrl to $activeBaseUrl")
                        handleResponse(response, parser)
                    }
                    return result
                } catch (fallbackError: Exception) {
                    android.util.Log.e("NOVA_NETWORK", "Fallback to $fallbackUrl failed: ${fallbackError.message}")
                }
            }
            NovaApiResult.NetworkFailure(
                error = e,
                message = "Network error connecting to backend: ${e.message ?: "Connection failed"}"
            )
        } catch (e: SerializationException) {
            NovaApiResult.NetworkFailure(
                error = e,
                message = "Failed to parse backend response: ${e.message}"
            )
        } catch (e: Exception) {
            NovaApiResult.NetworkFailure(
                error = e,
                message = "Unexpected networking error: ${e.message}"
            )
        }
    }

    private inline fun <T> handleResponse(
        response: Response,
        parser: (String) -> T
    ): NovaApiResult<T> {
        val code = response.code
        val rawBody = response.body?.string().orEmpty()

        if (response.isSuccessful) {
            return try {
                val parsed = parser(rawBody)
                NovaApiResult.Success(data = parsed, statusCode = code)
            } catch (e: SerializationException) {
                NovaApiResult.NetworkFailure(
                    error = e,
                    message = "Failed to parse successful response (HTTP $code): ${e.message}"
                )
            }
        }

        // Handle HTTP error (4xx/5xx)
        val detailMessage = extractFastApiErrorDetail(rawBody, code)
        return NovaApiResult.HttpError(
            statusCode = code,
            message = detailMessage,
            rawBody = rawBody
        )
    }

    /**
     * Extracts human-readable error messages from FastAPI's error format:
     * e.g. {"detail": "..."} or {"detail": [{"msg": "..."}]}
     */
    private fun extractFastApiErrorDetail(rawBody: String, statusCode: Int): String {
        if (rawBody.isBlank()) {
            return defaultMessageForStatus(statusCode)
        }

        return try {
            val jsonElement = json.parseToJsonElement(rawBody)
            if (jsonElement is JsonObject) {
                when (val detail = jsonElement["detail"]) {
                    is JsonObject -> detail.toString()
                    is JsonArray -> {
                        val messages = detail.mapNotNull { item ->
                            if (item is JsonObject) {
                                item["msg"]?.jsonPrimitive?.content
                            } else null
                        }
                        if (messages.isNotEmpty()) messages.joinToString("; ") else rawBody
                    }
                    else -> detail?.jsonPrimitive?.content ?: defaultMessageForStatus(statusCode)
                }
            } else {
                defaultMessageForStatus(statusCode)
            }
        } catch (_: Exception) {
            defaultMessageForStatus(statusCode)
        }
    }

    private fun defaultMessageForStatus(code: Int): String = when (code) {
        422 -> "Validation error: invalid request payload or empty prompt."
        502 -> "Bad gateway: AI model error or invalid tool parameters."
        503 -> "Service unavailable: Gemini API key is not configured on the backend."
        500 -> "Internal backend server error."
        else -> "HTTP error $code"
    }
}

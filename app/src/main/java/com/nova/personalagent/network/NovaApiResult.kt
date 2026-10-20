package com.nova.personalagent.network

/**
 * ============================================================================
 * Strongly-Typed Network Result
 * ============================================================================
 * Distinguishes successful responses, HTTP status errors (4xx/5xx), and
 * network/parsing failures without throwing uncaught exceptions to callers.
 */
sealed interface NovaApiResult<out T> {

    /**
     * Successful response with HTTP 200 and parsed response body.
     */
    data class Success<T>(
        val data: T,
        val statusCode: Int = 200
    ) : NovaApiResult<T>

    /**
     * Non-2xx HTTP status code returned by the server (e.g. 422, 502, 503, 500).
     *
     * @param statusCode The HTTP status code.
     * @param message    Human-readable detail extracted from FastAPI error payload.
     * @param rawBody    Raw response body for debugging or diagnostics.
     */
    data class HttpError(
        val statusCode: Int,
        val message: String,
        val rawBody: String? = null
    ) : NovaApiResult<Nothing>

    /**
     * Client-side failure before a valid HTTP response could be received
     * (e.g. timeout, DNS/host resolution, network connection drop, malformed JSON).
     */
    data class NetworkFailure(
        val error: Throwable,
        val message: String
    ) : NovaApiResult<Nothing>

    /**
     * Returns true if this result represents a successful call.
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Returns the payload data if [Success], or null otherwise.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }
}

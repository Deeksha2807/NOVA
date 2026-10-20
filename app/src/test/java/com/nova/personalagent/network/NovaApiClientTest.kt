package com.nova.personalagent.network

import com.nova.personalagent.network.models.ChatMessageDto
import com.nova.personalagent.network.models.ChatRequest
import com.nova.personalagent.network.models.ToolCallPayload
import com.nova.personalagent.network.models.ToolResultPayloads
import com.nova.personalagent.network.models.ToolResultRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * Unit Tests: NovaApiClient
 * ============================================================================
 * Runs entirely on the JVM using OkHttp's MockWebServer.
 * No live backend, Android device, or Gemini API key required.
 */
class NovaApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: NovaApiClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString()
        apiClient = NovaApiClient(
            baseUrl = baseUrl,
            connectTimeoutSeconds = 2,
            readTimeoutSeconds = 2,
            writeTimeoutSeconds = 2,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // ── 1. Health Response Parsing ───────────────────────────────────────────────

    @Test
    fun getHealth_parsesSuccessResponseCorrectly() = runBlocking {
        val mockJson = """
            {
                "status": "ok",
                "gemini_configured": true,
                "model": "gemini-2.5-flash",
                "version": "1.0.0"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val result = apiClient.getHealth()

        assertTrue("Result should be Success", result is NovaApiResult.Success)
        val data = (result as NovaApiResult.Success).data
        assertEquals("ok", data.status)
        assertTrue(data.geminiConfigured)
        assertEquals("gemini-2.5-flash", data.model)
        assertEquals("1.0.0", data.version)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("/health", recordedRequest.path)
    }

    @Test
    fun getHealth_parsesDegradedStatus() = runBlocking {
        val mockJson = """
            {
                "status": "degraded",
                "gemini_configured": false,
                "model": "gemini-2.5-flash",
                "version": "1.0.0"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val result = apiClient.getHealth()
        assertTrue(result is NovaApiResult.Success)
        val data = (result as NovaApiResult.Success).data
        assertEquals("degraded", data.status)
        assertFalse(data.geminiConfigured)
    }

    // ── 2. Chat text_reply Parsing ──────────────────────────────────────────────

    @Test
    fun sendChat_parsesTextReplyResponse() = runBlocking {
        val mockJson = """
            {
                "response_type": "text_reply",
                "reply": "Hello! I am NOVA, your personal agent.",
                "model": "gemini-2.5-flash",
                "tool_calls": [],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val request = ChatRequest(
            prompt = "Hello NOVA",
            history = listOf(ChatMessageDto(role = "user", content = "Hi"))
        )

        val result = apiClient.sendChat(request)

        assertTrue(result is NovaApiResult.Success)
        val data = (result as NovaApiResult.Success).data
        assertEquals("text_reply", data.responseType)
        assertEquals("Hello! I am NOVA, your personal agent.", data.reply)
        assertEquals("gemini-2.5-flash", data.model)
        assertTrue(data.toolCalls.isEmpty())
        assertEquals("success", data.status)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("/api/chat", recordedRequest.path)
        assertTrue(recordedRequest.body.readUtf8().contains("Hello NOVA"))
    }

    // ── 3. Chat tool_request Parsing (including arguments object) ───────────────

    @Test
    fun sendChat_parsesToolRequestWithArguments() = runBlocking {
        val mockJson = """
            {
                "response_type": "tool_request",
                "reply": "I will create that note for you.",
                "model": "gemini-2.5-flash",
                "tool_calls": [
                    {
                        "call_id": "call_abc123",
                        "tool_name": "create_note",
                        "arguments": {
                            "title": "Groceries",
                            "content": "Milk and eggs"
                        },
                        "requires_confirmation": false
                    }
                ],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Add groceries note"))

        assertTrue(result is NovaApiResult.Success)
        val data = (result as NovaApiResult.Success).data
        assertEquals("tool_request", data.responseType)
        assertEquals(1, data.toolCalls.size)

        val toolCall = data.toolCalls[0]
        assertEquals("call_abc123", toolCall.callId)
        assertEquals("create_note", toolCall.toolName)
        assertFalse(toolCall.requiresConfirmation)

        // Verify arguments object fields
        assertEquals("Groceries", toolCall.arguments["title"]?.jsonPrimitive?.content)
        assertEquals("Milk and eggs", toolCall.arguments["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun sendChat_parsesDestructiveToolRequiringConfirmation() = runBlocking {
        val mockJson = """
            {
                "response_type": "tool_request",
                "reply": "Are you sure you want to delete note 42?",
                "model": "gemini-2.5-flash",
                "tool_calls": [
                    {
                        "call_id": "call_del_99",
                        "tool_name": "delete_note",
                        "arguments": {
                            "id": 42
                        },
                        "requires_confirmation": true
                    }
                ],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Delete note 42"))

        assertTrue(result is NovaApiResult.Success)
        val toolCall = (result as NovaApiResult.Success).data.toolCalls[0]
        assertEquals("delete_note", toolCall.toolName)
        assertTrue(toolCall.requiresConfirmation)
        assertEquals("42", toolCall.arguments["id"]?.jsonPrimitive?.content)
    }

    // ── 4. Tool Result Request Serialization ───────────────────────────────────

    @Test
    fun sendToolResult_serializesPayloadCorrectly() = runBlocking {
        val mockResponseJson = """
            {
                "response_type": "text_reply",
                "reply": "Note created with ID 10.",
                "model": "gemini-2.5-flash",
                "tool_calls": [],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockResponseJson))

        val toolResultReq = ToolResultRequest(
            prompt = "Create note",
            toolCall = ToolCallPayload(
                callId = "call_123",
                toolName = "create_note",
                arguments = buildJsonObject {
                    put("title", "Work")
                    put("content", "Finish report")
                }
            ),
            toolResult = ToolResultPayloads.createNoteSuccess(noteId = 10L),
            history = listOf(ChatMessageDto(role = "user", content = "Earlier message")),
            systemInstruction = null
        )

        val result = apiClient.sendToolResult(toolResultReq)

        assertTrue(result is NovaApiResult.Success)
        val data = (result as NovaApiResult.Success).data
        assertEquals("Note created with ID 10.", data.reply)

        // Inspect the outgoing HTTP request body serialized by OkHttp + kotlinx.serialization
        val recorded = mockWebServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/chat/tool-result", recorded.path)

        val bodyString = recorded.body.readUtf8()
        assertTrue(bodyString.contains("\"call_id\":\"call_123\""))
        assertTrue(bodyString.contains("\"tool_name\":\"create_note\""))
        assertTrue(bodyString.contains("\"note_id\":10"))
        assertTrue(bodyString.contains("\"prompt\":\"Create note\""))
    }

    @Test
    fun sendToolResult_serializesCancellationResultCorrectly() = runBlocking {
        val mockResponseJson = """
            {
                "response_type": "text_reply",
                "reply": "Understood. The note was not deleted.",
                "model": "gemini-2.5-flash",
                "tool_calls": [],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockResponseJson))

        val toolResultReq = ToolResultRequest(
            prompt = "Delete note 5",
            toolCall = ToolCallPayload(
                callId = "call_del_5",
                toolName = "delete_note",
                arguments = buildJsonObject { put("id", 5) }
            ),
            toolResult = ToolResultPayloads.cancellationResult("User declined confirmation"),
            history = emptyList()
        )

        val result = apiClient.sendToolResult(toolResultReq)

        assertTrue(result is NovaApiResult.Success)
        val recorded = mockWebServer.takeRequest()
        val bodyString = recorded.body.readUtf8()
        assertTrue(bodyString.contains("\"cancelled\":true"))
        assertTrue(bodyString.contains("\"User declined confirmation\""))
    }

    // ── 5. Snake_case JSON Field Mapping ───────────────────────────────────────

    @Test
    fun snakeCaseMapping_allFieldsMappedAccurately() = runBlocking {
        // Enqueue response using exact backend snake_case keys
        val mockJson = """
            {
                "response_type": "text_reply",
                "reply": "Mapped successfully",
                "model": "gemini-2.5-flash",
                "tool_calls": [
                    {
                        "call_id": "test_id",
                        "tool_name": "search_notes",
                        "arguments": {"query": "test"},
                        "requires_confirmation": false
                    }
                ],
                "status": "success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "search"))
        assertTrue(result is NovaApiResult.Success)

        val data = (result as NovaApiResult.Success).data
        assertEquals("text_reply", data.responseType)
        assertEquals("test_id", data.toolCalls[0].callId)
        assertEquals("search_notes", data.toolCalls[0].toolName)
        assertFalse(data.toolCalls[0].requiresConfirmation)
    }

    // ── 6. HTTP Error Handling (422, 502, 503, 500) ─────────────────────────────

    @Test
    fun sendChat_handlesHttp422ValidationError() = runBlocking {
        val errorJson = """
            {
                "detail": "Prompt cannot be empty or contain only whitespace."
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(422).setBody(errorJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "   "))

        assertTrue("Result should be HttpError", result is NovaApiResult.HttpError)
        val error = result as NovaApiResult.HttpError
        assertEquals(422, error.statusCode)
        assertTrue(error.message.contains("Prompt cannot be empty"))
    }

    @Test
    fun sendChat_handlesHttp502BadGateway() = runBlocking {
        val errorJson = """
            {
                "detail": "The AI model generated an unexecutable or invalid tool request."
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(502).setBody(errorJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Invalid tool trigger"))

        assertTrue(result is NovaApiResult.HttpError)
        val error = result as NovaApiResult.HttpError
        assertEquals(502, error.statusCode)
        assertTrue(error.message.contains("invalid tool request"))
    }

    @Test
    fun sendChat_handlesHttp503ServiceUnavailable() = runBlocking {
        val errorJson = """
            {
                "detail": "Gemini API is not configured on the backend. Please configure GEMINI_API_KEY."
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody(errorJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Hello"))

        assertTrue(result is NovaApiResult.HttpError)
        val error = result as NovaApiResult.HttpError
        assertEquals(503, error.statusCode)
        assertTrue(error.message.contains("Gemini API is not configured"))
    }

    @Test
    fun sendChat_handlesHttp500InternalServerError() = runBlocking {
        val errorJson = """
            {
                "detail": "Internal server error processing request."
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody(errorJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Crash"))

        assertTrue(result is NovaApiResult.HttpError)
        val error = result as NovaApiResult.HttpError
        assertEquals(500, error.statusCode)
        assertTrue(error.message.contains("Internal server error"))
    }

    // ── 7. Malformed JSON Response ──────────────────────────────────────────────

    @Test
    fun sendChat_handlesMalformedJsonResponseWithoutCrashing() = runBlocking {
        val invalidJson = "{ this is not valid json : [[{"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(invalidJson))

        val result = apiClient.sendChat(ChatRequest(prompt = "Test"))

        assertTrue("Should return NetworkFailure on malformed JSON", result is NovaApiResult.NetworkFailure)
        val failure = result as NovaApiResult.NetworkFailure
        assertNotNull(failure.error)
        assertTrue(failure.message.contains("Failed to parse"))
    }

    // ── 8. Timeout and Connection Failure ───────────────────────────────────────

    @Test
    fun sendChat_handlesSocketTimeoutGracefully() = runBlocking {
        // Enqueue a response that never returns (simulate timeout)
        mockWebServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        // Create client with 100ms timeout for fast unit test execution
        val shortTimeoutClient = NovaApiClient(
            baseUrl = mockWebServer.url("/").toString(),
            connectTimeoutSeconds = 1,
            readTimeoutSeconds = 1,
            writeTimeoutSeconds = 1,
            customOkHttpClient = OkHttpClient.Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .build(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = shortTimeoutClient.sendChat(ChatRequest(prompt = "Timeout test"))

        assertTrue("Should return NetworkFailure on timeout", result is NovaApiResult.NetworkFailure)
        val failure = result as NovaApiResult.NetworkFailure
        assertTrue(failure.message.contains("timed out", ignoreCase = true))
    }

    @Test
    fun sendChat_handlesConnectionRefused() = runBlocking {
        // Point client to a port that is guaranteed not to be listening
        val deadClient = NovaApiClient(
            baseUrl = "http://localhost:59999/",
            connectTimeoutSeconds = 1,
            customOkHttpClient = OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .build(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = deadClient.sendChat(ChatRequest(prompt = "Connection test"))

        assertTrue("Should return NetworkFailure on connection refused", result is NovaApiResult.NetworkFailure)
        val failure = result as NovaApiResult.NetworkFailure
        assertTrue(failure.message.contains("Network error", ignoreCase = true))
    }
}

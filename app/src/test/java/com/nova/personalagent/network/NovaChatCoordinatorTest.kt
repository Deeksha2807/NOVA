package com.nova.personalagent.network

import com.nova.personalagent.FakeChatMessageDao
import com.nova.personalagent.FakeNoteDao
import com.nova.personalagent.data.ChatRepository
import com.nova.personalagent.data.Note
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.network.models.ChatRequest
import com.nova.personalagent.network.models.ChatResponse
import com.nova.personalagent.network.models.HealthResponse
import com.nova.personalagent.network.models.ToolCallRequest
import com.nova.personalagent.network.models.ToolResultRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake implementation of NovaApi for fast, isolated coordinator tests.
 */
class FakeNovaApi : NovaApi {
    var chatResponseToReturn: NovaApiResult<ChatResponse>? = null
    var toolResultResponseToReturn: NovaApiResult<ChatResponse>? = null
    var healthResponseToReturn: NovaApiResult<HealthResponse>? = null

    var lastChatRequest: ChatRequest? = null
    var lastToolResultRequest: ToolResultRequest? = null

    override suspend fun getHealth(): NovaApiResult<HealthResponse> {
        return healthResponseToReturn ?: NovaApiResult.Success(
            HealthResponse(status = "ok", geminiConfigured = true, model = "gemini-2.5-flash", version = "1.0.0")
        )
    }

    override suspend fun sendChat(request: ChatRequest): NovaApiResult<ChatResponse> {
        lastChatRequest = request
        return chatResponseToReturn ?: error("No chat response configured in FakeNovaApi")
    }

    override suspend fun sendToolResult(request: ToolResultRequest): NovaApiResult<ChatResponse> {
        lastToolResultRequest = request
        return toolResultResponseToReturn ?: error("No tool result response configured in FakeNovaApi")
    }
}

/**
 * ============================================================================
 * Unit Tests: NovaChatCoordinator
 * ============================================================================
 * Runs on the JVM using in-memory fakes for NovaApi, ChatRepository, and NotesRepository.
 * No network calls, device, or Gemini API keys required.
 */
class NovaChatCoordinatorTest {

    private lateinit var fakeApi: FakeNovaApi
    private lateinit var fakeChatDao: FakeChatMessageDao
    private lateinit var fakeNoteDao: FakeNoteDao
    private lateinit var chatRepository: ChatRepository
    private lateinit var notesRepository: NotesRepository
    private lateinit var coordinator: NovaChatCoordinator

    private var confirmationResult = true
    private var lastConfirmedToolName: String? = null
    private var lastConfirmedTargetId: Long? = null

    @Before
    fun setUp() {
        fakeApi = FakeNovaApi()
        fakeChatDao = FakeChatMessageDao()
        fakeNoteDao = FakeNoteDao()

        chatRepository = ChatRepository(fakeChatDao, ioDispatcher = Dispatchers.Unconfined)
        notesRepository = NotesRepository(fakeNoteDao, ioDispatcher = Dispatchers.Unconfined)

        confirmationResult = true
        lastConfirmedToolName = null
        lastConfirmedTargetId = null

        val confirmationProvider = ConfirmationProvider { toolName, _, targetId ->
            lastConfirmedToolName = toolName
            lastConfirmedTargetId = targetId
            confirmationResult
        }

        coordinator = NovaChatCoordinator(
            api = fakeApi,
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            confirmationProvider = confirmationProvider,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // ── 1. Normal Text Reply ───────────────────────────────────────────────────

    @Test
    fun sendMessage_normalTextReply_persistsTurnsAndReturnsSuccess() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "I am NOVA, ready to help.",
                model = "gemini-2.5-flash",
                toolCalls = emptyList()
            )
        )

        val result = coordinator.sendMessage("Who are you?")

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals("I am NOVA, ready to help.", success.reply)
        assertNull(success.toolExecuted)
        assertFalse(success.wasCancelled)

        // Verify history in ChatRepository
        val history = chatRepository.getMessagesOnce()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Who are you?", history[0].content)
        assertEquals("assistant", history[1].role)
        assertEquals("I am NOVA, ready to help.", history[1].content)
    }

    // ── 2. Tool Dispatch: create_note ──────────────────────────────────────────

    @Test
    fun sendMessage_createNoteTool_executesToolAndReturnsFinalReply() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Creating note...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_create_1",
                        toolName = "create_note",
                        arguments = buildJsonObject {
                            put("title", "Groceries")
                            put("content", "Milk and Eggs")
                        }
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Note 'Groceries' created with ID 1.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Take a note for groceries")

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals("Note 'Groceries' created with ID 1.", success.reply)
        assertEquals("create_note", success.toolExecuted)

        // Verify note was actually created in NotesRepository
        val notes = notesRepository.search_notes("Groceries")
        assertEquals(1, notes.size)
        assertEquals("Groceries", notes[0].title)
        assertEquals("Milk and Eggs", notes[0].content)

        // Verify tool result payload sent to API
        val toolResultReq = fakeApi.lastToolResultRequest
        assertNotNull(toolResultReq)
        assertEquals("call_create_1", toolResultReq?.toolCall?.callId)
        assertEquals(true, toolResultReq?.toolResult?.get("success")?.jsonPrimitive?.content?.toBoolean())
        assertNotNull(toolResultReq?.toolResult?.get("note_id"))
    }

    // ── 3. Tool Dispatch: search_notes ─────────────────────────────────────────

    @Test
    fun sendMessage_searchNotesTool_executesSearchAndSendsResults() = runBlocking {
        // Pre-seed notes
        notesRepository.create_note("Dentist", "Appointment on Tuesday")
        notesRepository.create_note("Doctor", "Annual checkup")

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Searching notes...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_search_1",
                        toolName = "search_notes",
                        arguments = buildJsonObject {
                            put("query", "Dentist")
                        }
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Found 1 note about Dentist.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Find my dentist appointment")

        assertTrue(result is CoordinatorResult.Success)
        assertEquals("search_notes", (result as CoordinatorResult.Success).toolExecuted)

        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals("1", toolResultPayload?.get("count")?.jsonPrimitive?.content)
    }

    // ── 4. Tool Dispatch: get_note ─────────────────────────────────────────────

    @Test
    fun sendMessage_getNoteTool_retrievesNoteById() = runBlocking {
        val noteId = notesRepository.create_note("Important Meeting", "Room 404")

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Fetching note...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_get_1",
                        toolName = "get_note",
                        arguments = buildJsonObject {
                            put("id", noteId)
                        }
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "The note is 'Important Meeting' in Room 404.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Get note $noteId")

        assertTrue(result is CoordinatorResult.Success)
        assertEquals("get_note", (result as CoordinatorResult.Success).toolExecuted)

        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals(true, toolResultPayload?.get("success")?.jsonPrimitive?.content?.toBoolean())
    }

    // ── 5. Tool Dispatch: update_note ──────────────────────────────────────────

    @Test
    fun sendMessage_updateNoteTool_modifiesExistingNote() = runBlocking {
        val noteId = notesRepository.create_note("Original Title", "Old Content")

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Updating note...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_update_1",
                        toolName = "update_note",
                        arguments = buildJsonObject {
                            put("id", noteId)
                            put("title", "Updated Title")
                            put("content", "New Content")
                        }
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Note updated successfully.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Update note $noteId")

        assertTrue(result is CoordinatorResult.Success)
        assertEquals("update_note", (result as CoordinatorResult.Success).toolExecuted)

        // Verify update in repository
        val updatedNote = notesRepository.get_note(noteId)
        assertNotNull(updatedNote)
        assertEquals("Updated Title", updatedNote?.title)
        assertEquals("New Content", updatedNote?.content)
    }

    // ── 6. Tool Dispatch: delete_note (Confirmed) ──────────────────────────────

    @Test
    fun sendMessage_deleteNote_confirmedByUser_deletesNote() = runBlocking {
        val noteId = notesRepository.create_note("To Delete", "Delete this soon")
        confirmationResult = true // User confirms

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Are you sure you want to delete note $noteId?",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_del_1",
                        toolName = "delete_note",
                        arguments = buildJsonObject {
                            put("id", noteId)
                        },
                        requiresConfirmation = true
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Note $noteId has been deleted.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Delete note $noteId")

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals("delete_note", success.toolExecuted)
        assertFalse(success.wasCancelled)

        // Verify confirmation was requested with correct ID
        assertEquals("delete_note", lastConfirmedToolName)
        assertEquals(noteId, lastConfirmedTargetId)

        // Verify note was deleted from repository
        assertNull(notesRepository.get_note(noteId))
    }

    // ── 7. Tool Dispatch: delete_note (Declined / Cancelled) ────────────────────

    @Test
    fun sendMessage_deleteNote_declinedByUser_sendsCancellationPayload() = runBlocking {
        val noteId = notesRepository.create_note("Preserve Me", "Do not delete")
        confirmationResult = false // User declines!

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Are you sure you want to delete note $noteId?",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_del_2",
                        toolName = "delete_note",
                        arguments = buildJsonObject {
                            put("id", noteId)
                        },
                        requiresConfirmation = true
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Understood. The note was not deleted.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Delete note $noteId")

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals("delete_note", success.toolExecuted)
        assertTrue("wasCancelled must be true", success.wasCancelled)

        // Verify note was NOT deleted from repository
        assertNotNull(notesRepository.get_note(noteId))

        // Verify cancellation payload sent to API matching backend documentation
        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals(false, toolResultPayload?.get("success")?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, toolResultPayload?.get("cancelled")?.jsonPrimitive?.content?.toBoolean())
        assertEquals("User declined confirmation", toolResultPayload?.get("message")?.jsonPrimitive?.content)
    }

    // ── 8. Unknown and Invalid Tool Requests ───────────────────────────────────

    @Test
    fun sendMessage_unknownToolName_sendsErrorPayloadGracefully() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Running unknown tool...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_unknown",
                        toolName = "delete_all_system_files",
                        arguments = buildJsonObject {}
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "I cannot perform that action.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Delete all system files")

        assertTrue(result is CoordinatorResult.Success)
        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals(false, toolResultPayload?.get("success")?.jsonPrimitive?.content?.toBoolean())
        assertTrue(toolResultPayload?.get("error")?.jsonPrimitive?.content?.contains("Unknown tool") == true)
    }

    @Test
    fun sendMessage_malformedToolArguments_sendsErrorPayload() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Fetching note...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_malformed",
                        toolName = "get_note",
                        arguments = buildJsonObject {
                            put("id", "not_a_valid_number")
                        }
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Could not find that note due to invalid ID.",
                model = "gemini-2.5-flash"
            )
        )

        val result = coordinator.sendMessage("Get note abc")

        assertTrue(result is CoordinatorResult.Success)
        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals(false, toolResultPayload?.get("success")?.jsonPrimitive?.content?.toBoolean())
        assertTrue(toolResultPayload?.get("error")?.jsonPrimitive?.content?.contains("Invalid or missing 'id'") == true)
    }

    // ── 9. Error Handling: Backend HTTP & Network Failures ─────────────────────

    @Test
    fun sendMessage_backendHttpError_returnsCoordinatorError() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.HttpError(
            statusCode = 503,
            message = "Gemini API key is not configured on the backend."
        )

        val result = coordinator.sendMessage("Hello")

        assertTrue(result is CoordinatorResult.Error)
        val error = result as CoordinatorResult.Error
        assertTrue(error.message.contains("503"))
        assertTrue(error.message.contains("Gemini API key is not configured"))
    }

    @Test
    fun sendMessage_toolResultApiFailure_returnsCoordinatorError() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Creating note...",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_fail",
                        toolName = "create_note",
                        arguments = buildJsonObject {
                            put("title", "Test")
                            put("content", "Body")
                        }
                    )
                )
            )
        )

        // The follow-up tool-result endpoint fails with 500
        fakeApi.toolResultResponseToReturn = NovaApiResult.HttpError(
            statusCode = 500,
            message = "Internal server error"
        )

        val result = coordinator.sendMessage("Create note Test")

        assertTrue(result is CoordinatorResult.Error)
        val error = result as CoordinatorResult.Error
        assertTrue(error.message.contains("500"))
        assertTrue(error.message.contains("Failed to submit tool result"))
    }

    // ── 10. History De-duplication & Persistence Integrity ─────────────────────

    @Test
    fun sendMessage_historyDoesNotDuplicatePrompt_evenIfPreSaved() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Hello!",
                model = "gemini-2.5-flash"
            )
        )

        // Pre-save an earlier turn
        chatRepository.saveMessage("user", "Previous question")
        chatRepository.saveMessage("assistant", "Previous answer")

        coordinator.sendMessage("New question")

        // Check history sent in ChatRequest to backend
        val chatReq = fakeApi.lastChatRequest
        assertNotNull(chatReq)
        assertEquals("New question", chatReq?.prompt)
        assertEquals(2, chatReq?.history?.size)
        assertEquals("Previous question", chatReq?.history?.get(0)?.content)
        assertEquals("Previous answer", chatReq?.history?.get(1)?.content)

        // Check total turns in database
        val allMessages = chatRepository.getMessagesOnce()
        assertEquals(4, allMessages.size)
        assertEquals("New question", allMessages[2].content)
        assertEquals("Hello!", allMessages[3].content)
    }

    @Test
    fun sendMessage_preSavedPrompt_notAddedTwiceToDatabase() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Yes, I heard you.",
                model = "gemini-2.5-flash"
            )
        )

        // Simulate a scenario where the UI already pre-saved the user prompt
        chatRepository.saveMessage("user", "Pre-saved prompt")

        coordinator.sendMessage("Pre-saved prompt")

        // Total turns should be exactly 2: 1 user, 1 assistant
        val allMessages = chatRepository.getMessagesOnce()
        assertEquals(2, allMessages.size)
        assertEquals("user", allMessages[0].role)
        assertEquals("Pre-saved prompt", allMessages[0].content)
        assertEquals("assistant", allMessages[1].role)
        assertEquals("Yes, I heard you.", allMessages[1].content)
    }

    // ── 11. Hardened Safety: Exceptions & Concurrency ──────────────────────────

    @Test
    fun sendMessage_deleteNote_confirmationThrowsException_neverDeletesNoteAndSendsCancellation() = runBlocking {
        val noteId = notesRepository.create_note("Crucial Note", "Must survive error")

        val throwingConfirmationProvider = ConfirmationProvider { _, _, _ ->
            throw IllegalStateException("UI destroyed or lifecycle error")
        }

        val testCoordinator = NovaChatCoordinator(
            api = fakeApi,
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            confirmationProvider = throwingConfirmationProvider,
            ioDispatcher = Dispatchers.Unconfined
        )

        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "tool_request",
                reply = "Are you sure you want to delete note $noteId?",
                model = "gemini-2.5-flash",
                toolCalls = listOf(
                    ToolCallRequest(
                        callId = "call_del_err",
                        toolName = "delete_note",
                        arguments = buildJsonObject {
                            put("id", noteId)
                        },
                        requiresConfirmation = true
                    )
                )
            )
        )

        fakeApi.toolResultResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Confirmation was interrupted. Note was not deleted.",
                model = "gemini-2.5-flash"
            )
        )

        val result = testCoordinator.sendMessage("Delete note $noteId")

        assertTrue(result is CoordinatorResult.Success)
        val success = result as CoordinatorResult.Success
        assertEquals("delete_note", success.toolExecuted)
        assertTrue(success.wasCancelled)

        // Verify note was NOT deleted from repository
        assertNotNull(notesRepository.get_note(noteId))

        // Verify cancellation payload sent to API indicates failure
        val toolResultPayload = fakeApi.lastToolResultRequest?.toolResult
        assertNotNull(toolResultPayload)
        assertEquals(false, toolResultPayload?.get("success")?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, toolResultPayload?.get("cancelled")?.jsonPrimitive?.content?.toBoolean())
        assertTrue(toolResultPayload?.get("message")?.jsonPrimitive?.content?.contains("Confirmation failed") == true)
    }

    @Test
    fun sendMessage_concurrentInvocations_areSerializedSafelyWithoutInterleaving() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Response to prompt",
                model = "gemini-2.5-flash"
            )
        )

        // Launch two concurrent calls with async
        val deferred1 = async(Dispatchers.IO) { coordinator.sendMessage("Message 1") }
        val deferred2 = async(Dispatchers.IO) { coordinator.sendMessage("Message 2") }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        assertTrue(result1 is CoordinatorResult.Success)
        assertTrue(result2 is CoordinatorResult.Success)

        // Messages in ChatRepository should be strictly ordered without corruption
        val messages = chatRepository.getMessagesOnce()
        assertEquals(4, messages.size) // 2 user + 2 assistant
    }
}

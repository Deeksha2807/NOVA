package com.nova.personalagent

import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.data.ChatRepository
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.network.CoordinatorResult
import com.nova.personalagent.network.FakeNovaApi
import com.nova.personalagent.network.NovaApiResult
import com.nova.personalagent.network.NovaChatCoordinator
import com.nova.personalagent.network.models.ChatResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatViewModel].
 * Runs entirely on the JVM using in-memory fakes.
 */
class ChatViewModelTest {

    private lateinit var fakeApi: FakeNovaApi
    private lateinit var fakeChatDao: FakeChatMessageDao
    private lateinit var fakeNoteDao: FakeNoteDao
    private lateinit var chatRepository: ChatRepository
    private lateinit var notesRepository: NotesRepository
    private lateinit var bridge: ChatConfirmationBridge
    private lateinit var coordinator: NovaChatCoordinator
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        fakeApi = FakeNovaApi()
        fakeChatDao = FakeChatMessageDao()
        fakeNoteDao = FakeNoteDao()

        chatRepository = ChatRepository(fakeChatDao, ioDispatcher = Dispatchers.Unconfined)
        notesRepository = NotesRepository(fakeNoteDao, ioDispatcher = Dispatchers.Unconfined)
        bridge = ChatConfirmationBridge()

        coordinator = NovaChatCoordinator(
            api = fakeApi,
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            confirmationProvider = bridge,
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel = ChatViewModel(
            chatRepository = chatRepository,
            coordinator = coordinator,
            confirmationBridge = bridge,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // ── 1. History Loading and Observation ─────────────────────────────────────

    @Test
    fun init_loadsExistingHistoryAndObservesUpdates() = runBlocking {
        // Pre-seed messages into database
        chatRepository.saveMessage("user", "Hello there")
        chatRepository.saveMessage("assistant", "Hi, I am NOVA")

        // Initial messages in UI state should match pre-seeded history
        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals("Hello there", messages[0].content)
        assertEquals("Hi, I am NOVA", messages[1].content)

        // Adding a new message reactively updates UI state without clearing history
        chatRepository.saveMessage("user", "New message")
        val updated = viewModel.uiState.value.messages
        assertEquals(3, updated.size)
        assertEquals("New message", updated[2].content)
    }

    // ── 2. Successful Message Sending ──────────────────────────────────────────

    @Test
    fun sendMessage_successfulTextReply_updatesHistoryAndResetsSendingState() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.Success(
            ChatResponse(
                responseType = "text_reply",
                reply = "Good day! How can I help?",
                model = "gemini-2.5-flash"
            )
        )

        val job = viewModel.sendMessage("Good morning")
        job?.join()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertNull(state.errorMessage)
        assertEquals(2, state.messages.size)
        assertEquals("Good morning", state.messages[0].content)
        assertEquals("Good day! How can I help?", state.messages[1].content)
    }

    // ── 3. ViewModel Error Handling ────────────────────────────────────────────

    @Test
    fun sendMessage_coordinatorReturnsError_setsErrorMessageAndResetsSendingState() = runBlocking {
        fakeApi.chatResponseToReturn = NovaApiResult.HttpError(
            statusCode = 503,
            message = "Gemini API unavailable"
        )

        val job = viewModel.sendMessage("Check status")
        job?.join()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("503"))
        assertTrue(state.errorMessage!!.contains("Gemini API unavailable"))

        // Clear error action works
        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // ── 4. Rejection of Repeated Submissions While In-Progress ──────────────────

    @Test
    fun sendMessage_rejectsRepeatedSubmissionWhileInProgress() = runBlocking {
        // Create an API that takes a small delay before returning
        val delayedApi = object : com.nova.personalagent.network.NovaApi {
            override suspend fun getHealth() = fakeApi.getHealth()
            override suspend fun sendChat(request: com.nova.personalagent.network.models.ChatRequest): NovaApiResult<ChatResponse> {
                delay(100)
                return NovaApiResult.Success(
                    ChatResponse(
                        responseType = "text_reply",
                        reply = "First reply",
                        model = "gemini-2.5-flash"
                    )
                )
            }
            override suspend fun sendToolResult(request: com.nova.personalagent.network.models.ToolResultRequest) = fakeApi.sendToolResult(request)
        }

        val delayedCoordinator = NovaChatCoordinator(
            api = delayedApi,
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            ioDispatcher = Dispatchers.IO
        )

        val delayedVm = ChatViewModel(
            chatRepository = chatRepository,
            coordinator = delayedCoordinator,
            ioDispatcher = Dispatchers.IO
        )

        // Start first message
        val job1 = delayedVm.sendMessage("First prompt")
        assertNotNull("First message job must not be null", job1)

        // Attempt second message immediately while first is in progress
        val job2 = delayedVm.sendMessage("Second prompt")
        assertNull("Second message attempt must be rejected (null Job) while first is running", job2)

        job1?.join()
        assertFalse(delayedVm.uiState.value.isSending)
    }

    // ── 5. Blank Prompts are Ignored ───────────────────────────────────────────

    @Test
    fun sendMessage_blankOrEmptyPrompt_returnsNullAndDoesNotSend() {
        val jobEmpty = viewModel.sendMessage("")
        assertNull(jobEmpty)

        val jobWhitespace = viewModel.sendMessage("   ")
        assertNull(jobWhitespace)

        assertFalse(viewModel.uiState.value.isSending)
        assertEquals(0, viewModel.uiState.value.messages.size)
    }

    // ── 6. Delete Confirmation Bridge Interaction ──────────────────────────────

    @Test
    fun confirmationBridge_confirmFlow_approvesAction() = runBlocking {
        val deferredConfirm = async {
            bridge.requestConfirmation("delete_note", "Are you sure?", 42L)
        }

        // Wait until bridge emits pending request to UI state
        delay(20)
        val pending = viewModel.uiState.value.pendingConfirmation
        assertNotNull(pending)
        assertEquals("delete_note", pending?.toolName)
        assertEquals(42L, pending?.targetId)

        // User confirms
        viewModel.confirmDelete()

        val confirmed = deferredConfirm.await()
        assertTrue("User confirmation must return true", confirmed)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun confirmationBridge_dismissFlow_declinesAction() = runBlocking {
        val deferredConfirm = async {
            bridge.requestConfirmation("delete_note", "Are you sure?", 99L)
        }

        delay(20)
        val pending = viewModel.uiState.value.pendingConfirmation
        assertNotNull(pending)

        // User dismisses
        viewModel.dismissDelete()

        val confirmed = deferredConfirm.await()
        assertFalse("User decline must return false", confirmed)
        assertNull(viewModel.uiState.value.pendingConfirmation)
    }
}

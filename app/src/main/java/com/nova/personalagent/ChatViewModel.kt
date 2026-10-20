package com.nova.personalagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.data.ChatRepository
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.network.ConfirmationProvider
import com.nova.personalagent.network.CoordinatorResult
import com.nova.personalagent.network.NovaApi
import com.nova.personalagent.network.NovaChatCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 * Delete Confirmation Request
 * ============================================================================
 * Represents an active confirmation prompt requested by [NovaChatCoordinator]
 * before executing a destructive tool (e.g. "delete_note").
 */
data class DeleteConfirmationRequest(
    val toolName: String,
    val message: String,
    val targetId: Long
)

/**
 * ============================================================================
 * Chat UI State
 * ============================================================================
 * Immutable state model observed by the chat UI / HomeScreen.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val pendingConfirmation: DeleteConfirmationRequest? = null
)

/**
 * ============================================================================
 * Confirmation Bridge
 * ============================================================================
 * Connects the coordinator's synchronous/suspending [ConfirmationProvider] callback
 * to the ViewModel's reactive UI state.
 */
class ChatConfirmationBridge : ConfirmationProvider {
    private val _pendingRequest = MutableStateFlow<DeleteConfirmationRequest?>(null)
    val pendingRequest: StateFlow<DeleteConfirmationRequest?> = _pendingRequest.asStateFlow()

    private var activeDeferred: CompletableDeferred<Boolean>? = null

    override suspend fun requestConfirmation(
        toolName: String,
        message: String,
        targetId: Long
    ): Boolean {
        // Cancel any lingering previous deferred safely if one existed
        activeDeferred?.complete(false)

        val deferred = CompletableDeferred<Boolean>()
        activeDeferred = deferred
        _pendingRequest.value = DeleteConfirmationRequest(toolName, message, targetId)
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } finally {
            _pendingRequest.value = null
            activeDeferred = null
        }
    }

    /**
     * User confirmed the action (e.g. tapped "Delete" in confirmation dialog).
     */
    fun confirm() {
        activeDeferred?.complete(true)
    }

    /**
     * User dismissed or cancelled the action (e.g. tapped "Cancel").
     */
    fun dismiss() {
        activeDeferred?.complete(false)
    }
}

/**
 * ============================================================================
 * Chat ViewModel
 * ============================================================================
 * Manages conversational state, integrates persistent history via [ChatRepository],
 * and orchestrates message submission to [NovaChatCoordinator].
 *
 * Responsibilities:
 *  1. Exposes observable [uiState] containing messages, sending state, error, and confirmation requests.
 *  2. Prevents repeated / concurrent message submissions while one is in flight.
 *  3. Dispatches all network and database operations off the main thread.
 *  4. Observes existing chat history reactively without clearing it.
 *  5. Respects coroutine cancellation without swallowing [CancellationException].
 */
open class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val coordinator: NovaChatCoordinator,
    private val confirmationBridge: ChatConfirmationBridge? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val isSendingInProgress = AtomicBoolean(false)

    init {
        // 1. Observe persistent chat history from Room
        viewModelScope.launch(ioDispatcher) {
            chatRepository.allMessages.collect { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }

        // 2. Observe destructive action confirmation requests from the coordinator
        confirmationBridge?.let { bridge ->
            viewModelScope.launch(ioDispatcher) {
                bridge.pendingRequest.collect { request ->
                    _uiState.update { it.copy(pendingConfirmation = request) }
                }
            }
        }
    }

    /**
     * Lifecycle-safe send-message action.
     * Prevents duplicate/repeated submissions if a request is already in progress.
     *
     * @param prompt The user's input text.
     * @return The launched [Job], or null if rejected due to blank prompt or active request.
     */
    fun sendMessage(prompt: String): Job? {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return null

        if (!isSendingInProgress.compareAndSet(false, true)) {
            // Rejection of repeated submission while in progress
            return null
        }

        _uiState.update { it.copy(isSending = true, errorMessage = null) }

        return viewModelScope.launch(ioDispatcher) {
            try {
                when (val result = coordinator.sendMessage(cleanPrompt)) {
                    is CoordinatorResult.Success -> {
                        _uiState.update { it.copy(isSending = false, errorMessage = null) }
                    }
                    is CoordinatorResult.Error -> {
                        _uiState.update { it.copy(isSending = false, errorMessage = result.message) }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = e.message ?: "An unexpected error occurred."
                    )
                }
            } finally {
                isSendingInProgress.set(false)
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    /**
     * Suspend-safe send-message action for direct coroutine callers.
     */
    suspend fun sendMessageSuspend(prompt: String): CoordinatorResult? = withContext(ioDispatcher) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext null

        if (!isSendingInProgress.compareAndSet(false, true)) {
            return@withContext null
        }

        _uiState.update { it.copy(isSending = true, errorMessage = null) }

        try {
            val result = coordinator.sendMessage(cleanPrompt)
            when (result) {
                is CoordinatorResult.Success -> {
                    _uiState.update { it.copy(isSending = false, errorMessage = null) }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update { it.copy(isSending = false, errorMessage = result.message) }
                }
            }
            result
        } catch (e: CancellationException) {
            _uiState.update { it.copy(isSending = false) }
            throw e
        } catch (e: Exception) {
            val error = CoordinatorResult.Error(e.message ?: "An unexpected error occurred.", e)
            _uiState.update { it.copy(isSending = false, errorMessage = error.message) }
            error
        } finally {
            isSendingInProgress.set(false)
            _uiState.update { it.copy(isSending = false) }
        }
    }

    /**
     * User approves the pending destructive tool confirmation.
     */
    fun confirmDelete() {
        confirmationBridge?.confirm()
    }

    /**
     * User declines or dismisses the pending destructive tool confirmation.
     */
    fun dismissDelete() {
        confirmationBridge?.dismiss()
    }

    /**
     * Clears any active error banner without affecting messages.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        /**
         * Convenience factory that constructs [ChatConfirmationBridge], [NovaChatCoordinator],
         * and [ChatViewModel] with all internal wiring.
         */
        fun create(
            chatRepository: ChatRepository,
            notesRepository: NotesRepository,
            api: NovaApi,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ChatViewModel {
            val bridge = ChatConfirmationBridge()
            val coordinator = NovaChatCoordinator(
                api = api,
                chatRepository = chatRepository,
                notesRepository = notesRepository,
                confirmationProvider = bridge,
                ioDispatcher = ioDispatcher
            )
            return ChatViewModel(
                chatRepository = chatRepository,
                coordinator = coordinator,
                confirmationBridge = bridge,
                ioDispatcher = ioDispatcher
            )
        }
    }

    /**
     * Standard Android [ViewModelProvider.Factory] for Jetpack Compose injection.
     */
    class Factory(
        private val chatRepository: ChatRepository,
        private val notesRepository: NotesRepository,
        private val api: NovaApi,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel.create(chatRepository, notesRepository, api, ioDispatcher) as T
        }
    }
}

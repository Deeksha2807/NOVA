package com.nova.personalagent.network

import com.nova.personalagent.data.ChatRepository
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.network.models.ChatMessageDto
import com.nova.personalagent.network.models.ChatRequest
import com.nova.personalagent.network.models.ToolCallPayload
import com.nova.personalagent.network.models.ToolCallRequest
import com.nova.personalagent.network.models.ToolResultPayloads
import com.nova.personalagent.network.models.ToolResultRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * ============================================================================
 * Confirmation Provider
 * ============================================================================
 * Testable callback abstraction for requesting explicit user confirmation
 * before executing destructive operations (e.g. delete_note).
 */
fun interface ConfirmationProvider {
    /**
     * Called when a destructive tool invocation requires user confirmation.
     *
     * @param toolName  The name of the tool (e.g. "delete_note").
     * @param message   Human-readable confirmation description.
     * @param targetId  The target note ID to be affected.
     * @return True if the user confirmed the action, false if cancelled/declined.
     */
    suspend fun requestConfirmation(
        toolName: String,
        message: String,
        targetId: Long
    ): Boolean
}

/**
 * ============================================================================
 * Coordinator Result
 * ============================================================================
 * Structured outcome returned by [NovaChatCoordinator.sendMessage].
 */
sealed interface CoordinatorResult {

    /**
     * Successfully received a final conversational reply from NOVA.
     *
     * @param reply         The assistant's spoken / displayed response text.
     * @param toolExecuted  The name of the tool executed, if a tool was invoked.
     * @param wasCancelled  True if a destructive tool was cancelled by the user.
     */
    data class Success(
        val reply: String,
        val toolExecuted: String? = null,
        val wasCancelled: Boolean = false
    ) : CoordinatorResult

    /**
     * An error occurred during communication, tool validation, or execution.
     *
     * @param message User-friendly error description.
     * @param cause   Underlying exception, if available.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : CoordinatorResult

    val isSuccess: Boolean
        get() = this is Success

    val replyOrNull: String?
        get() = (this as? Success)?.reply
}

/**
 * ============================================================================
 * NovaChatCoordinator
 * ============================================================================
 * Orchestrates communication between the network API ([NovaApi]),
 * conversation history storage ([ChatRepository]), and local Room tools ([NotesRepository]).
 *
 * Responsibilities:
 *  1. Sends user prompts and prior history to POST /api/chat.
 *  2. Guards against sending duplicate prompts in history.
 *  3. Handles direct "text_reply" turns.
 *  4. Handles "tool_request" turns by validating arguments and dispatching to [NotesRepository].
 *  5. Requires confirmation via [ConfirmationProvider] before executing destructive tools.
 *  6. Submits local tool execution results back to POST /api/chat/tool-result.
 *  7. Persists user prompts and assistant replies into [ChatRepository] exactly once.
 *  8. Executes all database and network operations off the main thread.
 */
class NovaChatCoordinator(
    private val api: NovaApi,
    private val chatRepository: ChatRepository,
    private val notesRepository: NotesRepository,
    private val confirmationProvider: ConfirmationProvider = ConfirmationProvider { _, _, _ -> true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val sendMutex = Mutex()

    /**
     * Submits a user prompt to NOVA, executes any requested local tool safely,
     * submits the tool outcome back to the backend if needed, and returns the
     * final assistant response.
     *
     * Protected by [sendMutex] so concurrent invocations are strictly serialized,
     * preventing interleaved history and duplicate message persistence.
     *
     * @param prompt The user's spoken or typed prompt.
     * @return [CoordinatorResult.Success] on completion, or [CoordinatorResult.Error] on failure.
     */
    suspend fun sendMessage(prompt: String): CoordinatorResult = withContext(ioDispatcher) {
        sendMutex.withLock {
            val cleanPrompt = prompt.trim()
            if (cleanPrompt.isEmpty()) {
                return@withLock CoordinatorResult.Error("Prompt cannot be empty or contain only whitespace.")
            }

        // 1. Fetch current conversation history
        val existingHistory = chatRepository.getMessagesOnce()

        // Check if the current prompt was already saved as the latest user turn
        val isAlreadySaved = existingHistory.isNotEmpty() &&
                existingHistory.last().role == "user" &&
                existingHistory.last().content == cleanPrompt

        // The history list sent to the backend MUST exclude the current prompt turn,
        // because the backend explicitly appends the current prompt as the latest turn.
        val historyToSend = if (isAlreadySaved) {
            existingHistory.dropLast(1)
        } else {
            existingHistory
        }.map { ChatMessageDto(role = it.role, content = it.content) }

        // Persist the user turn once if not already saved
        if (!isAlreadySaved) {
            chatRepository.saveMessage(role = "user", content = cleanPrompt)
        }

        // 2. Send prompt to POST /api/chat
        val chatResult = api.sendChat(
            ChatRequest(
                prompt = cleanPrompt,
                history = historyToSend
            )
        )

        when (chatResult) {
            is NovaApiResult.HttpError -> {
                CoordinatorResult.Error(
                    message = "Backend error (${chatResult.statusCode}): ${chatResult.message}"
                )
            }
            is NovaApiResult.NetworkFailure -> {
                CoordinatorResult.Error(
                    message = "Network failure: ${chatResult.message}",
                    cause = chatResult.error
                )
            }
            is NovaApiResult.Success -> {
                val response = chatResult.data
                when (response.responseType) {
                    "text_reply" -> {
                        // Direct conversational response
                        if (response.reply.isNotBlank()) {
                            chatRepository.saveMessage(role = "assistant", content = response.reply)
                        }
                        CoordinatorResult.Success(
                            reply = response.reply,
                            toolExecuted = null,
                            wasCancelled = false
                        )
                    }
                    "tool_request" -> {
                        if (response.toolCalls.isEmpty()) {
                            return@withLock CoordinatorResult.Error(
                                "Backend returned 'tool_request' but tool_calls list was empty."
                            )
                        }

                        // Dispatch local tool execution
                        val toolCall = response.toolCalls.first()
                        val outcome = executeTool(toolCall)

                        // 3. Submit tool result to POST /api/chat/tool-result
                        val toolResultRequest = ToolResultRequest(
                            prompt = cleanPrompt,
                            toolCall = ToolCallPayload(
                                callId = toolCall.callId,
                                toolName = toolCall.toolName,
                                arguments = toolCall.arguments
                            ),
                            toolResult = outcome.resultPayload,
                            history = historyToSend
                        )

                        val followUpResult = api.sendToolResult(toolResultRequest)
                        when (followUpResult) {
                            is NovaApiResult.HttpError -> {
                                CoordinatorResult.Error(
                                    message = "Failed to submit tool result (${followUpResult.statusCode}): ${followUpResult.message}"
                                )
                            }
                            is NovaApiResult.NetworkFailure -> {
                                CoordinatorResult.Error(
                                    message = "Network failure submitting tool result: ${followUpResult.message}",
                                    cause = followUpResult.error
                                )
                            }
                            is NovaApiResult.Success -> {
                                val finalReply = followUpResult.data.reply
                                if (finalReply.isNotBlank()) {
                                    chatRepository.saveMessage(role = "assistant", content = finalReply)
                                }
                                CoordinatorResult.Success(
                                    reply = finalReply,
                                    toolExecuted = toolCall.toolName,
                                    wasCancelled = outcome.wasCancelled
                                )
                            }
                        }
                    }
                    else -> {
                        CoordinatorResult.Error(
                            "Unexpected response_type from backend: '${response.responseType}'"
                        )
                    }
                }
            }
        }
    }
}

    // ── Safe Local Tool Execution ──────────────────────────────────────────────

    private data class ToolExecutionOutcome(
        val resultPayload: JsonObject,
        val toolName: String,
        val isSuccess: Boolean,
        val wasCancelled: Boolean = false
    )

    private suspend fun executeTool(toolCall: ToolCallRequest): ToolExecutionOutcome {
        return when (toolCall.toolName) {
            "create_note" -> executeCreateNote(toolCall)
            "search_notes" -> executeSearchNotes(toolCall)
            "get_note" -> executeGetNote(toolCall)
            "update_note" -> executeUpdateNote(toolCall)
            "delete_note" -> executeDeleteNote(toolCall)
            else -> ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Unknown tool: '${toolCall.toolName}'"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    private suspend fun executeCreateNote(toolCall: ToolCallRequest): ToolExecutionOutcome {
        val title = parseString(toolCall.arguments["title"])?.trim()
        if (title.isNullOrEmpty()) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Missing or blank 'title' argument for create_note"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        val content = parseString(toolCall.arguments["content"])?.trim() ?: ""
        return try {
            val noteId = notesRepository.create_note(title, content)
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.createNoteSuccess(noteId),
                toolName = toolCall.toolName,
                isSuccess = true
            )
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Failed to create note: ${e.message}"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    private suspend fun executeSearchNotes(toolCall: ToolCallRequest): ToolExecutionOutcome {
        val query = parseString(toolCall.arguments["query"])?.trim()
        if (query.isNullOrEmpty()) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Missing or blank 'query' argument for search_notes"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        return try {
            val matchingNotes = notesRepository.search_notes(query)
            val resultJson = buildJsonObject {
                put("success", true)
                put("count", matchingNotes.size)
                put("notes", buildJsonArray {
                    matchingNotes.forEach { note ->
                        add(buildJsonObject {
                            put("id", note.id)
                            put("title", note.title)
                            put("content", note.content)
                        })
                    }
                })
            }
            ToolExecutionOutcome(
                resultPayload = resultJson,
                toolName = toolCall.toolName,
                isSuccess = true
            )
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Failed to search notes: ${e.message}"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    private suspend fun executeGetNote(toolCall: ToolCallRequest): ToolExecutionOutcome {
        val id = parseId(toolCall.arguments["id"])
        if (id == null || id < 1) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Invalid or missing 'id' argument for get_note"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        return try {
            val note = notesRepository.get_note(id)
            val resultJson = if (note != null) {
                ToolResultPayloads.getNoteSuccess(note.id, note.title, note.content)
            } else {
                ToolResultPayloads.getNoteNotFound()
            }
            ToolExecutionOutcome(
                resultPayload = resultJson,
                toolName = toolCall.toolName,
                isSuccess = (note != null)
            )
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Failed to retrieve note: ${e.message}"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    private suspend fun executeUpdateNote(toolCall: ToolCallRequest): ToolExecutionOutcome {
        val id = parseId(toolCall.arguments["id"])
        if (id == null || id < 1) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Invalid or missing 'id' argument for update_note"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        val title = parseString(toolCall.arguments["title"])?.trim()
        if (title.isNullOrEmpty()) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Missing or blank 'title' argument for update_note"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        val content = parseString(toolCall.arguments["content"])?.trim() ?: ""
        return try {
            val success = notesRepository.update_note(id, title, content)
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.updateNoteResult(success, id),
                toolName = toolCall.toolName,
                isSuccess = success
            )
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Failed to update note: ${e.message}"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    private suspend fun executeDeleteNote(toolCall: ToolCallRequest): ToolExecutionOutcome {
        val id = parseId(toolCall.arguments["id"])
        if (id == null || id < 1) {
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Invalid or missing 'id' argument for delete_note"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }

        // Destructive operation: require explicit confirmation
        val confirmed = try {
            confirmationProvider.requestConfirmation(
                toolName = "delete_note",
                message = "Are you sure you want to delete note #$id?",
                targetId = id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Confirmation failed or threw an exception - NEVER delete the note!
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.cancellationResult("Confirmation failed: ${e.message ?: "unknown error"}"),
                toolName = toolCall.toolName,
                isSuccess = false,
                wasCancelled = true
            )
        }

        if (!confirmed) {
            // User cancelled / declined: send documented cancellation payload without deleting
            return ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.cancellationResult("User declined confirmation"),
                toolName = toolCall.toolName,
                isSuccess = false,
                wasCancelled = true
            )
        }

        return try {
            val deleted = notesRepository.delete_note(id)
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.deleteNoteResult(deleted, id),
                toolName = toolCall.toolName,
                isSuccess = deleted
            )
        } catch (e: Exception) {
            ToolExecutionOutcome(
                resultPayload = ToolResultPayloads.executionError("Failed to delete note: ${e.message}"),
                toolName = toolCall.toolName,
                isSuccess = false
            )
        }
    }

    // ── Argument Parsing Utilities ─────────────────────────────────────────────

    private fun parseId(element: JsonElement?): Long? {
        if (element == null || element !is JsonPrimitive) return null
        return element.longOrNull ?: element.content.toLongOrNull()
    }

    private fun parseString(element: JsonElement?): String? {
        if (element == null || element !is JsonPrimitive) return null
        return element.content
    }
}

package com.nova.personalagent.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ============================================================================
 * Backend Network DTOs
 * ============================================================================
 * Strongly-typed Kotlin models corresponding 1-to-1 with Pydantic models
 * defined in backend/main.py and backend/tools.py.
 *
 * All snake_case JSON field names are explicitly mapped with @SerialName.
 */

/**
 * A single turn in the conversation history.
 * Mirrors Pydantic ChatMessage in backend/main.py.
 */
@Serializable
data class ChatMessageDto(
    @SerialName("role")
    val role: String,

    @SerialName("content")
    val content: String
)

/**
 * Input payload for POST /api/chat.
 * Mirrors Pydantic ChatRequest in backend/main.py.
 */
@Serializable
data class ChatRequest(
    @SerialName("prompt")
    val prompt: String,

    @SerialName("history")
    val history: List<ChatMessageDto> = emptyList(),

    @SerialName("system_instruction")
    val systemInstruction: String? = null
)

/**
 * Structured request instructing Android to execute a local tool.
 * Mirrors Pydantic ToolCallRequest in backend/tools.py.
 */
@Serializable
data class ToolCallRequest(
    @SerialName("call_id")
    val callId: String,

    @SerialName("tool_name")
    val toolName: String,

    @SerialName("arguments")
    val arguments: JsonObject = JsonObject(emptyMap()),

    @SerialName("requires_confirmation")
    val requiresConfirmation: Boolean = false
)

/**
 * Output payload from POST /api/chat and POST /api/chat/tool-result.
 * Mirrors Pydantic ChatResponse in backend/main.py.
 */
@Serializable
data class ChatResponse(
    @SerialName("response_type")
    val responseType: String = "text_reply",

    @SerialName("reply")
    val reply: String = "",

    @SerialName("model")
    val model: String,

    @SerialName("tool_calls")
    val toolCalls: List<ToolCallRequest> = emptyList(),

    @SerialName("status")
    val status: String = "success"
)

/**
 * Identifies the tool invocation that was executed.
 * Mirrors Pydantic ToolCallPayload in backend/main.py.
 */
@Serializable
data class ToolCallPayload(
    @SerialName("call_id")
    val callId: String,

    @SerialName("tool_name")
    val toolName: String,

    @SerialName("arguments")
    val arguments: JsonObject = JsonObject(emptyMap())
)

/**
 * Input payload for POST /api/chat/tool-result after Android executes a tool.
 * Mirrors Pydantic ToolResultRequest in backend/main.py.
 */
@Serializable
data class ToolResultRequest(
    @SerialName("prompt")
    val prompt: String,

    @SerialName("tool_call")
    val toolCall: ToolCallPayload,

    @SerialName("tool_result")
    val toolResult: JsonObject,

    @SerialName("history")
    val history: List<ChatMessageDto> = emptyList(),

    @SerialName("system_instruction")
    val systemInstruction: String? = null
)

/**
 * Output payload from GET /health.
 * Mirrors Pydantic HealthResponse in backend/main.py.
 */
@Serializable
data class HealthResponse(
    @SerialName("status")
    val status: String,

    @SerialName("gemini_configured")
    val geminiConfigured: Boolean,

    @SerialName("model")
    val model: String,

    @SerialName("version")
    val version: String
)

/**
 * Convenience builder helpers for constructing tool_result JsonObject payloads
 * sent back to POST /api/chat/tool-result.
 */
object ToolResultPayloads {

    fun createNoteSuccess(noteId: Long): JsonObject = buildJsonObject {
        put("success", true)
        put("note_id", noteId)
    }

    fun getNoteSuccess(id: Long, title: String, content: String): JsonObject = buildJsonObject {
        put("success", true)
        put("note", buildJsonObject {
            put("id", id)
            put("title", title)
            put("content", content)
        })
    }

    fun getNoteNotFound(): JsonObject = buildJsonObject {
        put("success", false)
        put("error", "Note not found")
    }

    fun updateNoteResult(success: Boolean, id: Long): JsonObject = buildJsonObject {
        put("success", success)
        put("id", id)
    }

    fun deleteNoteResult(success: Boolean, id: Long): JsonObject = buildJsonObject {
        put("success", success)
        put("id", id)
    }

    fun cancellationResult(message: String = "User declined confirmation"): JsonObject = buildJsonObject {
        put("success", false)
        put("cancelled", true)
        put("message", message)
    }

    fun executionError(errorMessage: String): JsonObject = buildJsonObject {
        put("success", false)
        put("error", errorMessage)
    }
}

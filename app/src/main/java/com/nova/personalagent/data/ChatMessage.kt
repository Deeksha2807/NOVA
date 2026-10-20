package com.nova.personalagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Room Entity: ChatMessage
 * ============================================================================
 * Represents a single turn in the NOVA conversation history.
 * Stored in the "chat_messages" table (added in database version 2).
 *
 * Fields:
 *  - id         : Auto-generated primary key.
 *  - role       : Who sent the message — "user" or "assistant".
 *  - content    : The text of the message.
 *  - timestamp  : Creation time in epoch milliseconds (used for chronological ordering).
 *
 * Design notes:
 *  - No API keys, credentials, or sensitive data are stored here.
 *  - The table name "chat_messages" is distinct from "notes" to prevent any
 *    accidental overlap in queries or migrations.
 *  - Timestamp ordering (ASC) is used so the UI can render oldest messages first,
 *    matching the natural reading direction of a chat thread.
 */
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** "user" for messages the human sent; "assistant" for NOVA's replies. */
    val role: String,

    /** The full text of the message turn. */
    val content: String,

    /** Epoch milliseconds when this message was created. Defaults to now. */
    val timestamp: Long = System.currentTimeMillis()
)

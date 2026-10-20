package com.nova.personalagent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * DAO: ChatMessageDao
 * ============================================================================
 * Defines all database interactions for the ChatMessage entity.
 * Room generates the SQL implementation at compile-time via KSP.
 *
 * Conventions:
 *  - suspend functions for one-shot writes.
 *  - Flow for reactive reads so the UI automatically reflects new messages.
 *  - Chronological ASC ordering mirrors natural chat reading order.
 *  - clearAll() only touches "chat_messages" — the "notes" table is never affected.
 */
@Dao
interface ChatMessageDao {

    /**
     * Inserts a new chat message into the database.
     * IGNORE strategy is chosen because messages are append-only; there is no
     * meaningful conflict scenario (each row has a unique auto-generated id).
     *
     * @return The auto-generated row ID of the inserted message.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    /**
     * Emits the full chat history in chronological order (oldest first).
     * Room re-emits automatically whenever any row in "chat_messages" changes,
     * so the UI stays in sync without manual refresh calls.
     */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    /**
     * One-shot fetch of all chat messages in chronological order.
     * Useful for building the history list to send to the backend API.
     */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesOnce(): List<ChatMessage>

    /**
     * Deletes every row from the chat_messages table.
     * This operation is completely isolated to chat history and does NOT
     * touch the notes table in any way.
     */
    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

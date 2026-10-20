package com.nova.personalagent.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * Repository: ChatRepository
 * ============================================================================
 * Mediates between the chat UI / ViewModel and the Room database for
 * conversation history. Mirrors the same architectural conventions as
 * NotesRepository:
 *  - All database I/O is dispatched onto [ioDispatcher] (default: Dispatchers.IO).
 *  - Reactive [Flow] for live UI observation.
 *  - suspend functions for one-shot writes.
 *  - Clear-history operation is strictly scoped to chat_messages.
 *
 * This repository has no knowledge of the notes table and does not depend on
 * NotesRepository or NoteDao in any way.
 */
class ChatRepository(
    private val chatMessageDao: ChatMessageDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ── Reactive stream ────────────────────────────────────────────────────────

    /**
     * Emits the full chat history in chronological order (oldest first).
     * Collects on [ioDispatcher] so Room never touches the main thread.
     * Compose / ViewModel callers can use .collectAsState() on this directly.
     */
    val allMessages: Flow<List<ChatMessage>> =
        chatMessageDao.getAllMessages().flowOn(ioDispatcher)

    // ── Write operations ───────────────────────────────────────────────────────

    /**
     * Persists a single chat turn.
     *
     * @param role    "user" for the human side, "assistant" for NOVA's reply.
     * @param content The full text of the message.
     * @return        The auto-generated row ID of the saved message.
     */
    suspend fun saveMessage(role: String, content: String): Long =
        withContext(ioDispatcher) {
            chatMessageDao.insert(
                ChatMessage(role = role, content = content)
            )
        }

    // ── Read operations ────────────────────────────────────────────────────────

    /**
     * One-shot fetch of all messages in chronological order.
     * Intended for building the history list before an API call.
     *
     * @return List of [ChatMessage] rows, oldest first.
     */
    suspend fun getMessagesOnce(): List<ChatMessage> =
        withContext(ioDispatcher) {
            chatMessageDao.getAllMessagesOnce()
        }

    // ── Maintenance ────────────────────────────────────────────────────────────

    /**
     * Deletes all chat messages.
     * Only chat_messages rows are affected — notes are completely untouched.
     */
    suspend fun clearHistory(): Unit =
        withContext(ioDispatcher) {
            chatMessageDao.clearAll()
        }
}

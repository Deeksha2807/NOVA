package com.nova.personalagent.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * Room Component 4: Repository
 * ============================================================================
 * A Repository mediates between different data sources (Room database, network, etc.)
 * and the rest of the app (UI or AI agent).
 *
 * Responsibilities:
 * - Provides a clean API to the rest of the application.
 * - Hides the database implementation details from the UI layer.
 * - Ensures all database reads and writes execute off the main UI thread
 *   using Kotlin Coroutines and `Dispatchers.IO`.
 * - Provides tool-oriented functions (`create_note`, `search_notes`, etc.)
 *   ready for the NOVA Agentic AI layer in Milestone 4.
 */
class NotesRepository(
    private val noteDao: NoteDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ── Reactive Data Streams (for UI) ──────────────────────────────────────────

    /**
     * Flow emitting the complete list of notes ordered by updated time.
     * Room automatically emits new values whenever the "notes" table changes.
     */
    val allNotes: Flow<List<Note>> = noteDao.getAllNotes().flowOn(ioDispatcher)

    /**
     * Reactive search query returning matching notes based on title or content.
     */
    fun searchNotesFlow(query: String): Flow<List<Note>> {
        return noteDao.searchNotes(query).flowOn(ioDispatcher)
    }

    // ── Standard CRUD Operations (Off-main-thread) ──────────────────────────────

    suspend fun insert(note: Note): Long = withContext(ioDispatcher) {
        noteDao.insertNote(note)
    }

    suspend fun update(note: Note) = withContext(ioDispatcher) {
        noteDao.updateNote(note)
    }

    suspend fun delete(note: Note) = withContext(ioDispatcher) {
        noteDao.deleteNote(note)
    }

    suspend fun deleteById(id: Long): Boolean = withContext(ioDispatcher) {
        noteDao.deleteNoteById(id) > 0
    }

    // ── Agentic AI Tools (Milestone 4 Ready) ──────────────────────────────────
    // These functions match the tool specifications for the upcoming AI Agent layer:
    //   create_note(title, content)
    //   search_notes(query)
    //   get_note(id)
    //   update_note(id, title, content)
    //   delete_note(id)

    /**
     * Tool: create_note(title, content)
     * Creates and stores a new note with the specified title and content.
     * @return The auto-generated ID of the newly created note.
     */
    suspend fun create_note(title: String, content: String): Long = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val note = Note(
            title = title.trim(),
            content = content.trim(),
            createdAt = now,
            updatedAt = now
        )
        noteDao.insertNote(note)
    }

    /**
     * Tool: search_notes(query)
     * Searches notes by matching keywords in title or content.
     * @return List of matching Note objects.
     */
    suspend fun search_notes(query: String): List<Note> = withContext(ioDispatcher) {
        noteDao.searchNotesOnce(query.trim())
    }

    /**
     * Tool: get_note(id)
     * Retrieves a single note by its ID.
     * @return The Note if found, or null if it does not exist.
     */
    suspend fun get_note(id: Long): Note? = withContext(ioDispatcher) {
        noteDao.getNoteById(id)
    }

    /**
     * Tool: update_note(id, title, content)
     * Updates an existing note's title and content.
     * @return True if the note was found and updated, false otherwise.
     */
    suspend fun update_note(id: Long, title: String, content: String): Boolean = withContext(ioDispatcher) {
        val existingNote = noteDao.getNoteById(id) ?: return@withContext false
        val updatedNote = existingNote.copy(
            title = title.trim(),
            content = content.trim(),
            updatedAt = System.currentTimeMillis()
        )
        noteDao.updateNote(updatedNote)
        true
    }

    /**
     * Tool: delete_note(id)
     * Deletes a note by its ID.
     * @return True if the note was deleted, false if no note with that ID was found.
     */
    suspend fun delete_note(id: Long): Boolean = withContext(ioDispatcher) {
        noteDao.deleteNoteById(id) > 0
    }
}

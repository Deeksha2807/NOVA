package com.nova.personalagent.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * Room Component 2: DAO (Data Access Object)
 * ============================================================================
 * The DAO is an interface that defines all database interactions for the Note entity.
 * Room generates the actual SQL queries and implementation methods at compile-time.
 *
 * Room features used:
 * - `suspend`: Kotlin Coroutine support ensures database work runs off the main UI thread.
 * - `Flow`: Asynchronous data stream that automatically emits updated lists whenever
 *   the data in the "notes" table changes.
 */
@Dao
interface NoteDao {

    /**
     * Inserts a new note into the database.
     * @return The auto-generated row ID (Long) of the inserted note.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    /**
     * Updates an existing note in the database matching its primary key id.
     */
    @Update
    suspend fun updateNote(note: Note)

    /**
     * Deletes a specific note from the database.
     */
    @Delete
    suspend fun deleteNote(note: Note)

    /**
     * Deletes a note by its unique ID.
     * @return Number of rows affected (1 if deleted, 0 if not found).
     */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long): Int

    /**
     * Retrieves all notes ordered by most recently updated first.
     * Returns a Flow so the UI receives real-time updates when notes change.
     */
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<Note>>

    /**
     * Retrieves a single note by its unique ID.
     */
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Long): Note?

    /**
     * Searches notes by matching text in either title OR content.
     * Returns a reactive Flow for dynamic search in the UI.
     */
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchNotes(query: String): Flow<List<Note>>

    /**
     * One-shot search notes by query for tool calls.
     */
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchNotesOnce(query: String): List<Note>
}

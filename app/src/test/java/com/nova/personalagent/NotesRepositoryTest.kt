package com.nova.personalagent

import com.nova.personalagent.data.Note
import com.nova.personalagent.data.NoteDao
import com.nova.personalagent.data.NotesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fake implementation of NoteDao for fast, isolated repository unit tests.
 */
class FakeNoteDao : NoteDao {
    private val notes = mutableListOf<Note>()
    private var nextId = 1L

    override suspend fun insertNote(note: Note): Long {
        val assignedId = if (note.id == 0L) nextId++ else note.id
        val newNote = note.copy(id = assignedId)
        notes.removeAll { it.id == assignedId }
        notes.add(newNote)
        return assignedId
    }

    override suspend fun updateNote(note: Note) {
        val index = notes.indexOfFirst { it.id == note.id }
        if (index != -1) {
            notes[index] = note
        }
    }

    override suspend fun deleteNote(note: Note) {
        notes.removeAll { it.id == note.id }
    }

    override suspend fun deleteNoteById(id: Long): Int {
        val removed = notes.removeAll { it.id == id }
        return if (removed) 1 else 0
    }

    override fun getAllNotes(): Flow<List<Note>> {
        return flowOf(notes.sortedByDescending { it.updatedAt })
    }

    override suspend fun getNoteById(id: Long): Note? {
        return notes.find { it.id == id }
    }

    override fun searchNotes(query: String): Flow<List<Note>> {
        val filtered = notes.filter {
            it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }
        return flowOf(filtered)
    }

    override suspend fun searchNotesOnce(query: String): List<Note> {
        return notes.filter {
            it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }
    }
}

/**
 * Unit tests verifying NotesRepository and the tool-oriented functions
 * prepared for Milestone 4:
 * - create_note
 * - search_notes
 * - get_note
 * - update_note
 * - delete_note
 */
class NotesRepositoryTest {
    private lateinit var fakeDao: FakeNoteDao
    private lateinit var repository: NotesRepository

    @Before
    fun setUp() {
        fakeDao = FakeNoteDao()
        repository = NotesRepository(fakeDao)
    }

    @Test
    fun testCreateAndGetNoteTool() = runBlocking {
        val id = repository.create_note("Buy groceries", "Milk, Eggs, Bread")
        assertTrue("Created note ID should be > 0", id > 0)

        val note = repository.get_note(id)
        assertNotNull("Note should exist in database", note)
        assertEquals("Buy groceries", note?.title)
        assertEquals("Milk, Eggs, Bread", note?.content)
    }

    @Test
    fun testUpdateNoteTool() = runBlocking {
        val id = repository.create_note("Meeting", "Discuss project")
        val success = repository.update_note(id, "Meeting Updated", "Discuss project roadmap")
        assertTrue("update_note should return true on success", success)

        val note = repository.get_note(id)
        assertEquals("Meeting Updated", note?.title)
        assertEquals("Discuss project roadmap", note?.content)
    }

    @Test
    fun testDeleteNoteTool() = runBlocking {
        val id = repository.create_note("Temporary", "Delete soon")
        val deleted = repository.delete_note(id)
        assertTrue("delete_note should return true", deleted)

        val note = repository.get_note(id)
        assertNull("Note should be deleted", note)
    }

    @Test
    fun testSearchNotesTool() = runBlocking {
        repository.create_note("Doctor Appointment", "Dentist at 3pm")
        repository.create_note("Shopping", "Buy apples and milk")

        val results = repository.search_notes("Dentist")
        assertEquals(1, results.size)
        assertEquals("Doctor Appointment", results.first().title)

        val milkResults = repository.search_notes("milk")
        assertEquals(1, milkResults.size)
        assertEquals("Shopping", milkResults.first().title)
    }
}

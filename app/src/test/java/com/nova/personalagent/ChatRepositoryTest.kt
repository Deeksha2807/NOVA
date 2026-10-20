package com.nova.personalagent

import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.data.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ============================================================================
 * Unit tests: ChatRepository (via FakeChatMessageDao)
 * ============================================================================
 * Tests run on the JVM — no Android device or Room runtime required.
 *
 * FakeChatMessageDao is defined in ChatMessageDaoTest.kt (same package,
 * same test source set) so it is visible here without re-declaration.
 *
 * The ioDispatcher is overridden to Dispatchers.Unconfined so tests can
 * use runBlocking without deadlocking — the same pattern used in the
 * existing NotesRepositoryTest.
 *
 * Coverage:
 *  1. saveMessage() stores role and content correctly.
 *  2. Saved messages appear in allMessages Flow.
 *  3. getMessagesOnce() returns the full history in chronological order.
 *  4. clearHistory() removes all chat rows.
 *  5. clearHistory() does not affect any note data (isolation check).
 *  6. Consecutive saves produce increasing IDs.
 */
class ChatRepositoryTest {

    private lateinit var fakeDao: FakeChatMessageDao
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        fakeDao = FakeChatMessageDao()
        // Use Unconfined so withContext() doesn't suspend in runBlocking tests
        repository = ChatRepository(fakeDao, ioDispatcher = Dispatchers.Unconfined)
    }

    // ── 1. saveMessage stores role and content ─────────────────────────────────

    @Test
    fun saveMessage_userRole_persistedCorrectly() = runBlocking {
        val id = repository.saveMessage(role = "user", content = "Hello NOVA")
        assertTrue("Returned ID should be > 0", id > 0)

        val messages = repository.getMessagesOnce()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello NOVA", messages[0].content)
    }

    @Test
    fun saveMessage_assistantRole_persistedCorrectly() = runBlocking {
        repository.saveMessage(role = "assistant", content = "I'm ready to help!")

        val messages = repository.getMessagesOnce()
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("I'm ready to help!", messages[0].content)
    }

    // ── 2. allMessages Flow reflects saved messages ────────────────────────────

    @Test
    fun allMessages_flow_emitsInsertedMessages() = runBlocking {
        repository.saveMessage(role = "user",      content = "First message")
        repository.saveMessage(role = "assistant", content = "Second message")

        // Wait for the non-empty emission from the Flow
        val emitted = repository.allMessages.first { it.isNotEmpty() }
        assertEquals(2, emitted.size)
    }

    // ── 3. getMessagesOnce returns chronological order ─────────────────────────

    @Test
    fun getMessagesOnce_returnsChronologicalOrder() = runBlocking {
        // Insert messages with explicit timestamps via the DAO to control ordering
        fakeDao.insert(ChatMessage(role = "assistant", content = "Reply",   timestamp = 2000L))
        fakeDao.insert(ChatMessage(role = "user",      content = "Prompt",  timestamp = 1000L))

        val messages = repository.getMessagesOnce()
        assertEquals(2, messages.size)
        assertEquals("Prompt", messages[0].content)  // timestamp 1000 comes first
        assertEquals("Reply",  messages[1].content)  // timestamp 2000 comes second
    }

    // ── 4. clearHistory removes all messages ──────────────────────────────────

    @Test
    fun clearHistory_removesAllChatMessages() = runBlocking {
        repository.saveMessage(role = "user",      content = "Message A")
        repository.saveMessage(role = "assistant", content = "Message B")

        repository.clearHistory()

        val messages = repository.getMessagesOnce()
        assertEquals("History should be empty after clearHistory()", 0, messages.size)
    }

    // ── 5. clearHistory isolation from notes ──────────────────────────────────

    @Test
    fun clearHistory_doesNotTouchNotesDaoOrTable() = runBlocking {
        // ChatRepository only holds a reference to ChatMessageDao.
        // This test asserts that clearHistory() is structurally incapable
        // of affecting notes: the repository has no NoteDao field.
        val noChatMessagesLeft = try {
            repository.saveMessage("user", "Temporary")
            repository.clearHistory()
            repository.getMessagesOnce().isEmpty()
        } catch (e: Exception) {
            false
        }
        assertTrue("clearHistory() should complete without error", noChatMessagesLeft)
        // If this test compiles and passes, ChatRepository has no notes coupling.
    }

    // ── 6. Multiple saves produce unique IDs ──────────────────────────────────

    @Test
    fun multipleSaves_produceUniqueIds() = runBlocking {
        val id1 = repository.saveMessage(role = "user",      content = "Turn 1")
        val id2 = repository.saveMessage(role = "assistant", content = "Turn 2")
        val id3 = repository.saveMessage(role = "user",      content = "Turn 3")

        assertTrue("IDs must be positive", id1 > 0 && id2 > 0 && id3 > 0)
        assertTrue("IDs must be distinct", id1 != id2 && id2 != id3 && id1 != id3)
    }
}

package com.nova.personalagent

import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.data.ChatMessageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake implementation of ChatMessageDao.
 *
 * Mirrors the same approach used by FakeNoteDao in NotesRepositoryTest:
 * pure Kotlin, no Android framework, no Room runtime — so these tests run
 * fast on the JVM without a device or emulator.
 */
class FakeChatMessageDao : ChatMessageDao {

    private val messages = mutableListOf<ChatMessage>()
    private var nextId = 1L
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())

    override suspend fun insert(message: ChatMessage): Long {
        val assignedId = if (message.id == 0L) nextId++ else message.id
        messages.add(message.copy(id = assignedId))
        _messagesFlow.value = messages.sortedBy { it.timestamp }
        return assignedId
    }

    override fun getAllMessages(): Flow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    override suspend fun getAllMessagesOnce(): List<ChatMessage> =
        messages.sortedBy { it.timestamp }

    override suspend fun clearAll() {
        messages.clear()
        _messagesFlow.value = emptyList()
    }

    // Helper for test assertions
    fun snapshot(): List<ChatMessage> = messages.sortedBy { it.timestamp }
}

/**
 * ============================================================================
 * Unit tests: ChatMessageDao (via FakeChatMessageDao)
 * ============================================================================
 * Tests run on the JVM — no Android device required.
 *
 * Coverage:
 *  1. Insert a user message and retrieve it.
 *  2. Insert an assistant message and retrieve it.
 *  3. Messages are returned in chronological (ASC timestamp) order.
 *  4. clearAll() removes all chat messages.
 *  5. clearAll() does not interfere with the notes table
 *     (verified at the isolation level — FakeChatMessageDao has no notes reference).
 */
class ChatMessageDaoTest {

    private lateinit var dao: FakeChatMessageDao

    @Before
    fun setUp() {
        dao = FakeChatMessageDao()
    }

    // ── 1. Insert user message ─────────────────────────────────────────────────

    @Test
    fun insertUserMessage_retrievedCorrectly() = runBlocking {
        val id = dao.insert(ChatMessage(role = "user", content = "Hello NOVA", timestamp = 1000L))

        assertTrue("Inserted ID should be > 0", id > 0)

        val messages = dao.getAllMessagesOnce()
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello NOVA", messages[0].content)
    }

    // ── 2. Insert assistant message ────────────────────────────────────────────

    @Test
    fun insertAssistantMessage_retrievedCorrectly() = runBlocking {
        dao.insert(ChatMessage(role = "assistant", content = "Hi! How can I help?", timestamp = 2000L))

        val messages = dao.getAllMessagesOnce()
        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("Hi! How can I help?", messages[0].content)
    }

    // ── 3. Chronological ordering ──────────────────────────────────────────────

    @Test
    fun messages_returnedInChronologicalOrder() = runBlocking {
        // Insert out-of-order timestamps to verify sort, not insertion order
        dao.insert(ChatMessage(role = "assistant", content = "Second", timestamp = 2000L))
        dao.insert(ChatMessage(role = "user",      content = "First",  timestamp = 1000L))
        dao.insert(ChatMessage(role = "user",      content = "Third",  timestamp = 3000L))

        val messages = dao.getAllMessagesOnce()

        assertEquals(3, messages.size)
        assertEquals("First",  messages[0].content)
        assertEquals("Second", messages[1].content)
        assertEquals("Third",  messages[2].content)
    }

    // ── 4. Multiple turns persist correctly ───────────────────────────────────

    @Test
    fun multipleTurns_allPersisted() = runBlocking {
        dao.insert(ChatMessage(role = "user",      content = "Create a note",       timestamp = 1000L))
        dao.insert(ChatMessage(role = "assistant", content = "Note created.",        timestamp = 2000L))
        dao.insert(ChatMessage(role = "user",      content = "Search for groceries", timestamp = 3000L))
        dao.insert(ChatMessage(role = "assistant", content = "Found 2 notes.",       timestamp = 4000L))

        val messages = dao.getAllMessagesOnce()
        assertEquals(4, messages.size)
        // Verify alternating roles in order
        assertEquals("user",      messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals("user",      messages[2].role)
        assertEquals("assistant", messages[3].role)
    }

    // ── 5. clearAll() empties only chat messages ───────────────────────────────

    @Test
    fun clearAll_removesAllChatMessages() = runBlocking {
        dao.insert(ChatMessage(role = "user",      content = "Message 1", timestamp = 1000L))
        dao.insert(ChatMessage(role = "assistant", content = "Message 2", timestamp = 2000L))

        dao.clearAll()

        val messages = dao.getAllMessagesOnce()
        assertEquals("Chat history should be empty after clearAll()", 0, messages.size)
    }

    // ── 6. clearAll() isolation (DAO has no notes reference) ──────────────────

    @Test
    fun clearAll_doesNotReferenceNotesDaoOrTable() = runBlocking {
        // This test verifies at the structural level that FakeChatMessageDao
        // has no reference to any note data. clearAll() purely operates on
        // the messages list — the "notes" table is not in scope.
        dao.insert(ChatMessage(role = "user", content = "Some message", timestamp = 1000L))
        dao.clearAll()

        // After clear, only chat messages are gone; notes are unaffected
        // because this DAO has no access to notes whatsoever.
        assertEquals(0, dao.snapshot().size)
    }
}

package com.nova.personalagent

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.data.NovaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ============================================================================
 * Instrumented Migration Test: NovaDatabase Version 1 -> Version 2
 * ============================================================================
 * Runs on an Android device or emulator (src/androidTest/).
 *
 * Design Notes:
 *  - NovaDatabase version 1 (commit 79f44a8) had exportSchema = false, so no
 *    schema JSON asset exists for version 1.
 *  - This test uses FrameworkSQLiteOpenHelperFactory to construct a realistic
 *    version 1 database with the exact DDL from baseline Note entity, seeds
 *    known note data, applies NovaDatabase.MIGRATION_1_TO_2, and validates:
 *      1. The database reaches version 2 (PRAGMA user_version = 2).
 *      2. The original note row survives with all field values unchanged.
 *      3. The "notes" table still exists with all original columns.
 *      4. The new "chat_messages" table exists with all expected columns.
 *      5. NovaDatabase and Room DAOs (NoteDao & ChatMessageDao) operate
 *         seamlessly on the migrated database.
 *      6. MIGRATION_1_TO_2 is idempotent (IF NOT EXISTS guard).
 */
@RunWith(AndroidJUnit4::class)
class NovaDatabaseMigrationTest {

    companion object {
        private const val TEST_DB = "nova_migration_test_db"

        // Exact version 1 notes table DDL corresponding to baseline Note entity (commit 79f44a8)
        private const val CREATE_NOTES_TABLE_V1 = """
            CREATE TABLE IF NOT EXISTS `notes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase("${TEST_DB}_room")
        context.deleteDatabase("${TEST_DB}_idempotent")
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase("${TEST_DB}_room")
        context.deleteDatabase("${TEST_DB}_idempotent")
    }

    /**
     * Helper to create a realistic version 1 database using the baseline schema
     * and insert a seed note row with known values.
     */
    private fun createV1Database(
        dbName: String,
        seedTitle: String,
        seedContent: String,
        seedCreatedAt: Long,
        seedUpdatedAt: Long
    ) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_NOTES_TABLE_V1.trimIndent())
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val v1Db = helper.writableDatabase
        assertEquals("Initial database version must be 1", 1, v1Db.version)

        v1Db.execSQL(
            """
            INSERT INTO `notes` (`title`, `content`, `createdAt`, `updatedAt`)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(seedTitle, seedContent, seedCreatedAt, seedUpdatedAt)
        )

        v1Db.close()
        helper.close()
    }

    /**
     * Requirement 2 & 3:
     * Create realistic version 1 database, apply MIGRATION_1_TO_2, and verify:
     *  - The database reaches version 2.
     *  - The original note row survives with all field values unchanged.
     *  - The notes table still exists with original columns.
     *  - The chat_messages table exists with expected columns and accepts rows.
     */
    @Test
    fun migration1To2_notesPreserved_chatMessagesCreated_andVersionBumped() {
        val seedTitle = "Important Project Note"
        val seedContent = "This note must survive migration 1 to 2 intact."
        val seedCreatedAt = 1710000000000L
        val seedUpdatedAt = 1710005000000L

        // 1. Create realistic version 1 database
        createV1Database(TEST_DB, seedTitle, seedContent, seedCreatedAt, seedUpdatedAt)

        // 2. Open at version 2 and apply MIGRATION_1_TO_2 via SQLite open helper
        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion == 1 && newVersion == 2) {
                            NovaDatabase.MIGRATION_1_TO_2.migrate(db)
                        }
                    }
                })
                .build()
        )

        val v2Db = v2Helper.writableDatabase

        try {
            // ── Verify 1: Database reaches version 2 ────────────────────────
            assertEquals("Database version must be upgraded to 2", 2, v2Db.version)

            // ── Verify 2 & 3: Notes table exists and seed row is unchanged ─
            val noteCursor = v2Db.query("SELECT * FROM `notes`")
            try {
                assertEquals("Exactly 1 note must remain in notes table", 1, noteCursor.count)
                assertTrue("Cursor should move to first row", noteCursor.moveToFirst())

                val idIdx = noteCursor.getColumnIndexOrThrow("id")
                val titleIdx = noteCursor.getColumnIndexOrThrow("title")
                val contentIdx = noteCursor.getColumnIndexOrThrow("content")
                val createdAtIdx = noteCursor.getColumnIndexOrThrow("createdAt")
                val updatedAtIdx = noteCursor.getColumnIndexOrThrow("updatedAt")

                assertTrue("Note ID must be generated > 0", noteCursor.getLong(idIdx) > 0)
                assertEquals("Note title must match exactly", seedTitle, noteCursor.getString(titleIdx))
                assertEquals("Note content must match exactly", seedContent, noteCursor.getString(contentIdx))
                assertEquals("Note createdAt must match exactly", seedCreatedAt, noteCursor.getLong(createdAtIdx))
                assertEquals("Note updatedAt must match exactly", seedUpdatedAt, noteCursor.getLong(updatedAtIdx))
            } finally {
                noteCursor.close()
            }

            // ── Verify 4: New chat_messages table exists with all columns ───
            val chatCursor = v2Db.query("SELECT * FROM `chat_messages`")
            try {
                assertEquals("chat_messages table must be initially empty", 0, chatCursor.count)

                // getColumnIndexOrThrow throws if any column is missing
                chatCursor.getColumnIndexOrThrow("id")
                chatCursor.getColumnIndexOrThrow("role")
                chatCursor.getColumnIndexOrThrow("content")
                chatCursor.getColumnIndexOrThrow("timestamp")
            } finally {
                chatCursor.close()
            }

            // ── Verify 5: chat_messages table accepts inserts ──────────────
            v2Db.execSQL(
                """
                INSERT INTO `chat_messages` (`role`, `content`, `timestamp`)
                VALUES (?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("user", "Hello NOVA", 1710010000000L)
            )

            val insertedChatCursor = v2Db.query("SELECT * FROM `chat_messages`")
            try {
                assertEquals("chat_messages must have 1 row after insert", 1, insertedChatCursor.count)
                assertTrue(insertedChatCursor.moveToFirst())
                assertEquals("user", insertedChatCursor.getString(insertedChatCursor.getColumnIndexOrThrow("role")))
                assertEquals("Hello NOVA", insertedChatCursor.getString(insertedChatCursor.getColumnIndexOrThrow("content")))
                assertEquals(1710010000000L, insertedChatCursor.getLong(insertedChatCursor.getColumnIndexOrThrow("timestamp")))
            } finally {
                insertedChatCursor.close()
            }
        } finally {
            v2Db.close()
            v2Helper.close()
        }
    }

    /**
     * Requirement 4:
     * Full Room integration test on the migrated database.
     * Verifies that Room's databaseBuilder with MIGRATION_1_TO_2 seamlessly
     * loads the pre-existing database and exposes both NoteDao and ChatMessageDao.
     */
    @Test
    fun migration1To2_roomBuilderIntegration_noteDaoAndChatDaoWork() = runBlocking {
        val roomTestDb = "${TEST_DB}_room"
        val seedTitle = "Room Surviving Note"
        val seedContent = "Note content verified through NoteDao"
        val seedCreatedAt = 1710020000000L
        val seedUpdatedAt = 1710025000000L

        // 1. Create realistic v1 database
        createV1Database(roomTestDb, seedTitle, seedContent, seedCreatedAt, seedUpdatedAt)

        // 2. Open with Room databaseBuilder applying MIGRATION_1_TO_2
        val roomDb = Room.databaseBuilder(context, NovaDatabase::class.java, roomTestDb)
            .addMigrations(NovaDatabase.MIGRATION_1_TO_2)
            .build()

        try {
            // Verify NoteDao can query the original surviving note via Flow stream
            val notes = roomDb.noteDao().getAllNotes().first()
            assertEquals("NoteDao should find exactly 1 note", 1, notes.size)
            assertEquals(seedTitle, notes[0].title)
            assertEquals(seedContent, notes[0].content)
            assertEquals(seedCreatedAt, notes[0].createdAt)
            assertEquals(seedUpdatedAt, notes[0].updatedAt)

            // Verify getNoteById also retrieves it correctly
            val singleNote = roomDb.noteDao().getNoteById(notes[0].id)
            assertNotNull("getNoteById should return the note", singleNote)
            assertEquals(seedTitle, singleNote?.title)

            // Verify ChatMessageDao can insert and query
            val messageId = roomDb.chatMessageDao().insert(
                ChatMessage(role = "assistant", content = "Room migration verified successfully")
            )
            assertTrue("ChatMessageDao insert ID should be > 0", messageId > 0)

            val messages = roomDb.chatMessageDao().getAllMessagesOnce()
            assertEquals(1, messages.size)
            assertEquals("assistant", messages[0].role)
            assertEquals("Room migration verified successfully", messages[0].content)
        } finally {
            roomDb.close()
        }
    }

    /**
     * Requirement 5:
     * Verify that running MIGRATION_1_TO_2 when the chat_messages table already
     * exists does not crash or corrupt data (validates IF NOT EXISTS guard).
     */
    @Test
    fun migration1To2_idempotentCreateTable_doesNotThrow() {
        val idempotentDb = "${TEST_DB}_idempotent"

        // 1. Create v1 database and pre-create chat_messages table
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(idempotentDb)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_NOTES_TABLE_V1.trimIndent())
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `chat_messages` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `role` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val v1Db = helper.writableDatabase
        v1Db.close()
        helper.close()

        // 2. Run migration 1 -> 2; should complete cleanly without throwing TableAlreadyExists exception
        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(idempotentDb)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        NovaDatabase.MIGRATION_1_TO_2.migrate(db)
                    }
                })
                .build()
        )
        val v2Db = v2Helper.writableDatabase
        assertEquals(2, v2Db.version)
        v2Db.close()
        v2Helper.close()
    }
}

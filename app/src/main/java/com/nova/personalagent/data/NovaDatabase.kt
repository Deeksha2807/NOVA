package com.nova.personalagent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ============================================================================
 * Room Component 3: Database
 * ============================================================================
 * The database class is the main access point connecting the app to the
 * underlying SQLite file "nova_database".
 *
 * Key aspects:
 *  - Annotated with @Database listing every entity and the current schema version.
 *  - Must be an abstract class extending RoomDatabase.
 *  - Declares abstract accessor methods for each DAO.
 *  - Implements the Singleton pattern so only one connection is open at a time.
 *
 * ── Version history ──────────────────────────────────────────────────────────
 *  Version 1  (baseline, commit 79f44a8)
 *             Entities : Note
 *             Tables   : notes
 *
 *  Version 2  (Task 3B)
 *             Entities : Note, ChatMessage
 *             Tables   : notes (UNCHANGED), chat_messages (NEW)
 *             Migration: MIGRATION_1_TO_2 — creates chat_messages only.
 *                        The notes table is never dropped, cleared, or altered.
 */
@Database(
    entities = [Note::class, ChatMessage::class],
    version = 2,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {

        // @Volatile ensures changes by one thread are immediately visible to all threads.
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        // ── Migration: version 1 → 2 ─────────────────────────────────────────
        //
        // This migration ONLY creates the chat_messages table.
        // It does NOT touch the notes table in any way — no DROP, no ALTER,
        // no INSERT, no DELETE.  All existing note rows survive intact.
        //
        // Column types mirror the ChatMessage entity exactly:
        //   id        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
        //   role      TEXT    NOT NULL
        //   content   TEXT    NOT NULL
        //   timestamp INTEGER NOT NULL  (epoch millis; default matches entity default)
        //
        // IF NOT EXISTS is included as a belt-and-suspenders guard; in practice
        // Room will only run this migration once.
        val MIGRATION_1_TO_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id`        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `role`      TEXT    NOT NULL,
                        `content`   TEXT    NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Returns the singleton NovaDatabase instance.
         * Creates "nova_database" on first call; subsequent calls return the
         * cached instance without re-opening the file.
         *
         * Migration strategy:
         *  - addMigrations(MIGRATION_1_TO_2) provides an explicit, safe path
         *    from version 1 to version 2.
         *  - fallbackToDestructiveMigration has been removed.  If a future
         *    version bump is made without a matching Migration object, Room will
         *    throw an IllegalStateException at runtime rather than silently
         *    wiping user data.
         */
        fun getDatabase(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_database"           // database filename unchanged
                )
                    .addMigrations(MIGRATION_1_TO_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

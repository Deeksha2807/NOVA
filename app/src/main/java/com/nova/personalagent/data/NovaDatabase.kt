package com.nova.personalagent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ============================================================================
 * Room Component 3: Database
 * ============================================================================
 * The database class is the main access point connecting your app to the SQLite database.
 *
 * Key aspects:
 * - Annotated with @Database with list of entities and schema version.
 * - Must be an abstract class extending RoomDatabase.
 * - Declares abstract methods for each DAO.
 * - Implements the Singleton pattern so only one database connection is open
 *   at any time, preventing memory leaks and database locks.
 */
@Database(
    entities = [Note::class],
    version = 1,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        // @Volatile ensures that changes made by one thread are immediately visible to all threads.
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        /**
         * Returns the singleton instance of NovaDatabase.
         * Creates the database file "nova_database" if it doesn't exist yet.
         */
        fun getDatabase(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_database"
                )
                    // If schema version changes without a migration, recreate tables
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

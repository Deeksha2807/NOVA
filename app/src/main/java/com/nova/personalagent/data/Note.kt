package com.nova.personalagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Room Component 1: Entity
 * ============================================================================
 * An Entity represents a table in the Room database.
 * Each instance of this class corresponds to a single row in the "notes" table.
 *
 * - @Entity(tableName = "notes") instructs Room to store these objects in a table named "notes".
 * - @PrimaryKey(autoGenerate = true) tells SQLite to automatically generate a unique ID
 *   for every new note inserted (1, 2, 3, etc.).
 * - Fields:
 *     - id: Unique primary key (auto-generated)
 *     - title: Note headline / title
 *     - content: Note body text
 *     - createdAt: Timestamp when the note was created (epoch milliseconds)
 *     - updatedAt: Timestamp when the note was last modified (epoch milliseconds)
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

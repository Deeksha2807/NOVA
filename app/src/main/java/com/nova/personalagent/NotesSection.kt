package com.nova.personalagent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.personalagent.data.Note
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.ui.theme.NovaBackground
import com.nova.personalagent.ui.theme.NovaCardBorder
import com.nova.personalagent.ui.theme.NovaCyan
import com.nova.personalagent.ui.theme.NovaCyanDim
import com.nova.personalagent.ui.theme.NovaSurface
import com.nova.personalagent.ui.theme.NovaTextPrimary
import com.nova.personalagent.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog state for creating or editing a note.
 */
private sealed class NoteDialogState {
    object Dismissed : NoteDialogState()
    object Create : NoteDialogState()
    data class Edit(val note: Note) : NoteDialogState()
}

/**
 * The Notes UI section embedded within the NOVA home screen.
 *
 * Responsibilities:
 * - Displays saved notes retrieved from Room via [NotesRepository].
 * - Real-time keyword search across note title and content.
 * - Create, read/open, edit, and delete notes.
 * - Handles all database mutations off the main UI thread with Coroutines.
 */
@Composable
fun NotesSection(
    notesRepository: NotesRepository,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // ── Search & Filter State ────────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }

    // Collect notes reactively from Room via Repository Flow.
    // If the user entered a search query, stream filtered results; otherwise stream all notes.
    val notes by remember(searchQuery) {
        if (searchQuery.isBlank()) {
            notesRepository.allNotes
        } else {
            notesRepository.searchNotesFlow(searchQuery)
        }
    }.collectAsState(initial = emptyList())

    // ── Dialog States ────────────────────────────────────────────────────────
    var dialogState by remember { mutableStateOf<NoteDialogState>(NoteDialogState.Dismissed) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Section Header ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Notes",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = NovaTextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Badge showing total count
                Box(
                    modifier = Modifier
                        .background(color = NovaSurface, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${notes.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NovaCyan
                    )
                }
            }

            // "+ New Note" action button
            Button(
                onClick = { dialogState = NoteDialogState.Create },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaCyan,
                    contentColor = NovaBackground
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Note",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "New Note",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Search Input Bar ─────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Search notes by title or content...",
                    fontSize = 13.sp,
                    color = NovaTextSecondary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (searchQuery.isNotEmpty()) NovaCyan else NovaTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = NovaTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NovaTextPrimary,
                unfocusedTextColor = NovaTextPrimary,
                focusedContainerColor = NovaSurface,
                unfocusedContainerColor = NovaSurface,
                cursorColor = NovaCyan,
                focusedBorderColor = NovaCyan,
                unfocusedBorderColor = NovaCardBorder
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ── Notes List / Empty State ─────────────────────────────────────────
        if (notes.isEmpty()) {
            EmptyNotesCard(searchQuery = searchQuery)
        } else {
            notes.forEach { note ->
                NoteCard(
                    note = note,
                    onOpenNote = { dialogState = NoteDialogState.Edit(note) },
                    onEditNote = { dialogState = NoteDialogState.Edit(note) },
                    onDeleteNote = { noteToDelete = note }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // ── Create / Edit Dialog ─────────────────────────────────────────────────
    when (val state = dialogState) {
        is NoteDialogState.Create -> {
            NoteEditorDialog(
                titleText = "Create Note",
                confirmButtonText = "Save",
                initialTitle = "",
                initialContent = "",
                onDismiss = { dialogState = NoteDialogState.Dismissed },
                onConfirm = { title, content ->
                    coroutineScope.launch {
                        // Uses the agentic tool-compatible repository method off the UI thread
                        notesRepository.create_note(title, content)
                    }
                    dialogState = NoteDialogState.Dismissed
                }
            )
        }

        is NoteDialogState.Edit -> {
            NoteEditorDialog(
                titleText = "Edit Note",
                confirmButtonText = "Update",
                initialTitle = state.note.title,
                initialContent = state.note.content,
                onDismiss = { dialogState = NoteDialogState.Dismissed },
                onConfirm = { title, content ->
                    coroutineScope.launch {
                        // Uses the agentic tool-compatible repository method off the UI thread
                        notesRepository.update_note(state.note.id, title, content)
                    }
                    dialogState = NoteDialogState.Dismissed
                },
                onDelete = {
                    noteToDelete = state.note
                    dialogState = NoteDialogState.Dismissed
                }
            )
        }

        NoteDialogState.Dismissed -> { /* No-op */ }
    }

    // ── Delete Confirmation Dialog ───────────────────────────────────────────
    noteToDelete?.let { note ->
        DeleteConfirmationDialog(
            noteTitle = note.title.ifBlank { "Untitled Note" },
            onDismiss = { noteToDelete = null },
            onConfirmDelete = {
                coroutineScope.launch {
                    // Uses the agentic tool-compatible repository method off the UI thread
                    notesRepository.delete_note(note.id)
                }
                noteToDelete = null
            }
        )
    }
}

// ── Sub-Composables ──────────────────────────────────────────────────────────

/**
 * Individual Note display card with title, content preview, timestamp, and action buttons.
 */
@Composable
private fun NoteCard(
    note: Note,
    onOpenNote: () -> Unit,
    onEditNote: () -> Unit,
    onDeleteNote: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenNote() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NovaSurface),
        border = BorderStroke(width = 1.dp, color = NovaCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Title and action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled Note" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Edit button
                    IconButton(
                        onClick = onEditNote,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit note",
                            tint = NovaCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Delete button
                    IconButton(
                        onClick = onDeleteNote,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete note",
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Note Content preview (up to 3 lines)
            if (note.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note.content,
                    fontSize = 13.sp,
                    color = NovaTextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamp row
            Text(
                text = "Updated: ${formatTimestamp(note.updatedAt)}",
                fontSize = 11.sp,
                color = NovaTextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Shown when there are no notes or no matching search results.
 */
@Composable
private fun EmptyNotesCard(searchQuery: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NovaSurface),
        border = BorderStroke(width = 1.dp, color = NovaCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (searchQuery.isBlank()) "📝" else "🔍",
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (searchQuery.isBlank()) {
                    "No notes saved yet"
                } else {
                    "No notes matching \"$searchQuery\""
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = NovaTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (searchQuery.isBlank()) {
                    "Tap \"New Note\" above to create your first note."
                } else {
                    "Try checking your spelling or clear the search."
                },
                fontSize = 12.sp,
                color = NovaTextSecondary
            )
        }
    }
}

/**
 * Clean dialog for creating or editing a note.
 */
@Composable
private fun NoteEditorDialog(
    titleText: String,
    confirmButtonText: String,
    initialTitle: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(initialTitle) }
    var content by remember { mutableStateOf(initialContent) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NovaSurface,
        title = {
            Text(
                text = titleText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = NovaTextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Title field
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        errorMessage = null
                    },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Grocery List") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NovaTextPrimary,
                        unfocusedTextColor = NovaTextPrimary,
                        focusedBorderColor = NovaCyan,
                        unfocusedBorderColor = NovaCardBorder,
                        cursorColor = NovaCyan,
                        focusedLabelColor = NovaCyan,
                        unfocusedLabelColor = NovaTextSecondary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Content field
                OutlinedTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        errorMessage = null
                    },
                    label = { Text("Content") },
                    placeholder = { Text("Write your note here...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NovaTextPrimary,
                        unfocusedTextColor = NovaTextPrimary,
                        focusedBorderColor = NovaCyan,
                        unfocusedBorderColor = NovaCardBorder,
                        cursorColor = NovaCyan,
                        focusedLabelColor = NovaCyan,
                        unfocusedLabelColor = NovaTextSecondary
                    )
                )

                // Error validation message
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank() && content.isBlank()) {
                        errorMessage = "Please enter a title or content."
                    } else {
                        onConfirm(title, content)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NovaCyan,
                    contentColor = NovaBackground
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = confirmButtonText,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))
                    ) {
                        Text("Delete")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = NovaTextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * Confirmation dialog before deleting a note to prevent accidental loss.
 */
@Composable
private fun DeleteConfirmationDialog(
    noteTitle: String,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NovaSurface,
        title = {
            Text(
                text = "Delete Note?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = NovaTextPrimary
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete \"$noteTitle\"? This action cannot be undone.",
                fontSize = 14.sp,
                color = NovaTextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B6B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Delete", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = NovaTextSecondary)
            ) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * Formats epoch timestamp into a readable date and time string.
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

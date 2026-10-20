package com.nova.personalagent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.personalagent.data.ChatMessage
import com.nova.personalagent.ui.theme.NovaCardBorder
import com.nova.personalagent.ui.theme.NovaCyan
import com.nova.personalagent.ui.theme.NovaCyanDim
import com.nova.personalagent.ui.theme.NovaGreen
import com.nova.personalagent.ui.theme.NovaSurface
import com.nova.personalagent.ui.theme.NovaTextPrimary
import com.nova.personalagent.ui.theme.NovaTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * Chat Section
 * ============================================================================
 * Displays persisted conversation history, text input bar, sending state,
 * error banner, and destructive action confirmation dialog.
 *
 * Integrated into [HomeScreen] following the dark cyberpunk design palette.
 */
@Composable
fun ChatSection(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val canSend = inputText.isNotBlank() && !uiState.isSending
    val onSendClick: () -> Unit = {
        if (canSend) {
            val prompt = inputText.trim()
            inputText = ""
            keyboardController?.hide()
            viewModel.sendMessage(prompt)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 1. Section Header ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Conversation",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = NovaTextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Live status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (uiState.isSending) NovaCyan else NovaGreen,
                            shape = CircleShape
                        )
                )
            }

            if (uiState.messages.isNotEmpty()) {
                Text(
                    text = "${uiState.messages.size} turn${if (uiState.messages.size == 1) "" else "s"}",
                    fontSize = 12.sp,
                    color = NovaTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 2. Error Banner ──────────────────────────────────────────────────
        val error = uiState.errorMessage
        if (error != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = NovaSurface),
                border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "⚠️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            fontSize = 13.sp,
                            color = Color(0xFFFF8E8E),
                            maxLines = 3
                        )
                    }
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss", color = NovaCyan, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── 3. Messages List ─────────────────────────────────────────────────
        if (uiState.messages.isEmpty() && !uiState.isSending) {
            // Empty state placeholder
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NovaSurface),
                border = BorderStroke(1.dp, NovaCardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "💬",
                        fontSize = 28.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No conversation yet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NovaTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap the mic above or type below to create, search, or update notes.",
                        fontSize = 12.sp,
                        color = NovaTextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // Rendered conversation bubbles
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                uiState.messages.forEach { msg ->
                    ChatBubble(message = msg)
                }

                // In-flight progress indicator
                if (uiState.isSending) {
                    AssistantThinkingBubble()
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 4. Message Input Bar ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (uiState.isSending) "NOVA is processing..." else "Ask NOVA or take a note...",
                        color = NovaTextSecondary.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                },
                enabled = !uiState.isSending,
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = NovaSurface,
                    unfocusedContainerColor = NovaSurface,
                    disabledContainerColor = NovaSurface.copy(alpha = 0.6f),
                    focusedBorderColor = NovaCyan,
                    unfocusedBorderColor = NovaCardBorder,
                    disabledBorderColor = NovaCardBorder.copy(alpha = 0.4f),
                    focusedTextColor = NovaTextPrimary,
                    unfocusedTextColor = NovaTextPrimary,
                    cursorColor = NovaCyan
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendClick() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send icon button
            IconButton(
                onClick = onSendClick,
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (canSend) NovaCyan else NovaSurface,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (canSend) Color(0xFF0A0E1A) else NovaTextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // ── 5. Delete Confirmation Dialog ────────────────────────────────────────
    val pendingConfirmation = uiState.pendingConfirmation
    if (pendingConfirmation != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            containerColor = NovaSurface,
            titleContentColor = NovaTextPrimary,
            textContentColor = NovaTextSecondary,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confirm Deletion",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Text(
                    text = pendingConfirmation.message,
                    fontSize = 14.sp,
                    color = NovaTextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4D))
                ) {
                    Text(
                        text = "Delete",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissDelete() },
                    border = BorderStroke(1.dp, NovaCardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaTextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Individual chat message bubble with distinct user/assistant styling.
 */
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    val bubbleBackground = if (isUser) {
        Color(0xFF0F263E) // Dark navy-cyan for user
    } else {
        NovaSurface // Sleek surface for NOVA assistant
    }

    val borderColor = if (isUser) {
        NovaCyanDim.copy(alpha = 0.5f)
    } else {
        NovaCardBorder
    }

    val timeFormatted = remember(message.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            modifier = Modifier.widthIn(max = 310.dp),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bubbleBackground),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Header (Role + Time)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isUser) "You" else "NOVA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) NovaCyan else NovaGreen
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = timeFormatted,
                        fontSize = 10.sp,
                        color = NovaTextSecondary.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Message Text Content
                Text(
                    text = message.content,
                    fontSize = 14.sp,
                    color = NovaTextPrimary,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * Bubble displayed while NOVA is processing a prompt or executing a tool.
 */
@Composable
private fun AssistantThinkingBubble() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = NovaSurface),
            border = BorderStroke(1.dp, NovaCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = NovaCyan,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "NOVA is thinking...",
                    fontSize = 13.sp,
                    color = NovaTextSecondary
                )
            }
        }
    }
}

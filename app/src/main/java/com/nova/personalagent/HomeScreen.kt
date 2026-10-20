package com.nova.personalagent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.nova.personalagent.data.ChatRepository
import com.nova.personalagent.data.NovaDatabase
import com.nova.personalagent.data.NotesRepository
import com.nova.personalagent.network.NovaApiClient
import com.nova.personalagent.ui.theme.NOVATheme
import com.nova.personalagent.ui.theme.NovaBackground
import com.nova.personalagent.ui.theme.NovaCardBorder
import com.nova.personalagent.ui.theme.NovaCyan
import com.nova.personalagent.ui.theme.NovaCyanDim
import com.nova.personalagent.ui.theme.NovaCyanGlow
import com.nova.personalagent.ui.theme.NovaGreen
import com.nova.personalagent.ui.theme.NovaSurface
import com.nova.personalagent.ui.theme.NovaTextPrimary
import com.nova.personalagent.ui.theme.NovaTextSecondary

sealed class VoiceState {
    object Ready : VoiceState()
    object Listening : VoiceState()
    data class Result(val text: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
    object PermissionDenied : VoiceState()
}

data class ActivityItem(
    val icon: String,
    val title: String,
    val time: String
)

private val sampleActivities = listOf(
    ActivityItem("📅", "Set a reminder for team meeting", "2 min ago"),
    ActivityItem("🌤️", "Checked weather for tomorrow", "15 min ago"),
    ActivityItem("📧", "Drafted reply to Sara's email", "1 hour ago"),
    ActivityItem("🎵", "Played \"Chill Focus\" playlist", "3 hours ago"),
    ActivityItem("🔍", "Searched for nearby restaurants", "Yesterday")
)

@Composable
fun HomeScreen(
    chatViewModel: ChatViewModel? = null
) {
    var voiceState by remember {
        mutableStateOf<VoiceState>(VoiceState.Ready)
    }

    val context = LocalContext.current

    val database = remember {
        NovaDatabase.getDatabase(context)
    }

    val notesRepository = remember {
        NotesRepository(database.noteDao())
    }

    val chatRepository = remember {
        ChatRepository(database.chatMessageDao())
    }

    val api = remember {
        NovaApiClient(
            baseUrl = NovaApiClient.DEFAULT_BASE_URL
        )
    }

    val viewModel: ChatViewModel =
        chatViewModel ?: remember(context) {
            val storeOwner = context as? ViewModelStoreOwner

            if (storeOwner != null) {
                ViewModelProvider(
                    storeOwner,
                    ChatViewModel.Factory(
                        chatRepository,
                        notesRepository,
                        api
                    )
                )[ChatViewModel::class.java]
            } else {
                ChatViewModel.create(
                    chatRepository,
                    notesRepository,
                    api
                )
            }
        }

    val voiceManager = remember {
        VoiceRecognitionManager(
            context = context,
            onResult = { text ->
                voiceState = VoiceState.Result(text)
                viewModel.sendMessage(text)
            },
            onError = { message ->
                voiceState = VoiceState.Error(message)
            }
        )
    }

    DisposableEffect(voiceManager) {
        onDispose {
            voiceManager.destroy()
        }
    }

    val startVoiceRecognition: () -> Unit = {
        try {
            voiceState = VoiceState.Listening
            voiceManager.startListening()
        } catch (exception: Exception) {
            voiceState = VoiceState.Error(
                exception.localizedMessage
                    ?: "Unable to start speech recognition. Please try again."
            )
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startVoiceRecognition()
            } else {
                voiceState = VoiceState.PermissionDenied
            }
        }

    val onMicClick: () -> Unit = {
        if (voiceState is VoiceState.Listening) {
            voiceManager.stopListening()
            voiceState = VoiceState.Ready
        } else {
            val permissionGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                startVoiceRecognition()
            } else {
                permissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaBackground)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NovaCyanGlow,
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = 24.dp,
                    vertical = 48.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NovaHeader()

            Spacer(modifier = Modifier.height(32.dp))

            GreetingText()

            Spacer(modifier = Modifier.height(40.dp))

            MicButton(
                isListening = voiceState is VoiceState.Listening,
                onClick = onMicClick
            )

            Spacer(modifier = Modifier.height(20.dp))

            StatusChip(voiceState = voiceState)

            if (
                voiceState is VoiceState.Result ||
                voiceState is VoiceState.Error ||
                voiceState is VoiceState.PermissionDenied
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                VoiceResultCard(voiceState = voiceState)
            }

            Spacer(modifier = Modifier.height(36.dp))

            ChatSection(viewModel = viewModel)

            Spacer(modifier = Modifier.height(36.dp))

            NotesSection(
                notesRepository = notesRepository
            )

            Spacer(modifier = Modifier.height(36.dp))

            RecentActivitySection(
                activities = sampleActivities
            )
        }
    }
}

@Composable
private fun NovaHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NOVA",
            fontSize = 52.sp,
            fontWeight = FontWeight.ExtraBold,
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        NovaCyan,
                        NovaCyanDim
                    )
                )
            ),
            letterSpacing = 8.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Your Personal AI Agent",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = NovaTextSecondary,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun GreetingText() {
    Text(
        text = "How can I help you?",
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = NovaTextPrimary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun MicButton(
    isListening: Boolean,
    onClick: () -> Unit
) {
    val pulseDuration = if (isListening) 500 else 1000
    val pulseTarget = if (isListening) 1.28f else 1.18f

    val infiniteTransition =
        rememberInfiniteTransition(label = "micPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = pulseTarget,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = pulseDuration,
                easing = if (isListening) {
                    LinearEasing
                } else {
                    FastOutSlowInEasing
                }
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val glowColor = if (isListening) {
        NovaCyan.copy(alpha = 0.35f)
    } else {
        NovaCyanGlow
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor,
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NovaCyan,
                            NovaCyanDim
                        )
                    ),
                    shape = CircleShape
                )
                .clickable {
                    onClick()
                }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isListening) {
                        Icons.Filled.MicOff
                    } else {
                        Icons.Filled.Mic
                    },
                    contentDescription = if (isListening) {
                        "Stop listening"
                    } else {
                        "Start listening"
                    },
                    tint = NovaBackground,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isListening) {
                        "Tap to Stop"
                    } else {
                        "Tap to Talk"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NovaBackground
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    voiceState: VoiceState
) {
    val (dotColor, labelText) = when (voiceState) {
        VoiceState.Ready ->
            NovaGreen to "Ready"

        VoiceState.Listening ->
            NovaCyan to "Listening..."

        is VoiceState.Result ->
            NovaGreen to "Done"

        is VoiceState.Error ->
            Color(0xFFFF6B6B) to "Error"

        VoiceState.PermissionDenied ->
            Color(0xFFFF6B6B) to "No Permission"
    }

    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = NovaCardBorder,
                shape = RoundedCornerShape(50)
            )
            .background(
                color = NovaSurface,
                shape = RoundedCornerShape(50)
            )
            .padding(
                horizontal = 16.dp,
                vertical = 6.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = dotColor,
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = labelText,
                fontSize = 13.sp,
                color = NovaTextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun VoiceResultCard(
    voiceState: VoiceState
) {
    val emoji: String
    val title: String
    val body: String
    val accentColor: Color

    when (voiceState) {
        is VoiceState.Result -> {
            emoji = "💬"
            title = "You said:"
            body = voiceState.text
            accentColor = NovaCyan
        }

        is VoiceState.Error -> {
            emoji = "⚠️"
            title = "Recognition failed"
            body = voiceState.message
            accentColor = Color(0xFFFF6B6B)
        }

        VoiceState.PermissionDenied -> {
            emoji = "🎙️"
            title = "Microphone access denied"
            body = "Go to Settings → Apps → NOVA → Permissions and enable the Microphone."
            accentColor = Color(0xFFFF6B6B)
        }

        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = NovaSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 14.dp
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = emoji,
                    fontSize = 20.sp
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = body,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = NovaTextPrimary
            )
        }
    }
}

@Composable
private fun RecentActivitySection(
    activities: List<ActivityItem>
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Recent Activity",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = NovaTextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        activities.forEach { item ->
            ActivityCard(item = item)

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ActivityCard(
    item: ActivityItem
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = NovaSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = NovaCardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 14.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.icon,
                    fontSize = 24.sp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = NovaTextPrimary,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = item.time,
                fontSize = 11.sp,
                color = NovaTextSecondary
            )
        }
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true
)
@Composable
fun HomeScreenPreview() {
    NOVATheme {
        HomeScreen()
    }
}
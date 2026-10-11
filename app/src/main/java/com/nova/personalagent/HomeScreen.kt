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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nova.personalagent.ui.theme.NovaCyan
import com.nova.personalagent.ui.theme.NovaCyanDim
import com.nova.personalagent.ui.theme.NovaCyanGlow
import com.nova.personalagent.ui.theme.NovaGreen
import com.nova.personalagent.ui.theme.NovaTextPrimary
import com.nova.personalagent.ui.theme.NovaTextSecondary
import com.nova.personalagent.ui.theme.NOVATheme
import com.nova.personalagent.ui.theme.NovaSurface
import com.nova.personalagent.ui.theme.NovaBackground
import com.nova.personalagent.ui.theme.NovaCardBorder
import com.nova.personalagent.data.NovaDatabase
import com.nova.personalagent.data.NotesRepository

// ── Voice state ───────────────────────────────────────────────────────────────

/**
 * Represents every possible state the voice-recognition feature can be in.
 *
 * A sealed class means the compiler knows every possible variant, which lets us
 * use exhaustive `when` expressions (no `else` branch needed in most places).
 */
sealed class VoiceState {
    /** Idle – waiting for the user to tap the mic button. */
    object Ready : VoiceState()

    /** The microphone is active and the user should be speaking. */
    object Listening : VoiceState()

    /**
     * Recognition succeeded.
     * @param text The transcribed text returned by the recognizer.
     */
    data class Result(val text: String) : VoiceState()

    /**
     * Something went wrong.
     * @param message A human-readable description of the problem.
     */
    data class Error(val message: String) : VoiceState()

    /** The user denied the RECORD_AUDIO permission request. */
    object PermissionDenied : VoiceState()
}

// ── Data model for a single recent-activity item ─────────────────────────────

/**
 * Holds the display data for one row in the "Recent Activity" list.
 * This is a simple data class with no database or network logic.
 *
 * @param icon  An emoji used as a lightweight icon (no image assets needed).
 * @param title The action description shown in bold.
 * @param time  A human-readable relative time string.
 */
data class ActivityItem(
    val icon: String,
    val title: String,
    val time: String
)

// ── Static placeholder data ───────────────────────────────────────────────────

/**
 * Hard-coded list of placeholder recent-activity items.
 * In a future task these will come from a local database.
 */
private val sampleActivities = listOf(
    ActivityItem("📅", "Set a reminder for team meeting", "2 min ago"),
    ActivityItem("🌤️", "Checked weather for tomorrow", "15 min ago"),
    ActivityItem("📧", "Drafted reply to Sara's email",  "1 hour ago"),
    ActivityItem("🎵", "Played \"Chill Focus\" playlist",  "3 hours ago"),
    ActivityItem("🔍", "Searched for nearby restaurants",  "Yesterday"),
)

// ── Root composable shown in MainActivity ────────────────────────────────────

/**
 * The NOVA home screen.
 *
 * Layout (top → bottom):
 *   1. Header        – app name "NOVA" + subtitle
 *   2. Greeting      – friendly message
 *   3. Mic button    – large pulsing circle; label changes based on [VoiceState]
 *   4. Status chip   – text/colour changes based on [VoiceState]
 *   5. Voice result  – recognised text or error message (only shown when relevant)
 *   6. Recent Activity – scrollable list of [ActivityItem]s
 *
 * The screen is wrapped in a verticalScroll so it adapts to every phone size.
 */
@Composable
fun HomeScreen() {

    // ── State ────────────────────────────────────────────────────────────────

    // voiceState drives the UI.  We start in the Ready state.
    var voiceState by remember { mutableStateOf<VoiceState>(VoiceState.Ready) }

    // LocalContext gives us the Android Context Compose is running in.
    val context = LocalContext.current

    // Create the VoiceRecognitionManager once for the lifetime of this screen.
    // The `remember` block only runs on the first composition.
    val voiceManager = remember {
        VoiceRecognitionManager(
            context  = context,
            // ✅ Recognition succeeded — store the transcribed text.
            onResult = { text    -> voiceState = VoiceState.Result(text) },
            // ❌ Something went wrong — store the error message.
            onError  = { message -> voiceState = VoiceState.Error(message) }
        )
    }

    // DisposableEffect runs `onDispose` when HomeScreen leaves the Composition
    // (e.g. the Activity is destroyed or navigated away from).
    // This is the correct place to release the SpeechRecognizer so it doesn't
    // hold the microphone open in the background.
    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()   // 🔑 prevents resource/microphone leaks
        }
    }

    // ── Permission launcher ──────────────────────────────────────────────────

    // rememberLauncherForActivityResult handles the OS permission dialog.
    // The lambda is called back with `true` (granted) or `false` (denied).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission just granted — start listening immediately.
            voiceState = VoiceState.Listening
            voiceManager.startListening()
        } else {
            // User tapped "Deny" on the permission dialog.
            voiceState = VoiceState.PermissionDenied
        }
    }

    // ── Mic button click handler ─────────────────────────────────────────────

    // This function is called every time the mic button is tapped.
    val onMicClick: () -> Unit = {
        when (voiceState) {
            // If already listening, tapping again stops the recognizer early.
            is VoiceState.Listening -> {
                voiceManager.stopListening()
                voiceState = VoiceState.Ready
            }
            // Any other state → try to start listening.
            else -> {
                // Check whether RECORD_AUDIO permission has already been granted.
                val permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (permissionGranted) {
                    // Permission already granted — start listening right away.
                    voiceState = VoiceState.Listening
                    voiceManager.startListening()
                } else {
                    // Show the OS permission dialog; result arrives in permissionLauncher.
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    // Initialize Room Database repository for the Notes section
    val notesRepository = remember {
        val database = NovaDatabase.getDatabase(context)
        NotesRepository(database.noteDao())
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaBackground)   // dark navy background
    ) {
        // Subtle radial glow behind the mic button area (purely decorative)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(NovaCyanGlow, Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Main scrollable content column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── 1. Header ────────────────────────────────────────────────────
            NovaHeader()

            Spacer(modifier = Modifier.height(32.dp))

            // ── 2. Greeting ──────────────────────────────────────────────────
            GreetingText()

            Spacer(modifier = Modifier.height(40.dp))

            // ── 3. Microphone button ─────────────────────────────────────────
            // isListening changes the pulse speed, label, and icon.
            MicButton(
                isListening = voiceState is VoiceState.Listening,
                onClick     = onMicClick
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 4. Status chip ───────────────────────────────────────────────
            // The chip colour and label reflect the current VoiceState.
            StatusChip(voiceState = voiceState)

            // ── 5. Voice result / error card ─────────────────────────────────
            // Only visible when there is something meaningful to show.
            val showResultCard = voiceState is VoiceState.Result
                    || voiceState is VoiceState.Error
                    || voiceState is VoiceState.PermissionDenied

            if (showResultCard) {
                Spacer(modifier = Modifier.height(20.dp))
                VoiceResultCard(voiceState = voiceState)
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── 6. Notes section ─────────────────────────────────────────────
            NotesSection(notesRepository = notesRepository)

            Spacer(modifier = Modifier.height(36.dp))

            // ── 7. Recent Activity section ───────────────────────────────────
            RecentActivitySection(activities = sampleActivities)
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

/**
 * Displays the "NOVA" logo text and the "Your Personal AI Agent" subtitle.
 * Unchanged from the original implementation.
 */
@Composable
private fun NovaHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // App name with gradient text effect achieved via a gradient brush
        Text(
            text = "NOVA",
            fontSize = 52.sp,
            fontWeight = FontWeight.ExtraBold,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.horizontalGradient(
                    colors = listOf(NovaCyan, NovaCyanDim)
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

/**
 * Shows the friendly greeting message to the user.
 * Unchanged from the original implementation.
 */
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

/**
 * Large circular microphone button with a continuous pulse animation.
 *
 * Changes from the original:
 *  - Accepts [isListening] to adjust the pulse speed and button label.
 *  - Accepts [onClick] so the voice logic in [HomeScreen] can be triggered.
 *  - When [isListening] is true, the pulse is faster and the label reads
 *    "Tap to Stop" so the user knows they can cancel.
 *
 * @param isListening True while the SpeechRecognizer is active.
 * @param onClick     Called when the user taps the button.
 */
@Composable
private fun MicButton(isListening: Boolean, onClick: () -> Unit) {

    // Pulse speed and scale: faster/larger while actively listening
    val pulseDuration = if (isListening) 500 else 1000
    val pulseTarget   = if (isListening) 1.28f else 1.18f

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 1.0f,
        targetValue   = pulseTarget,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = pulseDuration,
                easing = if (isListening) LinearEasing else FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Glow colour: bright cyan while listening, dimmer when idle
    val glowColor = if (isListening) NovaCyan.copy(alpha = 0.35f) else NovaCyanGlow

    Box(contentAlignment = Alignment.Center) {
        // Outer pulsing glow ring
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulseScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Inner solid button circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(NovaCyan, NovaCyanDim)
                    ),
                    shape = CircleShape
                )
                .clickable { onClick() }   // triggers onMicClick in HomeScreen
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Icon switches between mic and mic-off while listening
                Icon(
                    imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Start listening",
                    tint     = NovaBackground,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text       = if (isListening) "Tap to Stop" else "Tap to Talk",
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color      = NovaBackground
                )
            }
        }
    }
}

/**
 * Small pill-shaped status chip below the mic button.
 *
 * Changes from the original:
 *  - Accepts [voiceState] so the dot colour and label can reflect what NOVA is doing.
 *
 * State → display mapping:
 *  - [VoiceState.Ready]           → green dot  + "Ready"
 *  - [VoiceState.Listening]       → cyan dot   + "Listening..."
 *  - [VoiceState.Result]          → green dot  + "Done"
 *  - [VoiceState.Error]           → red dot    + "Error"
 *  - [VoiceState.PermissionDenied]→ red dot    + "No Permission"
 */
@Composable
private fun StatusChip(voiceState: VoiceState) {

    // Choose dot colour and label text based on the current state
    val (dotColor, labelText) = when (voiceState) {
        is VoiceState.Ready           -> NovaGreen               to "Ready"
        is VoiceState.Listening       -> NovaCyan                to "Listening..."
        is VoiceState.Result          -> NovaGreen               to "Done"
        is VoiceState.Error           -> Color(0xFFFF6B6B)       to "Error"
        is VoiceState.PermissionDenied -> Color(0xFFFF6B6B)      to "No Permission"
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
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Coloured status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text       = labelText,
                fontSize   = 13.sp,
                color      = NovaTextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Card that displays the recognised text, an error message, or a
 * permission-denied explanation.
 *
 * This card is only shown when [voiceState] is [VoiceState.Result],
 * [VoiceState.Error], or [VoiceState.PermissionDenied] — controlled by the
 * parent [HomeScreen].
 *
 * @param voiceState The current voice state (must be Result, Error, or PermissionDenied).
 */
@Composable
private fun VoiceResultCard(voiceState: VoiceState) {

    // Determine the emoji, title, body text, and accent colour for this state
    val (emoji, title, body, accentColor) = when (voiceState) {
        is VoiceState.Result -> Quadruple(
            "💬",
            "You said:",
            voiceState.text,
            NovaCyan
        )
        is VoiceState.Error -> Quadruple(
            "⚠️",
            "Recognition failed",
            voiceState.message,
            Color(0xFFFF6B6B)   // soft red
        )
        is VoiceState.PermissionDenied -> Quadruple(
            "🎙️",
            "Microphone access denied",
            "Go to Settings → Apps → NOVA → Permissions and enable the Microphone.",
            Color(0xFFFF6B6B)
        )
        // This composable is only called for the three states above;
        // the remaining cases should never be reached.
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = NovaSurface),
        border   = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Header row: emoji + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = emoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text       = title,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = accentColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Body: the actual text content
            Text(
                text       = body,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium,
                color      = NovaTextPrimary
            )
        }
    }
}

/**
 * Simple 4-element tuple used to destructure display properties in
 * [VoiceResultCard].  Kotlin's [Triple] only holds three values.
 */
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ── Unchanged composables ─────────────────────────────────────────────────────

/**
 * "Recent Activity" section header + a vertically-stacked list of
 * [ActivityItem] cards.
 *
 * @param activities The list of activity items to display.
 */
@Composable
private fun RecentActivitySection(activities: List<ActivityItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Section header
        Text(
            text       = "Recent Activity",
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
            color      = NovaTextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // One card per activity item
        activities.forEach { item ->
            ActivityCard(item = item)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/**
 * A single rounded card representing one recent activity.
 *
 * @param item The [ActivityItem] data to display.
 */
@Composable
private fun ActivityCard(item: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = NovaSurface),
        border   = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = NovaCardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: emoji icon + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = item.icon, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text       = item.title,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color      = NovaTextPrimary,
                    maxLines   = 2
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: relative time
            Text(
                text     = item.time,
                fontSize = 11.sp,
                color    = NovaTextSecondary
            )
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    NOVATheme {
        HomeScreen()
    }
}

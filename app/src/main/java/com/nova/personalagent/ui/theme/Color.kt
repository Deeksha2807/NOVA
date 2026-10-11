package com.nova.personalagent.ui.theme

import androidx.compose.ui.graphics.Color

// ── NOVA Brand Palette ────────────────────────────────────────────────────────

// Deep navy backgrounds
val NovaBackground    = Color(0xFF0A0E1A)   // very dark navy – main background
val NovaSurface       = Color(0xFF131929)   // slightly lighter – cards / surfaces
val NovaCardBorder    = Color(0xFF1E2A45)   // subtle border for cards

// Cyan / electric-blue accent
val NovaCyan          = Color(0xFF00D4FF)   // primary accent (mic button glow, highlights)
val NovaCyanDim       = Color(0xFF0099BB)   // darker variant for gradients
val NovaCyanGlow      = Color(0x3300D4FF)   // transparent glow layer

// Text colours
val NovaTextPrimary   = Color(0xFFE8F4FF)   // almost-white for headings
val NovaTextSecondary = Color(0xFF7A9BBF)   // muted blue-grey for subtitles / captions

// Status indicator
val NovaGreen         = Color(0xFF00E5A0)   // "Ready" dot colour

// Legacy Material colours (kept so the existing Theme.kt compiles unchanged)
val Purple80          = Color(0xFFD0BCFF)
val PurpleGrey80      = Color(0xFFCCC2DC)
val Pink80            = Color(0xFFEFB8C8)
val Purple40          = Color(0xFF6650A4)
val PurpleGrey40      = Color(0xFF625B71)
val Pink40            = Color(0xFF7D5260)
package com.nova.personalagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nova.personalagent.ui.theme.NOVATheme

/**
 * The single Activity that hosts the entire NOVA app.
 * It uses Jetpack Compose to render the UI.
 *
 * For this first task it simply shows [HomeScreen].
 * Additional screens (voice recognition, settings, etc.) will be added later.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge lets our dark background extend behind the status bar
        enableEdgeToEdge()

        setContent {
            NOVATheme {
                // Surface fills the whole screen with the theme background colour
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}
package com.shai.riven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shai.riven.ui.arcade.ArcadeApp
import com.shai.riven.ui.theme.RivenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RivenTheme {
                ArcadeApp()
            }
        }
    }
}

package com.shai.riven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shai.riven.ui.RivenApp
import com.shai.riven.ui.RivenUiAssets
import com.shai.riven.ui.theme.RivenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RivenTheme {
                RivenApp(uiAssets = RivenUiAssets.Approved)
            }
        }
    }
}

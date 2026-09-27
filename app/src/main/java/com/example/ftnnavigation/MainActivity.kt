package com.example.ftnnavigation

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ftnnavigation.ui.FtnApp
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fiksno, ne po sistemskoj temi: gore je tirkizna traka (bele ikonice), dole svetla površina.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            FTNNavigationTheme {
                FtnApp()
            }
        }
    }
}

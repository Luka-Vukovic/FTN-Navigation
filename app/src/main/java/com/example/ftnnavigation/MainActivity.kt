package com.example.ftnnavigation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ftnnavigation.ui.FtnApp
import com.example.ftnnavigation.ui.theme.FTNNavigationTheme

class MainActivity : ComponentActivity() {
    /** Mesto do koga treba prikazati rutu (dodir na obaveštenje "kreni"); null kad je obrađeno. */
    private var routeRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Posle okreta/obnove procesa intent je isti - ruta je već bila prikazana.
        if (savedInstanceState == null) routeRequest = routeTarget(intent)
        // Fiksno, ne po sistemskoj temi: gore je tirkizna traka (bele ikonice), dole svetla površina.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            FTNNavigationTheme {
                FtnApp(routeRequest = routeRequest, onRouteRequestHandled = { routeRequest = null })
            }
        }
    }

    /** Aplikacija je već otvorena (FLAG_ACTIVITY_SINGLE_TOP iz obaveštenja). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeTarget(intent)?.let { routeRequest = it }
    }

    /** Pokretanje iz nedavnih aplikacija ponavlja stari intent obaveštenja - tada ne. */
    private fun routeTarget(intent: Intent?): String? =
        intent?.takeIf { it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }?.getStringExtra(EXTRA_ROUTE_TO)

    companion object {
        const val EXTRA_ROUTE_TO = "route_to"
    }
}

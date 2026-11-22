package com.hjtk.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hjtk.app.ui.HjtkViewModel
import com.hjtk.app.ui.screens.MainScreen
import com.hjtk.app.ui.theme.Hjtk
import com.hjtk.app.ui.theme.HjtkTheme

/**
 * Single activity. Navigation is a tab index rather than a nav graph: the app
 * has four destinations and no deep links, so a graph would only add weight.
 */
open class MainActivity : ComponentActivity() {

    private var settingsSection by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The Settings screen shows the real state either way. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsSection = intent?.getStringExtra("settings_section")
        requestNotificationPermissionIfNeeded()
        setContent {
            val vm: HjtkViewModel = viewModel()
            val snapshot by vm.settings.collectAsStateWithLifecycleCompat()
            val dark = when (snapshot.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkThemeSafe()
            }
            HjtkTheme(dark = dark) {
                Surface(Modifier.fillMaxSize().background(Hjtk.palette.background)) {
                    MainScreen(
                        vm = vm,
                        startTab = if (settingsSection != null) 3 else 0
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        settingsSection = intent.getStringExtra("settings_section")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@androidx.compose.runtime.Composable
private fun isSystemInDarkThemeSafe(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat(): androidx.compose.runtime.State<T> =
    this.collectAsState()

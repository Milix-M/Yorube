package com.hermes.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.hermes.client.ui.navigation.NavGraph
import com.hermes.client.ui.theme.HermesAppTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            val streams = (application as HermesApp).container.runStreams
            streams.restore()
            streams.activeRuns.value.firstOrNull()?.let {
                com.hermes.client.data.stream.RunStreamService.start(this@MainActivity, it.runId, it.sessionId)
            }
        }
        val startSession = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        setContent {
            HermesAppTheme {
                NavGraph(startSessionId = startSession.ifBlank { null })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

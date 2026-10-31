package com.jobscout.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.jobscout.feature.search.presentation.ScoutViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobscout.app.navigation.ScoutApp
import com.jobscout.core.designsystem.JobScoutTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: ScoutViewModel by viewModels()
    private var sharedLink by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        if (savedInstanceState?.containsKey("pending-job-link") == true) sharedLink = savedInstanceState.getString("pending-job-link")
        else readSharedLink(intent)
        setContent { JobScoutTheme { ScoutApp(viewModel, sharedLink) { sharedLink = null } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readSharedLink(intent) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("pending-job-link", sharedLink); super.onSaveInstanceState(outState) }
    private fun readSharedLink(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(10_000)
            intent.removeExtra(Intent.EXTRA_TEXT); intent.action = Intent.ACTION_MAIN
        }
    }
}

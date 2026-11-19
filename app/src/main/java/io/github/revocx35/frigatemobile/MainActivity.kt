package io.github.revocx35.frigatemobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.revocx35.frigatemobile.data.AlertWorker
import io.github.revocx35.frigatemobile.ui.AppRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** A review item to open, from a tapped notification. */
    private val openReview = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as FrigateMobileApp).graph
        if (savedInstanceState == null) openReview.value = intent?.getStringExtra(AlertWorker.EXTRA_REVIEW_ID)
        setContent { AppRoot(graph, openReview) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(AlertWorker.EXTRA_REVIEW_ID)?.let { openReview.value = it }
    }
}

package com.eristhewerd.costco

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.eristhewerd.costco.notify.EventWorker
import com.eristhewerd.costco.ui.AppRoot
import com.eristhewerd.costco.ui.theme.CostcoTheme

class MainActivity : ComponentActivity() {
    /** Where a tapped notification wants to go; consumed by the nav host. */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute.value = intent.getStringExtra(EventWorker.EXTRA_OPEN)
        val api = (application as CostcoApp).api
        setContent {
            CostcoTheme {
                AppRoot(api, pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EventWorker.EXTRA_OPEN)?.let { pendingRoute.value = it }
    }
}

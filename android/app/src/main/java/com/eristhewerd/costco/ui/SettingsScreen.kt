package com.eristhewerd.costco.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Status
import com.eristhewerd.costco.data.friendlyMessage
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun prettyTime(iso: String?): String = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(it)
} ?: "never"

@Composable
fun SettingsScreen(api: Api, range: RangePreset, onLink: () -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad("/api/status") { cachedOnly ->
        if (cachedOnly) api.cached("/api/status", Status.serializer()) else api.get("/api/status", Status.serializer())
    }
    val (start, end) = range.bounds()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { api.exportCsv(start, end, it) }
            }
            Toast.makeText(
                context,
                result.fold({ "Exported" }, { "Export failed: ${it.friendlyMessage()}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val needsNotifPermission = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    LoadContent(load) { s ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Costco account", style = MaterialTheme.typography.titleMedium)
                    if (s.costcoLinked) {
                        Text("Linked${s.costcoEmail?.let { " as $it" } ?: ""}")
                        s.daysSinceLink?.let {
                            Text("Signed in $it days ago. Costco sign-ins last about 90 days.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("Not linked yet.")
                    }
                    s.authError?.let {
                        Text("Costco rejected the sign-in. Please link again.", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onLink, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (s.costcoLinked) "Sign in to Costco again" else "Link Costco account")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium)
                    Text("${s.receipts} receipts and ${s.orders} online orders on file")
                    Text("Last successful sync: ${prettyTime(s.lastSuccessfulSync)}")
                    s.lastSync?.error?.takeIf { s.authError == null }?.let {
                        Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val msg = runCatching { api.syncNow() }
                                .fold({ "Sync started. New data shows up in a minute or two." }, { it.friendlyMessage() })
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            load.reload()
                        }
                    }, enabled = s.costcoLinked) { Text("Sync now") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Text("One row per item, for ${range.label.lowercase()}.")
                    OutlinedButton(onClick = {
                        exporter.launch("costco-${start ?: "all"}-to-${end ?: "now"}.csv")
                    }) { Text("Export CSV") }
                }
            }

            if (needsNotifPermission) {
                OutlinedButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Turn on notifications")
                }
            }

            Text("Signed in to ${api.prefs.server} as ${s.user}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch { api.logout(); onSignedOut() } }) { Text("Sign out") }
        }
    }
}

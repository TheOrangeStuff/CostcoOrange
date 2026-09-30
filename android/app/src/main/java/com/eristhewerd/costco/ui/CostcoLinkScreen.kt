package com.eristhewerd.costco.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.AppJson
import com.eristhewerd.costco.data.friendlyMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.resume

private const val START_URL = "https://www.costco.com/OrderStatusCmd"

// Costco's site signs in with Azure AD B2C through MSAL.js, which keeps its refresh token in
// localStorage under a key containing "refreshtoken". We read it after you sign in and hand it
// to our server, which uses it to sync. Your Costco password never leaves Costco's page.
private const val MSAL_CLIENT_ID = "a3a5186b-7c89-4b4c-93a8-dd604e930757"
private val READ_TOKENS_JS = """
    (function() {
      var out = [];
      [window.localStorage, window.sessionStorage].forEach(function(store) {
        for (var i = 0; i < store.length; i++) {
          var k = store.key(i);
          if (k.toLowerCase().indexOf('refreshtoken') < 0) continue;
          try {
            var v = JSON.parse(store.getItem(k));
            if (v && v.secret) out.push({ secret: v.secret, clientId: v.clientId || null });
          } catch (e) {}
        }
      });
      return JSON.stringify(out);
    })()
""".trimIndent()

@Serializable
private data class FoundToken(val secret: String, val clientId: String? = null)

private suspend fun WebView.readTokens(): List<FoundToken> = suspendCancellableCoroutine { cont ->
    evaluateJavascript(READ_TOKENS_JS) { raw ->
        val tokens = runCatching {
            // evaluateJavascript hands back the returned string JSON-encoded.
            val json = AppJson.decodeFromString(String.serializer(), raw)
            AppJson.decodeFromString(ListSerializer(FoundToken.serializer()), json)
        }.getOrDefault(emptyList())
        cont.resume(tokens)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CostcoLinkScreen(api: Api, onDone: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var message by remember { mutableStateOf("Sign in to Costco below. We'll link your account automatically.") }
    var busy by remember { mutableStateOf(false) }
    var failedToken by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun link(secret: String, clientId: String?) {
        busy = true
        message = "Linking your Costco account…"
        try {
            val email = api.linkCostco(secret, clientId)
            message = "Linked${email?.let { " as $it" } ?: ""}. Your history is downloading on the server."
            delay(1500)
            onDone()
        } catch (e: Exception) {
            failedToken = secret
            message = "Couldn't link: ${e.friendlyMessage()}"
        } finally {
            busy = false
        }
    }

    // Poll the page once signed in; MSAL writes the token a moment after the redirect lands.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            if (busy || wv.url?.contains("costco.com") != true) continue
            val tokens = wv.readTokens()
            val pick = tokens.firstOrNull { it.clientId == MSAL_CLIENT_ID } ?: tokens.firstOrNull()
            if (pick != null && pick.secret != failedToken) link(pick.secret, pick.clientId)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = { showPaste = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Having trouble? Paste a token instead")
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    webView = this
                    // Start from a clean slate: leftover cookies from earlier attempts pile up until
                    // costco.com rejects the request with "Header Overflow".
                    WebStorage.getInstance().deleteAllData()
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptThirdPartyCookies(this, true)
                    cookies.removeAllCookies { cookies.flush(); loadUrl(START_URL) }
                }
            },
        )
    }

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Paste refresh token") },
            text = {
                Column {
                    Text(
                        "On a computer signed in to costco.com: DevTools → Application → Local Storage → " +
                            "www.costco.com, find the key containing \"refreshtoken\" and copy its \"secret\" value.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(text, { text = it }, label = { Text("Token") },
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaste = false
                    scope.launch { link(text.trim(), null) }
                }, enabled = text.isNotBlank()) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
        )
    }
}

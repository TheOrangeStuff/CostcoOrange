package com.eristhewerd.costco.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Status
import com.eristhewerd.costco.data.friendlyMessage
import com.eristhewerd.costco.notify.EventWorker
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("overview", "Spending", Icons.Default.BarChart),
    Tab("items", "Items", Icons.Default.ShoppingBasket),
    Tab("savings", "Savings", Icons.Default.Savings),
    Tab("receipts", "Receipts", Icons.AutoMirrored.Filled.ReceiptLong),
)

@Composable
fun AppRoot(api: Api, pendingRoute: String?, onRouteConsumed: () -> Unit) {
    var signedIn by remember { mutableStateOf(api.signedIn) }
    if (!signedIn) {
        LoginScreen(api) { signedIn = true }
    } else {
        MainScaffold(api, pendingRoute, onRouteConsumed) { signedIn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(api: Api, pendingRoute: String?, onRouteConsumed: () -> Unit, onSignedOut: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var range by remember {
        mutableStateOf(runCatching { RangePreset.valueOf(api.prefs.range) }.getOrDefault(RangePreset.TWELVE_MONTHS))
    }
    val setRange: (RangePreset) -> Unit = { range = it; api.prefs.range = it.name }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        EventWorker.schedule(context)
        // Nothing to look at until the server has a Costco sign-in.
        val status = runCatching { api.get("/api/status", Status.serializer()) }.getOrNull()
        if (status != null && !status.costcoLinked) nav.navigate("link")
        runCatching { EventWorker.check(context, api.prefs.lastEventId) }
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigate(it) }
        onRouteConsumed()
    }
    // A 401 from any screen clears the token; bounce back to the login screen.
    LaunchedEffect(backStack) { if (!api.signedIn) onSignedOut() }

    val isTab = tabs.any { it.route == route }
    val title = when {
        route == "settings" -> "Settings"
        route == "link" -> "Link Costco"
        route == "gas" -> "Gas"
        route?.startsWith("item/") == true -> "Item"
        route?.startsWith("receipt/") == true || route?.startsWith("order/") == true -> "Receipt"
        else -> tabs.firstOrNull { it.route == route }?.label ?: "Costco"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!isTab) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isTab) IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "overview", modifier = Modifier.padding(padding)) {
            val openItem: (String) -> Unit = { nav.navigate("item/$it") }
            composable("overview") {
                OverviewScreen(api, range, setRange, openItem, onGas = { nav.navigate("gas") }) {
                    nav.navigate("receipt/$it")
                }
            }
            composable("gas") { GasScreen(api, range, setRange) { nav.navigate("receipt/$it") } }
            composable("items") { ItemsScreen(api, range, setRange, openItem) }
            composable("savings") { SavingsScreen(api, openItem) }
            composable("receipts") {
                ReceiptsScreen(api, range, setRange) { t ->
                    nav.navigate(if (t.kind == "online") "order/${t.id}" else "receipt/${t.id}")
                }
            }
            composable("settings") {
                SettingsScreen(api, range, onLink = { nav.navigate("link") }, onSignedOut = {
                    EventWorker.cancel(context)
                    onSignedOut()
                })
            }
            composable("link") { CostcoLinkScreen(api) { nav.popBackStack() } }
            composable("item/{n}") { ItemDetailScreen(api, it.arguments?.getString("n").orEmpty()) }
            composable("receipt/{id}") {
                ReceiptDetailScreen(api, "receipt", it.arguments?.getString("id").orEmpty(), openItem)
            }
            composable("order/{id}") {
                ReceiptDetailScreen(api, "order", it.arguments?.getString("id").orEmpty(), openItem)
            }
        }
    }
}

@Composable
private fun LoginScreen(api: Api, onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(api.prefs.server) }
    var username by remember { mutableStateOf(api.prefs.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Costco Tracker", style = MaterialTheme.typography.headlineMedium)
            Text("Sign in to the family server.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(server, { server = it.trim() }, label = { Text("Server") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            api.login(server.trimEnd('/'), username.trim(), password)
                            api.prefs.lastEventId = -1
                            onSignedIn()
                        } catch (e: Exception) {
                            error = e.friendlyMessage()
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        }
    }
}

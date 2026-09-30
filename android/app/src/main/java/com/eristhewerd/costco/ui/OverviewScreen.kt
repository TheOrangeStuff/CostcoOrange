package com.eristhewerd.costco.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Category
import com.eristhewerd.costco.data.Overview
import kotlinx.coroutines.launch

@Composable
fun OverviewScreen(api: Api, range: RangePreset, onRange: (RangePreset) -> Unit) {
    val path = "/api/summary" + range.query()
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, Overview.serializer()) else api.get(path, Overview.serializer())
    }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Category?>(null) }

    Column {
        RangeChips(range, onRange)
        LoadContent(load) { data ->
            val s = data.summary
            val maxCategory = data.categories.maxOfOrNull { it.spend }?.coerceAtLeast(1.0) ?: 1.0
            LazyColumn {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("Total spent", money(s.totalSpend), Modifier.fillMaxWidth(),
                            sub = "Warehouse ${wholeMoney(s.warehouseSpend)} · Online ${wholeMoney(s.onlineSpend)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Trips", s.trips.toString(), Modifier.weight(1f),
                                sub = "${money(s.avgPerTrip)} avg")
                            StatCard("Online orders", s.onlineOrders.toString(), Modifier.weight(1f))
                            StatCard("Unique items", s.uniqueItems.toString(), Modifier.weight(1f),
                                sub = "${units(s.units)} bought")
                        }
                        if (s.gasSpend != 0.0) {
                            Text("Gas & car wash (not counted above): ${money(s.gasSpend)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.dataFrom != null) {
                            Text("History on file: ${prettyDate(s.dataFrom)} – ${prettyDate(s.dataTo)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (data.monthly.isNotEmpty()) {
                    item { SectionTitle("By month") }
                    item { MonthlyBars(data.monthly) }
                }
                if (data.categories.isNotEmpty()) {
                    item {
                        SectionTitle("By department", Modifier.padding(top = 16.dp))
                        Text(
                            "Costco doesn't publish department names. Tap one to name it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(data.categories) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = c.department != null) { editing = c }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row {
                                Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(money(c.spend), style = MaterialTheme.typography.bodyLarge)
                            }
                            ShareBar((c.spend / maxCategory).toFloat(), Modifier.padding(vertical = 4.dp))
                            if (c.samples.isNotEmpty()) {
                                Text(
                                    c.samples.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    editing?.let { c ->
        var text by remember(c) { mutableStateOf(if (c.labeled) c.label else "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Name department ${c.department}") },
            text = {
                Column {
                    if (c.samples.isNotEmpty()) Text("Includes: ${c.samples.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") },
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val number = c.department ?: return@TextButton
                    scope.launch {
                        runCatching { api.labelDepartment(number, text) }
                        editing = null
                        load.reload()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

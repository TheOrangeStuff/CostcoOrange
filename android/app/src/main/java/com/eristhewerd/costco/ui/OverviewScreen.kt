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
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Basket
import com.eristhewerd.costco.data.Category
import com.eristhewerd.costco.data.Inflation
import com.eristhewerd.costco.data.Newness
import com.eristhewerd.costco.data.Overview
import com.eristhewerd.costco.ui.theme.CostcoRed
import com.eristhewerd.costco.ui.theme.NewAmber
import com.eristhewerd.costco.ui.theme.SavingsGreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(
    api: Api,
    range: RangePreset,
    onRange: (RangePreset) -> Unit,
    onItem: (String) -> Unit,
    onGas: () -> Unit,
    onReceipt: (String) -> Unit,
) {
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
                            StatCard("Gas & car wash", money(s.gasSpend), Modifier.fillMaxWidth().clickable(onClick = onGas),
                                sub = "Not counted above · tap for gallons and prices")
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
                if (data.basket.trips > 0) {
                    item { SectionTitle("Trips", Modifier.padding(top = 16.dp)) }
                    item { TripsSection(data.basket, onReceipt) }
                }
                if (data.inflation.itemsCompared >= MIN_INFLATION_ITEMS || data.inflation.monthly.size >= 3) {
                    item { SectionTitle("Your prices", Modifier.padding(top = 16.dp)) }
                    item { PricesSection(data.inflation, onItem) }
                }
                if (data.newness.monthly.isNotEmpty()) {
                    item { SectionTitle("New vs. repeat", Modifier.padding(top = 16.dp)) }
                    item { NewnessSection(data.newness, range) }
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

// Fewer shared items than this and the period-over-period figure is mostly noise.
private const val MIN_INFLATION_ITEMS = 5

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier)
}

@Composable
private fun TripsSection(b: Basket, onReceipt: (String) -> Unit) {
    Column {
        val maxTrips = b.buckets.maxOfOrNull { it.trips }?.coerceAtLeast(1) ?: 1
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Typical trip", money(b.medianTotal), Modifier.weight(1f), sub = "${money(b.avgTotal)} avg")
                StatCard("Items per trip", units(b.medianItems), Modifier.weight(1f), sub = "%.1f avg".format(b.avgItems))
                b.medianDaysBetween?.let {
                    StatCard("Shop every", "${it.roundToInt()} days", Modifier.weight(1f), sub = "typically")
                }
            }
            b.buckets.forEach { k ->
                Column {
                    Row {
                        Text(k.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${k.trips} trips · ${wholeMoney(k.spend)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    ShareBar(k.trips.toFloat() / maxTrips, Modifier.padding(vertical = 4.dp))
                }
            }
        }
        b.biggest?.let { t ->
            ListItem(
                headlineContent = { Text("Biggest trip: ${money(t.total)}") },
                supportingContent = {
                    Text(listOfNotNull(prettyDate(t.date), t.warehouse, "${units(t.items)} items").joinToString(" · "))
                },
                modifier = Modifier.clickable { onReceipt(t.barcode) },
            )
        }
    }
}

@Composable
private fun PricesSection(inf: Inflation, onItem: (String) -> Unit) {
    Column {
        val change = inf.changePct
        if (change != null && inf.itemsCompared >= MIN_INFLATION_ITEMS) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    when {
                        change > 0 -> "Prices on what you buy are up %.1f%%".format(change)
                        change < 0 -> "Prices on what you buy are down %.1f%%".format(-change)
                        else -> "Prices on what you buy haven't moved"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (change > 0) CostcoRed else if (change < 0) SavingsGreen else Color.Unspecified,
                )
                Caption(
                    "${inf.itemsCompared} items you bought both since ${prettyDate(inf.periodStart)} and in the " +
                        "same stretch before (${prettyDate(inf.priorStart)} – ${prettyDate(inf.priorEnd)}) cost " +
                        "${money(inf.paid)} on the shelf, vs. ${money(inf.atPriorPrices)} at the earlier prices. " +
                        "Shelf prices, before instant savings.",
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
        if (inf.monthly.size >= 3) {
            PercentLine(inf.monthly.map { it.month }, inf.monthly.map { it.vsUsualPct }) { i ->
                val m = inf.monthly[i]
                "${monthLabel(m.month, true)}: " + when {
                    m.vsUsualPct > 0 -> "%.1f%% above your usual prices".format(m.vsUsualPct)
                    m.vsUsualPct < 0 -> "%.1f%% below your usual prices".format(-m.vsUsualPct)
                    else -> "at your usual prices"
                }
            }
            Caption("Each item's shelf price vs. its average across all your history, for things you've bought more than once.",
                Modifier.padding(horizontal = 16.dp))
        }
        if (change != null && inf.itemsCompared >= MIN_INFLATION_ITEMS) {
            inf.drivers.take(5).forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name ?: d.itemNumber, maxLines = 2) },
                    supportingContent = { Text("${money(d.priorPrice)} → ${money(d.price)}") },
                    trailingContent = {
                        Text(
                            (if (d.impact > 0) "+" else "−") + money(kotlin.math.abs(d.impact)),
                            color = if (d.impact > 0) CostcoRed else SavingsGreen,
                        )
                    },
                    modifier = Modifier.clickable { onItem(d.itemNumber) },
                )
            }
        }
    }
}

@Composable
private fun NewnessSection(n: Newness, range: RangePreset) {
    Column {
        n.newShare?.let { share ->
            Text(
                "%.0f%% of spending went to things you'd never bought before".format(share) +
                    " (${n.newItems} new items, ${wholeMoney(n.newSpend)})",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val rangeStart = range.bounds().first
        if (n.countedFrom != null && (rangeStart == null || rangeStart < n.countedFrom)) {
            Caption("Counted from ${prettyDate(n.countedFrom)}: in the first 3 months of history everything looks new.",
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        }
        StackedMonthBars(
            n.monthly.map { it.month }, n.monthly.map { it.repeat }, n.monthly.map { it.new },
            "Bought before", "First time", MaterialTheme.colorScheme.primary, NewAmber,
        ) { i ->
            val m = n.monthly[i]
            "${monthLabel(m.month, true)}: ${money(m.new)} new" to
                "${m.newItems} first-time items · ${money(m.repeat)} on things bought before"
        }
    }
}

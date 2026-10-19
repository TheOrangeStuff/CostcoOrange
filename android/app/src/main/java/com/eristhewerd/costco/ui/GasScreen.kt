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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Gas
import com.eristhewerd.costco.data.GasGroup
import com.eristhewerd.costco.ui.theme.OnlineTeal
import kotlin.math.roundToInt

/** Pump prices are quoted to a tenth of a cent. */
fun perGallon(v: Double?): String = if (v == null) "—" else "$%.3f".format(v)

fun gallons(v: Double): String = "%.1f gal".format(v)

@Composable
fun GasScreen(api: Api, range: RangePreset, onRange: (RangePreset) -> Unit, onReceipt: (String) -> Unit) {
    val path = "/api/gas" + range.query()
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, Gas.serializer()) else api.get(path, Gas.serializer())
    }
    Column {
        RangeChips(range, onRange)
        LoadContent(load) { g ->
            if (g.fillUps == 0 && g.carWashSpend == 0.0) {
                Text("No Costco gas purchases in this range.", Modifier.padding(16.dp))
            } else LazyColumn {
                item { Totals(g) }
                if (g.priceHistory.size >= 2) {
                    item { SectionTitle("Price per gallon" + (g.usualGrade?.let { " · ${it.lowercase()}" } ?: "")) }
                    item {
                        PriceLine(g.priceHistory)
                        Text(
                            "Each fill-up of your usual grade, oldest to newest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (g.monthly.isNotEmpty()) {
                    item { SectionTitle("By month", Modifier.padding(top = 16.dp)) }
                    item {
                        StackedMonthBars(
                            g.monthly.map { it.month }, g.monthly.map { it.fuel }, g.monthly.map { it.carWash },
                            "Fuel", "Car wash", MaterialTheme.colorScheme.primary, OnlineTeal,
                        ) { i ->
                            val m = g.monthly[i]
                            "${monthLabel(m.month, true)}: ${money(m.fuel + m.carWash)}" to
                                listOfNotNull(
                                    "${gallons(m.gallons)} at ${perGallon(m.avgPrice)}",
                                    m.carWash.takeIf { it != 0.0 }?.let { "car wash ${money(it)}" },
                                ).joinToString(" · ")
                        }
                    }
                }
                if (g.grades.size > 1) {
                    item { SectionTitle("By grade", Modifier.padding(top = 16.dp)) }
                    items(g.grades) { Group(it.grade ?: "Unknown", it, g.gallons) }
                }
                if (g.stations.isNotEmpty()) {
                    item { SectionTitle("By station", Modifier.padding(top = 16.dp)) }
                    items(g.stations) { Group(it.warehouse ?: "Unknown", it, g.gallons) }
                }
                if (g.recent.isNotEmpty()) {
                    item { SectionTitle("Recent fill-ups", Modifier.padding(top = 16.dp)) }
                    items(g.recent) { f ->
                        ListItem(
                            headlineContent = { Text("${gallons(f.gallons)} · ${perGallon(f.price)}") },
                            supportingContent = {
                                Text(listOfNotNull(prettyDate(f.date), f.warehouse, f.grade?.lowercase())
                                    .joinToString(" · "))
                            },
                            trailingContent = { Text(money(f.paid)) },
                            modifier = Modifier.clickable { onReceipt(f.barcode) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Totals(g: Gas) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Spent on gas", money(g.spend), Modifier.fillMaxWidth(),
            sub = if (g.carWashSpend != 0.0) "Fuel ${money(g.fuelSpend)} · Car wash ${money(g.carWashSpend)}" else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Gallons", "%.0f".format(g.gallons), Modifier.weight(1f), sub = "${g.fillUps} fill-ups")
            StatCard("Avg price", perGallon(g.avgPrice), Modifier.weight(1f), sub = "per gallon")
            g.avgGallons?.let {
                StatCard("Per fill-up", gallons(it), Modifier.weight(1f),
                    sub = g.medianDaysBetween?.let { d -> "every ~${d.roundToInt()} days" })
            }
        }
    }
}

@Composable
private fun Group(label: String, g: GasGroup, totalGallons: Double) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(perGallon(g.avgPrice), style = MaterialTheme.typography.bodyLarge)
        }
        ShareBar((g.gallons / totalGallons.coerceAtLeast(1.0)).toFloat(), Modifier.padding(vertical = 4.dp))
        Text(
            "${gallons(g.gallons)} · ${g.fills} fill-ups · ${money(g.spend)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

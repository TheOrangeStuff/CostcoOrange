package com.eristhewerd.costco.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Insights
import com.eristhewerd.costco.data.ItemDetail
import com.eristhewerd.costco.data.ItemStat
import com.eristhewerd.costco.data.PriceChange
import com.eristhewerd.costco.data.RestockItem
import com.eristhewerd.costco.ui.theme.CostcoRed
import com.eristhewerd.costco.ui.theme.SavingsGreen
import kotlinx.serialization.builtins.ListSerializer

private enum class ItemTab(val label: String) {
    SPENT("Most spent"), BOUGHT("Most bought"), RESTOCK("Restock"), NEW("New to you"), PRICIEST("Priciest"),
    UP("Price ↑"), DOWN("Price ↓")
}

@Composable
fun ItemsScreen(api: Api, range: RangePreset, onRange: (RangePreset) -> Unit, onItem: (String) -> Unit) {
    val path = "/api/insights" + range.query().let { if (it.isEmpty()) "?limit=50" else "$it&limit=50" }
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, Insights.serializer()) else api.get(path, Insights.serializer())
    }
    var tab by rememberSaveable { mutableStateOf(ItemTab.SPENT) }

    Column {
        // Restock looks at all of history to find a rhythm, so the range doesn't apply.
        if (tab != ItemTab.RESTOCK) RangeChips(range, onRange)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ItemTab.entries.forEach { t ->
                FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        if (tab == ItemTab.RESTOCK) {
            RestockList(api, onItem)
            return@Column
        }
        LoadContent(load) { data ->
            LazyColumn {
                when (tab) {
                    ItemTab.RESTOCK -> Unit
                    ItemTab.SPENT -> items(data.topSpent) { s ->
                        StatRow(s, "${units(s.units)} bought", money(s.spend), onItem)
                    }
                    ItemTab.BOUGHT -> items(data.mostPurchased) { s ->
                        StatRow(s, "${money(s.spend)} total", "${s.purchases}×", onItem)
                    }
                    ItemTab.NEW -> items(data.newItems) { s ->
                        StatRow(
                            s,
                            "First bought ${prettyDate(s.firstBought)}" + if (s.purchases > 1) " · bought ${s.purchases}×" else "",
                            money(s.spend),
                            onItem,
                        )
                    }
                    ItemTab.PRICIEST -> items(data.mostExpensive) { s ->
                        StatRow(s, "${units(s.units)} bought", money(s.avgUnitPrice ?: 0.0), onItem)
                    }
                    ItemTab.UP -> items(data.priceIncreases) { c -> ChangeRow(c, onItem) }
                    ItemTab.DOWN -> items(data.priceDecreases) { c -> ChangeRow(c, onItem) }
                }
            }
        }
    }
}

@Composable
private fun RestockList(api: Api, onItem: (String) -> Unit) {
    val path = "/api/restock"
    val ser = ListSerializer(RestockItem.serializer())
    val load = rememberLoad(path) { cachedOnly -> if (cachedOnly) api.cached(path, ser) else api.get(path, ser) }
    LoadContent(load) { list ->
        LazyColumn {
            item {
                Text(
                    "Things you buy regularly, and when you'd usually buy them next.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (list.isEmpty()) item { Text("Nothing bought regularly yet.", Modifier.padding(16.dp)) }
            items(list) { r ->
                ListItem(
                    headlineContent = { Text(r.name ?: r.itemNumber, maxLines = 2) },
                    supportingContent = {
                        Text(listOfNotNull(
                            "Every ~${r.everyDays} days",
                            "last ${prettyDate(r.lastBought)} (${r.daysSince}d ago)",
                            r.lastPrice?.let { "${units(r.usualUnits)} × ${money(it)}" },
                        ).joinToString(" · "))
                    },
                    trailingContent = {
                        val (label, color) = when {
                            r.dueIn < 0 -> "${-r.dueIn}d overdue" to CostcoRed
                            r.dueIn == 0 -> "Due today" to CostcoRed
                            else -> "in ${r.dueIn}d" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(label, color = color, style = MaterialTheme.typography.titleSmall)
                    },
                    modifier = Modifier.clickable { onItem(r.itemNumber) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatRow(s: ItemStat, sub: String, value: String, onItem: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(s.name ?: s.itemNumber, maxLines = 2) },
        supportingContent = { Text(sub) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium) },
        modifier = Modifier.clickable { onItem(s.itemNumber) },
    )
    HorizontalDivider()
}

@Composable
private fun ChangeRow(c: PriceChange, onItem: (String) -> Unit) {
    val up = c.change > 0
    ListItem(
        headlineContent = { Text(c.name ?: c.itemNumber, maxLines = 2) },
        supportingContent = {
            Text("${money(c.firstPrice)} (${prettyDate(c.firstDate)}) → ${money(c.lastPrice)} (${prettyDate(c.lastDate)})")
        },
        trailingContent = {
            Text(
                (if (up) "+" else "") + "%.1f%%".format(c.changePct),
                color = if (up) CostcoRed else SavingsGreen,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        modifier = Modifier.clickable { onItem(c.itemNumber) },
    )
    HorizontalDivider()
}

@Composable
fun ItemDetailScreen(api: Api, itemNumber: String) {
    val path = "/api/items/$itemNumber"
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, ItemDetail.serializer()) else api.get(path, ItemDetail.serializer())
    }
    LoadContent(load) { item ->
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(item.name ?: "Item", style = MaterialTheme.typography.headlineSmall)
                    Text("Item #${item.itemNumber}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val spent = item.warehouse.sumOf { it.paid } + item.online.sumOf { it.amount ?: 0.0 }
                    val count = item.warehouse.size + item.online.size
                    Text("Bought $count times · ${money(spent)} total", modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (item.warehouse.size >= 2) {
                item { SectionTitle("Shelf price") }
                item { PriceLine(item.warehouse) }
            }
            item { SectionTitle("Purchases") }
            items(item.warehouse.reversed()) { p ->
                ListItem(
                    headlineContent = { Text(prettyDate(p.date)) },
                    supportingContent = {
                        Text(listOfNotNull(
                            p.warehouse,
                            "${units(p.units)} × ${money(p.unitPrice ?: 0.0)}",
                            if (p.discount != 0.0) "saved ${money(-p.discount)}" else null,
                        ).joinToString(" · "))
                    },
                    trailingContent = { Text(money(p.paid)) },
                )
            }
            items(item.online.reversed()) { p ->
                ListItem(
                    headlineContent = { Text(prettyDate(p.date)) },
                    supportingContent = { Text("Costco.com · order ${p.orderNumber}") },
                    trailingContent = { Text(money(p.amount ?: 0.0)) },
                )
            }
        }
    }
}

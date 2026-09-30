package com.eristhewerd.costco.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.ReceiptDetail
import com.eristhewerd.costco.data.Trip
import com.eristhewerd.costco.ui.theme.SavingsGreen
import kotlinx.serialization.builtins.ListSerializer

@Composable
fun ReceiptsScreen(api: Api, range: RangePreset, onRange: (RangePreset) -> Unit, onOpen: (Trip) -> Unit) {
    val path = "/api/receipts" + range.query()
    val serializer = ListSerializer(Trip.serializer())
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, serializer) else api.get(path, serializer)
    }
    Column {
        RangeChips(range, onRange)
        LoadContent(load) { trips ->
            if (trips.isEmpty()) Text("No receipts in this range yet.", Modifier.padding(16.dp))
            LazyColumn {
                items(trips, key = { it.kind + it.id }) { t ->
                    val label = when (t.kind) {
                        "online" -> "Costco.com" + (t.status?.let { " · $it" } ?: "")
                        "gas" -> "Gas · ${t.location ?: ""}"
                        else -> t.location ?: "Warehouse"
                    }
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Text("${prettyDate(t.date)} · ${t.items} item${if (t.items == 1) "" else "s"}") },
                        trailingContent = { Text(money(t.total), style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.clickable { onOpen(t) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ReceiptDetailScreen(api: Api, kind: String, id: String, onItem: (String) -> Unit) {
    val path = if (kind == "order") "/api/orders/$id" else "/api/receipts/$id"
    val load = rememberLoad(path) { cachedOnly ->
        if (cachedOnly) api.cached(path, ReceiptDetail.serializer()) else api.get(path, ReceiptDetail.serializer())
    }
    LoadContent(load) { r ->
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (kind == "order") "Costco.com order" else (r.warehouseName ?: "Warehouse"),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(prettyDate(r.txnDate ?: r.orderDate) + (r.status?.let { " · $it" } ?: ""))
                    Text("Total ${money(r.total)}", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp))
                    if (r.taxes != null) {
                        Text("Subtotal ${money(r.subtotal ?: 0.0)} · Tax ${money(r.taxes)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
            items(r.items) { li ->
                val qty = li.units ?: li.quantity
                ListItem(
                    headlineContent = { Text(li.name ?: li.itemNumber ?: "Item", maxLines = 2) },
                    supportingContent = {
                        Column {
                            if (qty != null && li.unitPrice != null) Text("${units(qty)} × ${money(li.unitPrice)}")
                            if (li.discount != 0.0) Text("Instant savings ${money(li.discount)}", color = SavingsGreen)
                        }
                    },
                    trailingContent = { Text(money(li.net ?: li.amount ?: 0.0)) },
                    modifier = Modifier.clickable(enabled = li.itemNumber != null) { li.itemNumber?.let(onItem) },
                )
            }
        }
    }
}

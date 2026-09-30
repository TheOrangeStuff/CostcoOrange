package com.eristhewerd.costco.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.Membership
import com.eristhewerd.costco.data.PriceAdjustment
import com.eristhewerd.costco.data.Savings
import com.eristhewerd.costco.ui.theme.SavingsGreen
import kotlinx.serialization.builtins.ListSerializer

@Composable
fun SavingsScreen(api: Api, onItem: (String) -> Unit) {
    val adjPath = "/api/price-adjustments"
    val memPath = "/api/membership"
    val adjSer = ListSerializer(PriceAdjustment.serializer())
    val load = rememberLoad("savings") { cachedOnly ->
        if (cachedOnly) {
            api.cached(adjPath, adjSer)?.let { Savings(it, api.cached(memPath, Membership.serializer())) }
        } else {
            Savings(api.get(adjPath, adjSer), api.get(memPath, Membership.serializer()))
        }
    }
    LoadContent(load) { data ->
        LazyColumn {
            item { SectionTitle("Price adjustments") }
            item {
                Text(
                    "Costco refunds the difference if something you bought rings up cheaper within 30 days. " +
                        "These are items your own later receipts show at a lower price. Take the receipt " +
                        "to the membership counter before the date shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (data.adjustments.isEmpty()) {
                item {
                    Text("Nothing to claim right now.", Modifier.padding(16.dp))
                }
            }
            items(data.adjustments) { a ->
                ListItem(
                    headlineContent = { Text(a.name ?: a.itemNumber, maxLines = 2) },
                    supportingContent = {
                        Text(
                            "Paid ${money(a.paidUnit)} on ${prettyDate(a.boughtOn)} · rang up ${money(a.lowerUnit)} " +
                                "on ${prettyDate(a.seenOn)}\nClaim by ${prettyDate(a.claimBy)} · ${a.warehouse ?: ""}"
                        )
                    },
                    trailingContent = {
                        Text(money(a.refundEstimate), color = SavingsGreen, fontWeight = FontWeight.Bold)
                    },
                    modifier = Modifier.clickable { onItem(a.itemNumber) },
                )
                HorizontalDivider()
            }
            data.membership?.let { m ->
                item { SectionTitle("Executive membership", Modifier.padding(top = 16.dp)) }
                item { MembershipCard(m) }
            }
        }
    }
}

@Composable
private fun MembershipCard(m: Membership) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (m.worthIt) "Upgrading would have paid off" else "Not worth upgrading yet",
                style = MaterialTheme.typography.titleMedium,
                color = if (m.worthIt) SavingsGreen else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Last 12 months: ${money(m.eligibleSpend)} of eligible purchases would have earned " +
                    "${money(m.reward)} back, against ${money(m.upgradeCost)} extra for Executive " +
                    "(${if (m.net >= 0) "+" else "−"}${money(kotlin.math.abs(m.net))}).",
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Break-even is ${wholeMoney(m.breakEvenSpend)} a year.",
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(m.note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

package com.eristhewerd.costco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Month
import com.eristhewerd.costco.data.PricePoint
import com.eristhewerd.costco.ui.theme.OnlineTeal
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private fun monthLabel(m: String, withYear: Boolean = false): String = runCatching {
    YearMonth.parse(m).format(DateTimeFormatter.ofPattern(if (withYear) "MMM yyyy" else "MMM"))
}.getOrDefault(m)

/** Stacked warehouse/online spend per month. Tap a bar to read its numbers. */
@Composable
fun MonthlyBars(months: List<Month>, modifier: Modifier = Modifier) {
    if (months.isEmpty()) return
    val warehouseColor = MaterialTheme.colorScheme.primary
    val onlineColor = OnlineTeal
    val selectedColor = MaterialTheme.colorScheme.tertiary
    var selected by remember(months) { mutableStateOf(months.lastIndex) }
    val max = (months.maxOf { it.warehouse + maxOf(it.online, 0.0) }).coerceAtLeast(1.0)

    Column(modifier.padding(horizontal = 16.dp)) {
        months.getOrNull(selected)?.let { m ->
            Text(
                "${monthLabel(m.month, true)}: ${money(m.total)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Warehouse ${money(m.warehouse)} · ${m.trips} trips · Online ${money(m.online)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(top = 8.dp)
                .pointerInput(months) {
                    detectTapGestures { pos ->
                        val slot = size.width / months.size.toFloat()
                        selected = (pos.x / slot).toInt().coerceIn(0, months.lastIndex)
                    }
                },
        ) {
            val slot = size.width / months.size
            val barWidth = (slot * 0.7f).coerceAtMost(40.dp.toPx())
            months.forEachIndexed { i, m ->
                val x = i * slot + (slot - barWidth) / 2
                val wh = (maxOf(m.warehouse, 0.0) / max * size.height).toFloat()
                val on = (maxOf(m.online, 0.0) / max * size.height).toFloat()
                val alpha = if (i == selected) 1f else 0.55f
                drawRoundRect(
                    color = warehouseColor.copy(alpha = alpha),
                    topLeft = Offset(x, size.height - wh),
                    size = Size(barWidth, wh),
                    cornerRadius = CornerRadius(4f),
                )
                if (on > 0) {
                    drawRoundRect(
                        color = onlineColor.copy(alpha = alpha),
                        topLeft = Offset(x, size.height - wh - on),
                        size = Size(barWidth, on),
                        cornerRadius = CornerRadius(4f),
                    )
                }
                if (i == selected) {
                    drawRect(selectedColor, Offset(x, size.height + 2), Size(barWidth, 3.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(monthLabel(months.first().month, true), style = MaterialTheme.typography.labelSmall)
            if (months.size > 1) Text(monthLabel(months.last().month, true), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Legend(warehouseColor, "Warehouse")
            Legend(onlineColor, "Online")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

/** A horizontal bar showing [fraction] of the widest row. */
@Composable
fun ShareBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** Shelf price at each purchase, as a step line. */
@Composable
fun PriceLine(points: List<PricePoint>, modifier: Modifier = Modifier) {
    val prices = points.mapNotNull { it.unitPrice }
    if (prices.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val lo = prices.min()
    val hi = prices.max()
    val span = (hi - lo).takeIf { it > 0.001 } ?: 1.0
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Low ${money(lo)}", style = MaterialTheme.typography.labelSmall)
            Text("High ${money(hi)}", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 8.dp)
        ) {
            // Inset by the dot radius so the first and last points aren't clipped.
            val inset = 4.dp.toPx()
            val step = (size.width - 2 * inset) / (prices.size - 1)
            fun y(p: Double) = (inset + (size.height - 2 * inset) * (1 - (p - lo) / span)).toFloat()
            val path = Path().apply {
                moveTo(inset, y(prices[0]))
                prices.drop(1).forEachIndexed { i, p ->
                    lineTo(inset + (i + 1) * step, y(prices[i]))
                    lineTo(inset + (i + 1) * step, y(p))
                }
            }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            prices.forEachIndexed { i, p -> drawCircle(color, inset, Offset(inset + i * step, y(p))) }
        }
    }
}

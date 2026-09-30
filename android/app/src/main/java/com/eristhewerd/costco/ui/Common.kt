package com.eristhewerd.costco.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.data.friendlyMessage
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RangePreset(val label: String) {
    THIRTY_DAYS("30 days"),
    TWELVE_MONTHS("12 months"),
    YEAR_TO_DATE("This year"),
    LAST_YEAR("Last year"),
    ALL("All time");

    /** (start, end) as ISO dates; null means unbounded. */
    fun bounds(today: LocalDate = LocalDate.now()): Pair<String?, String?> = when (this) {
        THIRTY_DAYS -> today.minusDays(30).toString() to today.toString()
        TWELVE_MONTHS -> today.minusMonths(12).plusDays(1).toString() to today.toString()
        YEAR_TO_DATE -> today.withDayOfYear(1).toString() to today.toString()
        LAST_YEAR -> today.minusYears(1).withDayOfYear(1).toString() to
            today.withDayOfYear(1).minusDays(1).toString()
        ALL -> null to null
    }

    fun query(): String = bounds().let { (s, e) -> Api.rangeQuery(s, e) }
}

@Composable
fun RangeChips(selected: RangePreset, onSelect: (RangePreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RangePreset.entries.forEach { r ->
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text(r.label) })
        }
    }
}

private val moneyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
private val wholeMoneyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US).apply {
    maximumFractionDigits = 0
}

fun money(v: Double): String = moneyFormat.format(v)
fun wholeMoney(v: Double): String = wholeMoneyFormat.format(v)

fun prettyDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }.getOrDefault(iso)
}

fun units(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)

class Load<T>(val data: T?, val loading: Boolean, val error: String?, val reload: () -> Unit)

/**
 * Shows the last cached copy immediately (so the app works offline), then refreshes from the
 * server. A failed refresh keeps the cached copy and reports the error.
 */
@Composable
fun <T> rememberLoad(key: Any, fetch: suspend (cachedOnly: Boolean) -> T?): Load<T> {
    var data by remember(key) { mutableStateOf<T?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        loading = true
        error = null
        if (data == null) data = runCatching { fetch(true) }.getOrNull()
        try {
            data = fetch(false)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.friendlyMessage()
        }
        loading = false
    }
    return Load(data, loading, error) { attempt++ }
}

@Composable
fun <T> LoadContent(load: Load<T>, content: @Composable (T) -> Unit) {
    val data = load.data
    Column(Modifier.fillMaxSize()) {
        if (load.error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (data != null) "Offline copy · ${load.error}" else load.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = load.reload) { Text("Retry") }
            }
        }
        when {
            data != null -> content(data)
            load.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

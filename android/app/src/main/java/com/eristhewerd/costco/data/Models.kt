package com.eristhewerd.costco.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class SyncRun(
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val ok: Int? = null,
    val error: String? = null,
)

@Serializable
data class Status(
    val user: String = "",
    @SerialName("costco_linked") val costcoLinked: Boolean = false,
    @SerialName("costco_email") val costcoEmail: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null,
    @SerialName("days_since_link") val daysSinceLink: Int? = null,
    @SerialName("auth_error") val authError: String? = null,
    @SerialName("last_sync") val lastSync: SyncRun? = null,
    @SerialName("last_successful_sync") val lastSuccessfulSync: String? = null,
    val receipts: Int = 0,
    val orders: Int = 0,
)

@Serializable
data class Summary(
    @SerialName("total_spend") val totalSpend: Double = 0.0,
    @SerialName("warehouse_spend") val warehouseSpend: Double = 0.0,
    @SerialName("online_spend") val onlineSpend: Double = 0.0,
    @SerialName("gas_spend") val gasSpend: Double = 0.0,
    val trips: Int = 0,
    @SerialName("online_orders") val onlineOrders: Int = 0,
    @SerialName("avg_per_trip") val avgPerTrip: Double = 0.0,
    val units: Double = 0.0,
    @SerialName("unique_items") val uniqueItems: Int = 0,
    @SerialName("data_from") val dataFrom: String? = null,
    @SerialName("data_to") val dataTo: String? = null,
)

@Serializable
data class Month(
    val month: String,
    val warehouse: Double = 0.0,
    val online: Double = 0.0,
    val total: Double = 0.0,
    val trips: Int = 0,
)

@Serializable
data class Category(
    val department: Int? = null,
    val label: String,
    val labeled: Boolean = false,
    val spend: Double = 0.0,
    val samples: List<String> = emptyList(),
)

@Serializable
data class TripRef(
    val barcode: String,
    val date: String,
    val warehouse: String? = null,
    val total: Double = 0.0,
    val items: Double = 0.0,
)

@Serializable
data class TripBucket(val label: String, val trips: Int = 0, val spend: Double = 0.0)

@Serializable
data class Basket(
    val trips: Int = 0,
    @SerialName("avg_total") val avgTotal: Double = 0.0,
    @SerialName("median_total") val medianTotal: Double = 0.0,
    @SerialName("avg_items") val avgItems: Double = 0.0,
    @SerialName("median_items") val medianItems: Double = 0.0,
    @SerialName("median_days_between") val medianDaysBetween: Double? = null,
    val biggest: TripRef? = null,
    val buckets: List<TripBucket> = emptyList(),
)

@Serializable
data class NewnessMonth(
    val month: String,
    val new: Double = 0.0,
    val repeat: Double = 0.0,
    @SerialName("new_items") val newItems: Int = 0,
)

@Serializable
data class Newness(
    @SerialName("counted_from") val countedFrom: String? = null,
    @SerialName("new_spend") val newSpend: Double = 0.0,
    @SerialName("repeat_spend") val repeatSpend: Double = 0.0,
    @SerialName("new_share") val newShare: Double? = null,
    @SerialName("new_items") val newItems: Int = 0,
    val monthly: List<NewnessMonth> = emptyList(),
)

@Serializable
data class InflationDriver(
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    @SerialName("prior_price") val priorPrice: Double,
    val price: Double,
    val impact: Double,
)

@Serializable
data class InflationMonth(val month: String, @SerialName("vs_usual_pct") val vsUsualPct: Double)

@Serializable
data class Inflation(
    @SerialName("period_start") val periodStart: String = "",
    @SerialName("prior_start") val priorStart: String = "",
    @SerialName("prior_end") val priorEnd: String = "",
    @SerialName("items_compared") val itemsCompared: Int = 0,
    val paid: Double = 0.0,
    @SerialName("at_prior_prices") val atPriorPrices: Double = 0.0,
    @SerialName("change_pct") val changePct: Double? = null,
    val drivers: List<InflationDriver> = emptyList(),
    val monthly: List<InflationMonth> = emptyList(),
)

@Serializable
data class Overview(
    val summary: Summary = Summary(),
    val monthly: List<Month> = emptyList(),
    val categories: List<Category> = emptyList(),
    val basket: Basket = Basket(),
    val newness: Newness = Newness(),
    val inflation: Inflation = Inflation(),
)

@Serializable
data class RestockItem(
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    @SerialName("days_bought") val daysBought: Int = 0,
    @SerialName("every_days") val everyDays: Int = 0,
    @SerialName("last_bought") val lastBought: String,
    @SerialName("days_since") val daysSince: Int = 0,
    @SerialName("due_on") val dueOn: String,
    @SerialName("due_in") val dueIn: Int = 0,
    @SerialName("usual_units") val usualUnits: Double = 0.0,
    @SerialName("last_price") val lastPrice: Double? = null,
)

@Serializable
data class ItemStat(
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    val spend: Double = 0.0,
    val units: Double = 0.0,
    val purchases: Int = 0,
    @SerialName("avg_unit_price") val avgUnitPrice: Double? = null,
    @SerialName("first_bought") val firstBought: String? = null,
)

@Serializable
data class PriceChange(
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    @SerialName("first_price") val firstPrice: Double,
    @SerialName("first_date") val firstDate: String,
    @SerialName("last_price") val lastPrice: Double,
    @SerialName("last_date") val lastDate: String,
    val change: Double,
    @SerialName("change_pct") val changePct: Double,
)

@Serializable
data class Insights(
    @SerialName("new_items") val newItems: List<ItemStat> = emptyList(),
    @SerialName("top_spent") val topSpent: List<ItemStat> = emptyList(),
    @SerialName("most_purchased") val mostPurchased: List<ItemStat> = emptyList(),
    @SerialName("most_expensive") val mostExpensive: List<ItemStat> = emptyList(),
    @SerialName("price_increases") val priceIncreases: List<PriceChange> = emptyList(),
    @SerialName("price_decreases") val priceDecreases: List<PriceChange> = emptyList(),
)

@Serializable
data class PriceAdjustment(
    val barcode: String,
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    @SerialName("bought_on") val boughtOn: String,
    val warehouse: String? = null,
    @SerialName("paid_unit") val paidUnit: Double,
    @SerialName("lower_unit") val lowerUnit: Double,
    @SerialName("seen_on") val seenOn: String,
    val units: Double,
    @SerialName("refund_estimate") val refundEstimate: Double,
    @SerialName("claim_by") val claimBy: String,
)

@Serializable
data class Membership(
    @SerialName("period_start") val periodStart: String,
    @SerialName("eligible_spend") val eligibleSpend: Double,
    val reward: Double,
    @SerialName("upgrade_cost") val upgradeCost: Double,
    val net: Double,
    @SerialName("break_even_spend") val breakEvenSpend: Double,
    @SerialName("worth_it") val worthIt: Boolean,
    val note: String = "",
)

@Serializable
data class Savings(
    val adjustments: List<PriceAdjustment> = emptyList(),
    val membership: Membership? = null,
)

@Serializable
data class Trip(
    val id: String,
    val date: String,
    val kind: String,
    val location: String? = null,
    val total: Double = 0.0,
    val items: Int = 0,
    val status: String? = null,
)

@Serializable
data class LineItem(
    val line: Int = 0,
    @SerialName("item_number") val itemNumber: String? = null,
    val name: String? = null,
    val units: Double? = null,
    val quantity: Double? = null,
    @SerialName("unit_price") val unitPrice: Double? = null,
    val amount: Double? = null,
    val discount: Double = 0.0,
    val net: Double? = null,
)

@Serializable
data class ReceiptDetail(
    val barcode: String? = null,
    @SerialName("order_number") val orderNumber: String? = null,
    @SerialName("txn_date") val txnDate: String? = null,
    @SerialName("order_date") val orderDate: String? = null,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    val status: String? = null,
    val total: Double = 0.0,
    val subtotal: Double? = null,
    val taxes: Double? = null,
    val items: List<LineItem> = emptyList(),
)

@Serializable
data class PricePoint(
    val date: String,
    val warehouse: String? = null,
    @SerialName("unit_price") val unitPrice: Double? = null,
    val units: Double = 0.0,
    val discount: Double = 0.0,
    val paid: Double = 0.0,
)

@Serializable
data class OnlinePurchase(
    val date: String,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("unit_price") val unitPrice: Double? = null,
    val quantity: Double? = null,
    val amount: Double? = null,
)

@Serializable
data class ItemDetail(
    @SerialName("item_number") val itemNumber: String,
    val name: String? = null,
    val warehouse: List<PricePoint> = emptyList(),
    val online: List<OnlinePurchase> = emptyList(),
)

@Serializable
data class Event(
    val id: Long,
    val kind: String,
    val ref: String,
    val title: String,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class GasGroup(
    val grade: String? = null,
    val warehouse: String? = null,
    val gallons: Double = 0.0,
    val spend: Double = 0.0,
    val fills: Int = 0,
    @SerialName("avg_price") val avgPrice: Double? = null,
)

@Serializable
data class GasMonth(
    val month: String,
    val fuel: Double = 0.0,
    @SerialName("car_wash") val carWash: Double = 0.0,
    val gallons: Double = 0.0,
    @SerialName("avg_price") val avgPrice: Double? = null,
)

@Serializable
data class GasFill(
    val barcode: String,
    val date: String,
    val warehouse: String? = null,
    val grade: String? = null,
    val gallons: Double = 0.0,
    val price: Double? = null,
    val paid: Double = 0.0,
)

@Serializable
data class Gas(
    val spend: Double = 0.0,
    @SerialName("fuel_spend") val fuelSpend: Double = 0.0,
    @SerialName("car_wash_spend") val carWashSpend: Double = 0.0,
    val gallons: Double = 0.0,
    @SerialName("fill_ups") val fillUps: Int = 0,
    @SerialName("avg_price") val avgPrice: Double? = null,
    @SerialName("avg_gallons") val avgGallons: Double? = null,
    @SerialName("median_days_between") val medianDaysBetween: Double? = null,
    @SerialName("usual_grade") val usualGrade: String? = null,
    @SerialName("price_history") val priceHistory: List<PricePoint> = emptyList(),
    val grades: List<GasGroup> = emptyList(),
    val stations: List<GasGroup> = emptyList(),
    val monthly: List<GasMonth> = emptyList(),
    val recent: List<GasFill> = emptyList(),
)

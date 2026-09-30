package com.eristhewerd.costco.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CostcoBlue = Color(0xFF005DAA)
val CostcoRed = Color(0xFFE31837)
val OnlineTeal = Color(0xFF00897B)
val SavingsGreen = Color(0xFF2E7D32)

private val Light = lightColorScheme(
    primary = CostcoBlue,
    secondary = OnlineTeal,
    tertiary = CostcoRed,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CC4FF),
    secondary = Color(0xFF6FD6C6),
    tertiary = Color(0xFFFFB3B5),
)

@Composable
fun CostcoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

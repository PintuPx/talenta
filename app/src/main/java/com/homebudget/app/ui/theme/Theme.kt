package com.homebudget.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ============================================================================================
 *  BRAND TOKENS — PLACEHOLDER VALUES.
 *  The final brand palette, typography and assets will be supplied by the owner.
 *  To re-brand, change ONLY this object (and res/values/colors.xml for the launcher/splash).
 *  See docs/BRANDING.md.
 * ============================================================================================
 */
object BrandTokens {
    // Neutral slate placeholder seed colors.
    val primaryLight = Color(0xFF3B5B7A)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFD3E3F5)
    val onPrimaryContainerLight = Color(0xFF0C2236)
    val secondaryLight = Color(0xFF55606B)
    val secondaryContainerLight = Color(0xFFD9E3EE)
    val backgroundLight = Color(0xFFF8F9FA)
    val surfaceLight = Color(0xFFF8F9FA)
    val surfaceContainerLight = Color(0xFFEEF0F3)

    val primaryDark = Color(0xFFA6C8EA)
    val onPrimaryDark = Color(0xFF0C2236)
    val primaryContainerDark = Color(0xFF23415E)
    val onPrimaryContainerDark = Color(0xFFD3E3F5)
    val secondaryDark = Color(0xFFBCC7D2)
    val secondaryContainerDark = Color(0xFF3D4853)
    val backgroundDark = Color(0xFF121417)
    val surfaceDark = Color(0xFF121417)
    val surfaceContainerDark = Color(0xFF1E2125)

    /** Replace with the brand font family when supplied (add the font files under res/font). */
    val fontFamily: FontFamily = FontFamily.Default
}

/**
 * Semantic finance colors. Always paired with a non-color cue in the UI (sign, arrow icon, label),
 * so meaning never depends on color alone. Contrast checked against surface colors for WCAG AA text.
 */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val warning: Color,
    val positiveContainer: Color,
    val negativeContainer: Color,
    /** Text/icon color on a filled [income] background (e.g. the income Save button). */
    val onIncome: Color,
    /** Text/icon color on a filled [expense] background (e.g. the expense Save button). */
    val onExpense: Color,
)

private val LightFinance = FinanceColors(
    income = Color(0xFF1B6E32),
    expense = Color(0xFFB3261E),
    transfer = Color(0xFF45546A),
    warning = Color(0xFF8A5100),
    positiveContainer = Color(0xFFD6EFDA),
    negativeContainer = Color(0xFFF9DEDC),
    onIncome = Color(0xFFFFFFFF), // 6.3:1 on income
    onExpense = Color(0xFFFFFFFF), // 6.5:1 on expense
)

private val DarkFinance = FinanceColors(
    income = Color(0xFF8BD69B),
    expense = Color(0xFFF2B8B5),
    transfer = Color(0xFFB8C4D6),
    warning = Color(0xFFFFB86B),
    positiveContainer = Color(0xFF1F3B26),
    negativeContainer = Color(0xFF4A2120),
    onIncome = Color(0xFF00390F), // 7.7:1 on income (dark)
    onExpense = Color(0xFF601410), // 7.7:1 on expense (dark)
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinance }

private val LightScheme: ColorScheme = lightColorScheme(
    primary = BrandTokens.primaryLight,
    onPrimary = BrandTokens.onPrimaryLight,
    primaryContainer = BrandTokens.primaryContainerLight,
    onPrimaryContainer = BrandTokens.onPrimaryContainerLight,
    secondary = BrandTokens.secondaryLight,
    secondaryContainer = BrandTokens.secondaryContainerLight,
    background = BrandTokens.backgroundLight,
    surface = BrandTokens.surfaceLight,
    surfaceContainer = BrandTokens.surfaceContainerLight,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = BrandTokens.primaryDark,
    onPrimary = BrandTokens.onPrimaryDark,
    primaryContainer = BrandTokens.primaryContainerDark,
    onPrimaryContainer = BrandTokens.onPrimaryContainerDark,
    secondary = BrandTokens.secondaryDark,
    secondaryContainer = BrandTokens.secondaryContainerDark,
    background = BrandTokens.backgroundDark,
    surface = BrandTokens.surfaceDark,
    surfaceContainer = BrandTokens.surfaceContainerDark,
)

private fun appTypography(): Typography {
    val base = Typography()
    val f = BrandTokens.fontFamily
    return base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = f),
        headlineMedium = base.headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = f),
        titleMedium = base.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = f),
        bodyLarge = base.bodyLarge.copy(fontFamily = f),
        bodyMedium = base.bodyMedium.copy(fontFamily = f),
        bodySmall = base.bodySmall.copy(fontFamily = f),
        labelLarge = base.labelLarge.copy(fontFamily = f),
        labelMedium = base.labelMedium.copy(fontFamily = f),
    )
}

/** Tabular figures so amounts align in lists. */
val AmountTextStyle = TextStyle(fontFeatureSettings = "tnum", fontSize = 16.sp)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFinanceColors provides if (darkTheme) DarkFinance else LightFinance) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = appTypography(),
            content = content,
        )
    }
}

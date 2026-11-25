package com.homebudget.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.homebudget.app.domain.money.MoneyFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    val currencyCode: String = MoneyFormatter.DEFAULT_CURRENCY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * User preferences live in Preferences DataStore (not Room): they are small, key-value, and must be
 * available before any database work. Nothing sensitive is stored here.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val currency = stringPreferencesKey("currency_code")
        val theme = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            currencyCode = p[Keys.currency]?.takeIf { it in MoneyFormatter.SUPPORTED } ?: MoneyFormatter.DEFAULT_CURRENCY,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        )
    }

    suspend fun setCurrency(code: String) {
        require(code in MoneyFormatter.SUPPORTED)
        store.edit { it[Keys.currency] = code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.theme] = mode.name }
    }
}

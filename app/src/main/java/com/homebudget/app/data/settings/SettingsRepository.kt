package com.homebudget.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.homebudget.app.domain.money.MoneyFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }
enum class AppMode(val label: String) { UNSET("Choose a mode"), PERSONAL("Individual"), SHARED("Family") }

data class AppSettings(
    val currencyCode: String = MoneyFormatter.DEFAULT_CURRENCY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val privateMode: Boolean = false,
    val monthCloseReminderUntil: Long = 0,
    val appMode: AppMode = AppMode.UNSET,
    val currentMemberId: String? = null,
    val dismissedFamilyUpdateVersionCode: Int = 0,
    val askForDeviceLock: Boolean = true,
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
        val privateMode = booleanPreferencesKey("private_mode")
        val monthCloseReminderUntil = longPreferencesKey("month_close_reminder_until")
        val appMode = stringPreferencesKey("app_mode")
        val currentMemberId = stringPreferencesKey("current_member_id")
        val dismissedFamilyUpdateVersionCode = intPreferencesKey("dismissed_family_update_version_code")
        val askForDeviceLock = booleanPreferencesKey("ask_for_device_lock")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            currencyCode = p[Keys.currency]?.takeIf { it in MoneyFormatter.SUPPORTED } ?: MoneyFormatter.DEFAULT_CURRENCY,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            privateMode = p[Keys.privateMode] ?: false,
            monthCloseReminderUntil = p[Keys.monthCloseReminderUntil] ?: 0L,
            appMode = p[Keys.appMode]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.UNSET,
            currentMemberId = p[Keys.currentMemberId],
            dismissedFamilyUpdateVersionCode = p[Keys.dismissedFamilyUpdateVersionCode] ?: 0,
            askForDeviceLock = p[Keys.askForDeviceLock] ?: true,
        )
    }

    suspend fun setCurrency(code: String) {
        require(code in MoneyFormatter.SUPPORTED)
        store.edit { it[Keys.currency] = code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setPrivateMode(enabled: Boolean) {
        store.edit { it[Keys.privateMode] = enabled }
    }

    suspend fun setMonthCloseReminderUntil(epochMillis: Long) {
        store.edit { it[Keys.monthCloseReminderUntil] = epochMillis }
    }

    suspend fun setAppMode(mode: AppMode) {
        store.edit { it[Keys.appMode] = mode.name }
    }

    suspend fun initializeIndividualModeIfUnset() {
        store.edit { preferences ->
            val storedMode = preferences[Keys.appMode]
                ?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
                ?: AppMode.UNSET
            if (storedMode == AppMode.UNSET) {
                preferences[Keys.appMode] = AppMode.PERSONAL.name
            }
        }
    }

    suspend fun setCurrentMemberId(memberId: String?) {
        store.edit {
            if (memberId == null) it.remove(Keys.currentMemberId) else it[Keys.currentMemberId] = memberId
        }
    }

    suspend fun dismissFamilyUpdate(versionCode: Int) {
        store.edit { it[Keys.dismissedFamilyUpdateVersionCode] = versionCode }
    }

    suspend fun setAskForDeviceLock(enabled: Boolean) {
        store.edit { it[Keys.askForDeviceLock] = enabled }
    }
}

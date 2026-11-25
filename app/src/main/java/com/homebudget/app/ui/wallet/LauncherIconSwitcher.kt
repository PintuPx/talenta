package com.homebudget.app.ui.wallet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.homebudget.app.domain.budget.WalletLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes the launcher icon by enabling exactly one `activity-alias` from the manifest.
 * Some launchers take a moment to refresh the icon.
 */
@Singleton
class LauncherIconSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shows the icon for [level]. Does nothing when it is already the one in use. */
    fun apply(level: WalletLevel) {
        val last = prefs.getString(KEY_LEVEL, null)?.let { runCatching { WalletLevel.valueOf(it) }.getOrNull() } ?: WalletLevel.FULL
        if (last == level) return
        val pm = context.packageManager
        // Enable the new entry first so the app never has no launcher icon.
        setState(pm, level, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        WalletLevel.entries.filter { it != level }.forEach { setState(pm, it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        prefs.edit { putString(KEY_LEVEL, level.name) }
    }

    private fun setState(pm: PackageManager, level: WalletLevel, state: Int) {
        pm.setComponentEnabledSetting(component(level), state, PackageManager.DONT_KILL_APP)
    }

    internal fun component(level: WalletLevel) = ComponentName(context.packageName, aliasName(level))

    companion object {
        private const val PREFS = "launcher_icon"
        private const val KEY_LEVEL = "level"

        /** Alias names declared in AndroidManifest.xml. */
        fun aliasName(level: WalletLevel): String = "com.homebudget.app.LauncherIcon" + when (level) {
            WalletLevel.FULL -> "Full"
            WalletLevel.THREE_QUARTERS -> "ThreeQuarters"
            WalletLevel.HALF -> "Half"
            WalletLevel.LOW -> "Low"
            WalletLevel.EMPTY -> "Empty"
            WalletLevel.OVER -> "Over"
        }
    }
}

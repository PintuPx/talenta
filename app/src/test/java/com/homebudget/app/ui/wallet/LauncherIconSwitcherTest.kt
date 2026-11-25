package com.homebudget.app.ui.wallet

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homebudget.app.domain.budget.WalletLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconSwitcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun enabledLevels(): List<WalletLevel> = WalletLevel.entries.filter {
        val state = pm.getComponentEnabledSetting(LauncherIconSwitcher(context).component(it))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it == WalletLevel.FULL)
    }

    @Test
    fun `exactly one alias is enabled after switching and the default is full`() {
        val switcher = LauncherIconSwitcher(context)
        assertEquals(listOf(WalletLevel.FULL), enabledLevels())
        switcher.apply(WalletLevel.LOW)
        assertEquals(listOf(WalletLevel.LOW), enabledLevels())
        switcher.apply(WalletLevel.OVER)
        assertEquals(listOf(WalletLevel.OVER), enabledLevels())
        switcher.apply(WalletLevel.FULL)
        assertEquals(listOf(WalletLevel.FULL), enabledLevels())
    }

    @Test
    fun `applying the same level again changes nothing`() {
        val switcher = LauncherIconSwitcher(context)
        switcher.apply(WalletLevel.HALF)
        // Tamper with the system state: an unchanged level must not touch it again.
        pm.setComponentEnabledSetting(
            switcher.component(WalletLevel.EMPTY), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        switcher.apply(WalletLevel.HALF)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(switcher.component(WalletLevel.EMPTY)),
        )
    }
}

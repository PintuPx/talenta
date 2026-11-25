package com.homebudget.app.ui

import com.homebudget.app.ui.common.shareLabel
import com.homebudget.app.ui.common.shareSpoken
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareLabelTest {
    @Test
    fun smallSharesNeverReadAsZero() {
        assertEquals("0%", shareLabel(0f))
        assertEquals("<1%", shareLabel(92f / 15092f))
        assertEquals("1%", shareLabel(0.01f))
        assertEquals("99%", shareLabel(15000f / 15092f))
        assertEquals("100%", shareLabel(1f))
        assertEquals("less than 1 percent", shareSpoken(0.006f))
        assertEquals("37 percent", shareSpoken(0.37f))
    }
}

package com.homebudget.app.domain.bill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveInfoTest {
    @Test
    fun detectsPaymentSecretsButNotOrdinaryReceiptText() {
        assertEquals(
            setOf(SensitiveInfo.Kind.PIN, SensitiveInfo.Kind.OTP, SensitiveInfo.Kind.CVV),
            SensitiveInfo.find("UPI PIN: 1234 OTP CVV"),
        )
        assertTrue(SensitiveInfo.find("Total 450.00, bill 998144").isEmpty())
    }

    @Test
    fun masksOnlyValidCardNumbersAndRetainsLastFourDigits() {
        val masked = SensitiveInfo.mask("Card 4111 1111 1111 1111, receipt 1234567890123456")
        assertTrue(masked.contains("XXXX XXXX XXXX 1111"))
        assertTrue(masked.contains("1234567890123456"))
        assertFalse(SensitiveInfo.find(masked).contains(SensitiveInfo.Kind.CARD_NUMBER))
    }
}

package com.homebudget.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {
    @Test
    fun releaseKeepsOnlyApprovedGroupsVisible() {
        assertFalse(FeatureFlags.FAMILY_SHARING)
        assertTrue(FeatureFlags.FAMILY_EXPENSES)
        assertTrue(FeatureFlags.INVESTMENTS)
        assertFalse(FeatureFlags.ARCHIVE)
        assertFalse(FeatureFlags.INVENTORY)
        assertFalse(FeatureFlags.PRICE_CHECKS)
        assertFalse(FeatureFlags.CHANGING_BILL_AMOUNTS)
        assertFalse(FeatureFlags.PLACE)
        assertFalse(FeatureFlags.UPDATE_CHECK)
        assertFalse(FeatureFlags.ENCRYPTED_BACKUP)
        assertFalse(FeatureFlags.CONNECTIONS)
    }
}

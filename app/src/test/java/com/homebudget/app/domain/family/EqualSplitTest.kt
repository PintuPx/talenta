package com.homebudget.app.domain.family

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualSplitTest {
    @Test
    fun remainderGoesToTheFirstMembers() {
        assertEquals(listOf(4L, 3L, 3L), equalShares(10, 3))
    }

    @Test
    fun zeroAndSingleMemberSplitsAreExact() {
        assertEquals(listOf(0L, 0L), equalShares(0, 2))
        assertEquals(listOf(17L), equalShares(17, 1))
        assertEquals(emptyList<Long>(), equalShares(17, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTotalIsRejected() {
        equalShares(-1, 2)
    }
}

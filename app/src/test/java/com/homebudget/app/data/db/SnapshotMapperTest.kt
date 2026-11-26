package com.homebudget.app.data.db

import com.homebudget.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMapperTest {
    @Test
    fun transactionSnapshotRoundTripResolvesReferencesByUid() {
        val entity = TransactionEntity(
            id = 4,
            type = TransactionType.EXPENSE,
            amount = 1250,
            accountId = 7,
            toAccountId = null,
            categoryId = 9,
            isRefund = false,
            occurredAt = 1_763_000_000,
            note = "Lunch",
            payee = "Cafe",
            createdAt = 10,
            updatedAt = 20,
            billId = null,
            recurringId = null,
            uid = "transaction-uid",
            isPrivate = true,
        )

        val snapshot = entity.toSnapshot(
            SnapshotReferences(
                accountUidsById = mapOf(7L to "account-uid"),
                categoryUidsById = mapOf(9L to "category-uid"),
            ),
        )
        val restored = snapshot.toEntity(
            LocalEntityReferences(
                accountIdsByUid = mapOf("account-uid" to 70L),
                categoryIdsByUid = mapOf("category-uid" to 90L),
            ),
            id = 40,
        )

        assertEquals("account-uid", snapshot.accountUid)
        assertEquals("category-uid", snapshot.categoryUid)
        assertEquals(40L, restored.id)
        assertEquals(70L, restored.accountId)
        assertEquals(90L, restored.categoryId)
        assertEquals(entity.uid, restored.uid)
        assertEquals(entity.amount, restored.amount)
        assertTrue(restored.isPrivate)
    }
}

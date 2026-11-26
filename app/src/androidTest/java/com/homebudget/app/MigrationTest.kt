package com.homebudget.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homebudget.app.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every exported schema version migrates to the current one with the registered migrations.
 * Requires a device/emulator (instrumented). When adding schema version N, add a test that creates
 * version N-1, inserts representative rows, migrates, and verifies data (balances must be unchanged).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun allMigrationsReachCurrentSchema() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at) VALUES (1,'Cash','CASH',1000,1,0,0,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at) VALUES (1,'Food','EXPENSE','groceries',4281236786,0,1,0,0)")
            execSQL("INSERT INTO transactions (id, type, amount, account_id, to_account_id, category_id, is_refund, occurred_at, note, payee, created_at, updated_at) VALUES (1,'EXPENSE',250,1,NULL,1,0,1763000000,'lunch','Shop',0,0)")
            close()
        }
        helper.runMigrationsAndValidate(dbName, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS)

        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        db.openHelper.writableDatabase.query("SELECT opening_balance FROM accounts WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(1000L, it.getLong(0))
        }
        db.openHelper.writableDatabase.query("SELECT amount, is_private, uid FROM transactions WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(250L, it.getLong(0))
            assertEquals(0, it.getInt(1))
            assertTrue(it.getString(2).isNotBlank())
        }
        db.openHelper.writableDatabase.query(
            "SELECT version FROM entity_versions WHERE entity_type = 'transaction' AND entity_uid = " +
                "(SELECT uid FROM transactions WHERE id = 1)",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate3To4PreservesRowsAndCreatesUidBasedInitialHistory() {
        val name = "migration-3-4-data"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at) VALUES (1,'Cash','CASH',1000,1,0,0,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at, parent_id) VALUES (1,'Food','EXPENSE','groceries',4281236786,0,1,0,0,NULL)")
            execSQL("INSERT INTO bills (id, merchant, bill_date, total, account_id, image_file, ocr_text, status, created_at, updated_at) VALUES (1,'Shop',1763000000,250,1,NULL,NULL,'POSTED',0,0)")
            execSQL("INSERT INTO transactions (id, type, amount, account_id, to_account_id, category_id, is_refund, occurred_at, note, payee, created_at, updated_at, bill_id, recurring_id) VALUES (1,'EXPENSE',250,1,NULL,1,0,1763000000,'food','Shop',0,0,1,NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_3_4)
        val accountUid = db.query("SELECT uid FROM accounts WHERE id = 1").use {
            it.moveToFirst()
            it.getString(0)
        }
        val categoryUid = db.query("SELECT uid FROM categories WHERE id = 1").use {
            it.moveToFirst()
            it.getString(0)
        }
        val transactionUid = db.query("SELECT uid, is_private FROM transactions WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(it.getColumnIndexOrThrow("is_private")))
            it.getString(it.getColumnIndexOrThrow("uid"))
        }
        db.query("SELECT account_id, category_id, bill_id FROM transactions WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(1L, it.getLong(0))
            assertEquals(1L, it.getLong(1))
            assertEquals(1L, it.getLong(2))
        }
        db.query("SELECT snapshot FROM entity_versions WHERE entity_type = 'transaction' AND entity_uid = ?", arrayOf(transactionUid)).use {
            it.moveToFirst()
            val snapshot = it.getString(0)
            assertTrue(snapshot.contains(accountUid))
            assertTrue(snapshot.contains(categoryUid))
        }
        db.query("SELECT COUNT(*) FROM entity_versions WHERE version = 1 AND status = 'APPROVED'").use {
            it.moveToFirst()
            assertTrue(it.getInt(0) >= 4)
        }
        db.close()
    }

    @Test
    fun migrate4To5PreservesRecurringRuleAndDefaultsAmountChangesOff() {
        val name = "migration-4-5-data"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at, uid, is_private) VALUES (1,'Cash','CASH',1000,1,0,0,0,0,'cash-uid',0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at, parent_id, uid) VALUES (1,'Food','EXPENSE','groceries',4281236786,0,1,0,0,NULL,'food-uid')")
            execSQL("INSERT INTO recurring_rules (id, type, amount, account_id, to_account_id, category_id, is_refund, note, payee, frequency, repeat_interval, start_at, posted_count, active, created_at, updated_at, uid, is_private) VALUES (1,'EXPENSE',500,1,NULL,1,0,'','Rent','MONTHLY',1,1763000000,0,1,0,0,'rent-uid',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)
        db.query("SELECT amount, uid, is_private, amount_changes FROM recurring_rules WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(500L, it.getLong(0))
            assertEquals("rent-uid", it.getString(1))
            assertEquals(0, it.getInt(2))
            assertEquals(0, it.getInt(3))
        }
        db.close()
    }

    /** Inserts v3-shaped rows (3.json): account, expense category, one recurring rule, one transaction linked to it. */
    private fun createV3WithRecurringRule(name: String) {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at) VALUES (1,'Test Cash','CASH',5000000,1,0,0,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at, parent_id) VALUES (1,'Housing','EXPENSE','groceries',4281236786,0,1,0,0,NULL)")
            execSQL("INSERT INTO recurring_rules (id, type, amount, account_id, to_account_id, category_id, is_refund, note, payee, frequency, repeat_interval, start_at, posted_count, active, created_at, updated_at) VALUES (1,'EXPENSE',1500000,1,NULL,1,0,'monthly rent','Test Rent','MONTHLY',1,1763000000,3,1,0,0)")
            execSQL("INSERT INTO transactions (id, type, amount, account_id, to_account_id, category_id, is_refund, occurred_at, note, payee, created_at, updated_at, bill_id, recurring_id) VALUES (1,'EXPENSE',1500000,1,NULL,1,0,1763000000,'monthly rent','Test Rent',0,0,NULL,1)")
            close()
        }
    }

    /** Regression: v3 -> v4 used to crash on any recurring_rules row (read amount_changes before it existed). */
    @Test
    fun migrate3To4WithRecurringRuleCreatesInitialHistory() {
        val name = "migration-3-4-recurring"
        createV3WithRecurringRule(name)
        val db = helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_3_4)

        val accountUid = db.query("SELECT uid FROM accounts WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertTrue(accountUid.isNotBlank())

        val ruleUid = db.query(
            "SELECT amount, payee, frequency, posted_count, is_private, uid FROM recurring_rules WHERE id = 1",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1500000L, it.getLong(0))
            assertEquals("Test Rent", it.getString(1))
            assertEquals("MONTHLY", it.getString(2))
            assertEquals(3, it.getInt(3))
            assertEquals(0, it.getInt(4))
            assertTrue(it.getString(5).isNotBlank())
            it.getString(5)
        }

        db.query(
            "SELECT version, status, snapshot FROM entity_versions WHERE entity_type = 'recurring_rule' AND entity_uid = ?",
            arrayOf(ruleUid),
        ).use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals("APPROVED", it.getString(1))
            val snapshot = org.json.JSONObject(it.getString(2))
            assertEquals(false, snapshot.getBoolean("amountChanges"))
            assertEquals(accountUid, snapshot.getString("accountUid"))
            assertEquals(1500000L, snapshot.getLong("amount"))
        }

        db.query("SELECT recurring_id FROM transactions WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
        }
        db.close()
    }

    /** Same v3 data carried through both migrations: the rule survives and amount_changes defaults to off. */
    @Test
    fun migrate3To5WithRecurringRuleKeepsDataAndDefaultsAmountChangesOff() {
        val name = "migration-3-5-recurring"
        createV3WithRecurringRule(name)
        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)

        db.query("SELECT amount, payee, frequency, posted_count, is_private, amount_changes, uid FROM recurring_rules WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1500000L, it.getLong(0))
            assertEquals("Test Rent", it.getString(1))
            assertEquals("MONTHLY", it.getString(2))
            assertEquals(3, it.getInt(3))
            assertEquals(0, it.getInt(4))
            assertEquals(0, it.getInt(5))
            assertTrue(it.getString(6).isNotBlank())
        }
        db.query("SELECT recurring_id, amount FROM transactions WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals(1500000L, it.getLong(1))
        }
        db.close()
    }

    /** v1 -> v2 keeps every Phase 1 row and balance, and adds grocery groups under an existing Groceries. */
    @Test
    fun migrate1To2KeepsDataAndAddsGroceryGroups() {
        val name = "migration-1-2"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at) VALUES (1,'Cash','CASH',1000,1,0,0,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at) VALUES (1,'Groceries','EXPENSE','groceries',4281236786,0,1,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at) VALUES (2,'Salary','INCOME','salary',4281236786,0,1,0,0)")
            execSQL("INSERT INTO transactions (id, type, amount, account_id, to_account_id, category_id, is_refund, occurred_at, note, payee, created_at, updated_at) VALUES (1,'EXPENSE',250,1,NULL,1,0,1763000000,'veg','',0,0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT amount, category_id, bill_id FROM transactions WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(250L, it.getLong(0))
            assertEquals(1L, it.getLong(1))
            assertEquals(true, it.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM categories WHERE parent_id = 1").use {
            it.moveToFirst()
            assertEquals(10, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM categories WHERE parent_id IS NULL").use {
            it.moveToFirst()
            assertEquals(2, it.getInt(0))
        }
        db.close()
    }

    /** v2 -> v3 keeps every row (including bills) and adds an empty recurring_rules table. */
    @Test
    fun migrate2To3KeepsDataAndAddsRecurringRules() {
        val name = "migration-2-3"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO accounts (id, name, type, opening_balance, include_in_total, archived, sort_order, created_at, updated_at) VALUES (1,'Cash','CASH',1000,1,0,0,0,0)")
            execSQL("INSERT INTO categories (id, name, kind, icon_key, color_argb, sort_order, active, created_at, updated_at, parent_id) VALUES (1,'Groceries','EXPENSE','groceries',4281236786,0,1,0,0,NULL)")
            execSQL("INSERT INTO bills (id, merchant, bill_date, total, account_id, image_file, ocr_text, status, created_at, updated_at) VALUES (1,'Shop',1763000000,250,1,NULL,NULL,'POSTED',0,0)")
            execSQL("INSERT INTO transactions (id, type, amount, account_id, to_account_id, category_id, is_refund, occurred_at, note, payee, created_at, updated_at, bill_id) VALUES (1,'EXPENSE',250,1,NULL,1,0,1763000000,'veg','',0,0,1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT amount, bill_id, recurring_id FROM transactions WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(250L, it.getLong(0))
            assertEquals(1L, it.getLong(1))
            assertEquals(true, it.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM recurring_rules").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        // The new foreign key works: a rule can be inserted and linked.
        db.execSQL("INSERT INTO recurring_rules (id, type, amount, account_id, to_account_id, category_id, is_refund, note, payee, frequency, repeat_interval, start_at, posted_count, active, created_at, updated_at) VALUES (1,'EXPENSE',500,1,NULL,1,0,'','Rent','MONTHLY',1,1763000000,0,1,0,0)")
        db.execSQL("UPDATE transactions SET recurring_id = 1 WHERE id = 1")
        db.close()
    }
}

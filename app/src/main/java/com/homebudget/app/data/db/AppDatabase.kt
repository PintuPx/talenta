package com.homebudget.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        BillEntity::class, BillItemEntity::class, ItemRuleEntity::class, RecurringRuleEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun billDao(): BillDao
    abstract fun itemRuleDao(): ItemRuleDao
    abstract fun recurringDao(): RecurringDao

    companion object {
        const val VERSION = 3
        const val NAME = "homebudget.db"

        /**
         * v1 -> v2: category groups (categories.parent_id), bills, bill_items, item_rules and
         * transactions.bill_id. Existing rows are untouched; default grocery groups are added under an
         * existing expense category named "Groceries" if there is one.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `parent_id` INTEGER REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parent_id` ON `categories` (`parent_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `merchant` TEXT NOT NULL, " +
                        "`bill_date` INTEGER NOT NULL, `total` INTEGER, `account_id` INTEGER, `image_file` TEXT, `ocr_text` TEXT, " +
                        "`status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_account_id` ON `bills` (`account_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_bill_date` ON `bills` (`bill_date`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bill_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bill_id` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `description` TEXT NOT NULL, `amount` INTEGER NOT NULL, `category_id` INTEGER, " +
                        "`included` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`bill_id`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_items_bill_id` ON `bill_items` (`bill_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_items_category_id` ON `bill_items` (`category_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_rules` (`key` TEXT NOT NULL, `category_id` INTEGER NOT NULL, `use_count` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`key`), " +
                        "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_rules_category_id` ON `item_rules` (`category_id`)")

                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `bill_id` INTEGER REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_bill_id` ON `transactions` (`bill_id`)")

                db.query("SELECT id FROM categories WHERE kind = 'EXPENSE' AND name = ? AND parent_id IS NULL ORDER BY id LIMIT 1", arrayOf(DefaultData.GROCERIES))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    ?.let { groceriesId -> insertCategories(db, DefaultData.groceryGroups(groceriesId, System.currentTimeMillis())) }
            }
        }

        /** v2 -> v3: recurring rules and transactions.recurring_id. Existing rows are untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATION_2_3_CREATE_RULES)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_account_id` ON `recurring_rules` (`account_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_to_account_id` ON `recurring_rules` (`to_account_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_category_id` ON `recurring_rules` (`category_id`)")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `recurring_id` INTEGER REFERENCES `recurring_rules`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_recurring_id` ON `transactions` (`recurring_id`)")
            }
        }

        // Must match app/schemas/.../3.json exactly (MigrationTest validates it).
        private const val MIGRATION_2_3_CREATE_RULES =
            "CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                "`amount` INTEGER NOT NULL, `account_id` INTEGER NOT NULL, `to_account_id` INTEGER, `category_id` INTEGER, " +
                "`is_refund` INTEGER NOT NULL, `note` TEXT NOT NULL, `payee` TEXT NOT NULL, `frequency` TEXT NOT NULL, " +
                "`repeat_interval` INTEGER NOT NULL, `start_at` INTEGER NOT NULL, `posted_count` INTEGER NOT NULL, " +
                "`active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`to_account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"

        /**
         * Explicit migrations, appended in order. Destructive migration is deliberately NOT enabled:
         * losing financial data on upgrade is never acceptable.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        internal fun insertCategories(db: SupportSQLiteDatabase, categories: List<CategoryEntity>): List<Long> =
            categories.map { c ->
                db.insert("categories", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("name", c.name)
                    put("kind", c.kind.name)
                    put("icon_key", c.iconKey)
                    put("color_argb", c.colorArgb)
                    put("sort_order", c.sortOrder)
                    put("active", if (c.active) 1 else 0)
                    put("created_at", c.createdAt)
                    put("updated_at", c.updatedAt)
                    if (c.parentId != null) put("parent_id", c.parentId) else putNull("parent_id")
                })
            }
    }
}

/** Seeds default categories (with grocery groups) and a Cash account when the database is first created. */
class SeedDefaultsCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val mains = DefaultData.categories(now)
        val ids = AppDatabase.insertCategories(db, mains)
        val groceriesId = ids[mains.indexOfFirst { it.name == DefaultData.GROCERIES }]
        AppDatabase.insertCategories(db, DefaultData.groceryGroups(groceriesId, now))
        DefaultData.accounts(now).forEach { a ->
            db.insert("accounts", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("name", a.name)
                put("type", a.type.name)
                put("opening_balance", a.openingBalance)
                put("include_in_total", if (a.includeInTotal) 1 else 0)
                put("archived", if (a.archived) 1 else 0)
                put("sort_order", a.sortOrder)
                put("created_at", now)
                put("updated_at", now)
            })
        }
    }
}

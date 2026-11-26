package com.homebudget.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.UUID

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TransactionEntity::class, BudgetEntity::class,
        BillEntity::class, BillItemEntity::class, ItemRuleEntity::class, RecurringRuleEntity::class,
        EntityVersionEntity::class, MonthLockEntity::class, MemberEntity::class, SyncStateEntity::class,
        FamilyPersonEntity::class, FamilyTagEntity::class, FamilyTagMemberEntity::class,
        TransactionMemberEntity::class, InvestmentValueEntity::class,
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
    abstract fun entityVersionDao(): EntityVersionDao
    abstract fun monthLockDao(): MonthLockDao
    abstract fun memberDao(): MemberDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun familyAllocationDao(): FamilyAllocationDao
    abstract fun investmentValueDao(): InvestmentValueDao

    companion object {
        const val VERSION = 5
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

        // v3 -> v4: entity uids, private flags, version history, month locks, members and sync state (see MigrationTest).
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "accounts",
                    "categories",
                    "transactions",
                    "bills",
                    "bill_items",
                    "budgets",
                    "recurring_rules",
                    "item_rules",
                ).forEach { addUidColumn(db, it) }

                listOf("accounts", "transactions", "bills", "budgets", "recurring_rules")
                    .forEach { table ->
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `is_private` INTEGER NOT NULL DEFAULT 0")
                    }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `entity_versions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `entity_uid` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `base_version` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `deleted` INTEGER NOT NULL,
                        `snapshot` TEXT NOT NULL,
                        `snapshot_format` INTEGER NOT NULL,
                        `author_id` TEXT NOT NULL,
                        `author_name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `decided_by` TEXT,
                        `decided_at` INTEGER,
                        `decision_note` TEXT,
                        `bulk_id` TEXT,
                        `is_private` INTEGER NOT NULL,
                        `event_uid` TEXT NOT NULL,
                        `archived` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entity_versions_entity_type_entity_uid_version` " +
                        "ON `entity_versions` (`entity_type`, `entity_uid`, `version`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_versions_status` ON `entity_versions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_versions_created_at` ON `entity_versions` (`created_at`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_entity_versions_event_uid` " +
                        "ON `entity_versions` (`event_uid`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `month_locks` (" +
                        "`month` INTEGER NOT NULL, `closed_at` INTEGER NOT NULL, `closed_by` TEXT NOT NULL, " +
                        "PRIMARY KEY(`month`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `members` (" +
                        "`member_id` TEXT NOT NULL, `email` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`active` INTEGER NOT NULL, `app_version_code` INTEGER NOT NULL, `app_version_name` TEXT NOT NULL, " +
                        "`sync_format` INTEGER NOT NULL, `last_seen_at` INTEGER, PRIMARY KEY(`member_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
                )

                insertInitialVersions(db, System.currentTimeMillis() / 1000L)
            }
        }

        // v4 -> v5: recurring amount_changes, family expense tables and investment values (see MigrationTest).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recurring_rules` ADD COLUMN `amount_changes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_people` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`uid` TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_family_people_uid` ON `family_people` (`uid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`uid` TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_family_tags_uid` ON `family_tags` (`uid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_tag_members` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tag_uid` TEXT NOT NULL, " +
                        "`person_uid` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `uid` TEXT NOT NULL DEFAULT '', " +
                        "FOREIGN KEY(`tag_uid`) REFERENCES `family_tags`(`uid`) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`person_uid`) REFERENCES `family_people`(`uid`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_family_tag_members_uid` ON `family_tag_members` (`uid`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_family_tag_members_tag_uid_person_uid` " +
                        "ON `family_tag_members` (`tag_uid`, `person_uid`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_family_tag_members_person_uid` ON `family_tag_members` (`person_uid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_members` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transaction_uid` TEXT NOT NULL, " +
                        "`person_uid` TEXT NOT NULL, `share_amount` INTEGER NOT NULL, " +
                        "`is_private` INTEGER NOT NULL DEFAULT 0, `uid` TEXT NOT NULL DEFAULT '', " +
                        "FOREIGN KEY(`person_uid`) REFERENCES `family_people`(`uid`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_members_uid` ON `transaction_members` (`uid`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_members_transaction_uid_person_uid` " +
                        "ON `transaction_members` (`transaction_uid`, `person_uid`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_members_person_uid` ON `transaction_members` (`person_uid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_members_is_private` ON `transaction_members` (`is_private`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `investment_values` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, " +
                        "`value_minor` INTEGER NOT NULL, `recorded_at` INTEGER NOT NULL, " +
                        "`is_private` INTEGER NOT NULL DEFAULT 0, `uid` TEXT NOT NULL DEFAULT '', " +
                        "FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_values_uid` ON `investment_values` (`uid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_values_account_id` ON `investment_values` (`account_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_investment_values_is_private` ON `investment_values` (`is_private`)")
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

        private fun addUidColumn(db: SupportSQLiteDatabase, table: String) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `$table` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)",
            )
        }

        private fun insertInitialVersions(db: SupportSQLiteDatabase, migrationTime: Long) {
            val accountUids = loadUids(db, "accounts")
            val categoryUids = loadUids(db, "categories")
            val billUids = loadUids(db, "bills")
            val recurringUids = loadUids(db, "recurring_rules")

            insertInitialVersions(db, "accounts", "account", migrationTime, "created_at", true) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("name", row.string("name"))
                    .put("type", row.string("type"))
                    .put("openingBalance", row.long("opening_balance"))
                    .put("includeInTotal", row.boolean("include_in_total"))
                    .put("archived", row.boolean("archived"))
                    .put("sortOrder", row.int("sort_order"))
                    .put("createdAt", row.long("created_at"))
                    .put("updatedAt", row.long("updated_at"))
                    .put("isPrivate", row.boolean("is_private"))
            }
            insertInitialVersions(db, "categories", "category", migrationTime, "created_at") { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("name", row.string("name"))
                    .put("kind", row.string("kind"))
                    .put("iconKey", row.string("icon_key"))
                    .put("colorArgb", row.long("color_argb"))
                    .put("sortOrder", row.int("sort_order"))
                    .put("active", row.boolean("active"))
                    .put("createdAt", row.long("created_at"))
                    .put("updatedAt", row.long("updated_at"))
                    .putNullable("parentUid", row.nullableLong("parent_id")?.let { categoryUids.uid(it, "parent_id") })
            }
            insertInitialVersions(db, "transactions", "transaction", migrationTime, "created_at", true) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("type", row.string("type"))
                    .put("amount", row.long("amount"))
                    .put("accountUid", accountUids.uid(row.long("account_id"), "account_id"))
                    .putNullable("toAccountUid", row.nullableLong("to_account_id")?.let { accountUids.uid(it, "to_account_id") })
                    .putNullable("categoryUid", row.nullableLong("category_id")?.let { categoryUids.uid(it, "category_id") })
                    .put("isRefund", row.boolean("is_refund"))
                    .put("occurredAt", row.long("occurred_at"))
                    .put("note", row.string("note"))
                    .put("payee", row.string("payee"))
                    .put("createdAt", row.long("created_at"))
                    .put("updatedAt", row.long("updated_at"))
                    .putNullable("billUid", row.nullableLong("bill_id")?.let { billUids.uid(it, "bill_id") })
                    .putNullable("recurringUid", row.nullableLong("recurring_id")?.let { recurringUids.uid(it, "recurring_id") })
                    .put("isPrivate", row.boolean("is_private"))
            }
            insertInitialVersions(db, "bills", "bill", migrationTime, "created_at", true) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("merchant", row.string("merchant"))
                    .put("billDate", row.long("bill_date"))
                    .putNullable("total", row.nullableLong("total"))
                    .putNullable("accountUid", row.nullableLong("account_id")?.let { accountUids.uid(it, "account_id") })
                    .putNullable("imageFile", row.nullableString("image_file"))
                    .putNullable("ocrText", row.nullableString("ocr_text"))
                    .put("status", row.string("status"))
                    .put("createdAt", row.long("created_at"))
                    .put("updatedAt", row.long("updated_at"))
                    .put("isPrivate", row.boolean("is_private"))
            }
            insertInitialVersions(db, "bill_items", "bill_item", migrationTime) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("billUid", billUids.uid(row.long("bill_id"), "bill_id"))
                    .put("position", row.int("position"))
                    .put("description", row.string("description"))
                    .put("amount", row.long("amount"))
                    .putNullable("categoryUid", row.nullableLong("category_id")?.let { categoryUids.uid(it, "category_id") })
                    .put("included", row.boolean("included"))
            }
            insertInitialVersions(db, "budgets", "budget", migrationTime, privateAware = true) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("month", row.int("month"))
                    .putNullable("categoryUid", row.nullableLong("category_id")?.let { categoryUids.uid(it, "category_id") })
                    .put("limitAmount", row.long("limit_amount"))
                    .put("isPrivate", row.boolean("is_private"))
            }
            insertInitialVersions(db, "recurring_rules", "recurring_rule", migrationTime, "created_at", true) { row ->
                JSONObject()
                    .put("id", row.long("id"))
                    .put("uid", row.string("uid"))
                    .put("type", row.string("type"))
                    .put("amount", row.long("amount"))
                    .put("accountUid", accountUids.uid(row.long("account_id"), "account_id"))
                    .putNullable("toAccountUid", row.nullableLong("to_account_id")?.let { accountUids.uid(it, "to_account_id") })
                    .putNullable("categoryUid", row.nullableLong("category_id")?.let { categoryUids.uid(it, "category_id") })
                    .put("isRefund", row.boolean("is_refund"))
                    .put("note", row.string("note"))
                    .put("payee", row.string("payee"))
                    .put("frequency", row.string("frequency"))
                    .put("interval", row.int("repeat_interval"))
                    .put("startAt", row.long("start_at"))
                    .put("postedCount", row.int("posted_count"))
                    .put("active", row.boolean("active"))
                    .put("createdAt", row.long("created_at"))
                    .put("updatedAt", row.long("updated_at"))
                    .put("isPrivate", row.boolean("is_private"))
                    // amount_changes is added in MIGRATION_4_5; it does not exist yet at v3 -> v4.
                    .put("amountChanges", false)
            }
            insertInitialVersions(db, "item_rules", "item_rule", migrationTime, "updated_at") { row ->
                JSONObject()
                    .put("key", row.string("key"))
                    .put("uid", row.string("uid"))
                    .put("categoryUid", categoryUids.uid(row.long("category_id"), "category_id"))
                    .put("useCount", row.int("use_count"))
                    .put("updatedAt", row.long("updated_at"))
            }
        }

        private fun insertInitialVersions(
            db: SupportSQLiteDatabase,
            table: String,
            entityType: String,
            migrationTime: Long,
            createdAtColumn: String? = null,
            privateAware: Boolean = false,
            snapshot: (Cursor) -> JSONObject,
        ) {
            db.query("SELECT * FROM `$table`").use { rows ->
                while (rows.moveToNext()) {
                    val createdAt = createdAtColumn?.let { rows.long(it) } ?: migrationTime
                    val isPrivate = privateAware && rows.boolean("is_private")
                    val values = ContentValues().apply {
                        put("entity_type", entityType)
                        put("entity_uid", rows.string("uid"))
                        put("version", 1)
                        put("status", "APPROVED")
                        put("base_version", 0)
                        put("kind", "ADD")
                        put("deleted", 0)
                        put("snapshot", snapshot(rows).toString())
                        put("snapshot_format", 1)
                        put("author_id", "me")
                        put("author_name", "me")
                        put("created_at", createdAt)
                        put("is_private", if (isPrivate) 1 else 0)
                        put("event_uid", UUID.randomUUID().toString())
                        put("archived", 0)
                    }
                    db.insert("entity_versions", SQLiteDatabase.CONFLICT_ABORT, values)
                }
            }
        }

        private fun loadUids(db: SupportSQLiteDatabase, table: String): Map<Long, String> =
            buildMap {
                db.query("SELECT `id`, `uid` FROM `$table`").use { rows ->
                    while (rows.moveToNext()) put(rows.getLong(0), rows.getString(1))
                }
            }

        private fun Map<Long, String>.uid(id: Long, column: String): String =
            get(id) ?: error("Missing UID for existing $column reference")

        private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

        private fun Cursor.nullableString(column: String): String? =
            getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

        private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

        private fun Cursor.nullableLong(column: String): Long? =
            getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }

        private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

        private fun Cursor.boolean(column: String): Boolean = int(column) != 0

        private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
            put(name, value ?: JSONObject.NULL)

        /**
         * Explicit migrations, appended in order. Destructive migration is deliberately NOT enabled:
         * losing financial data on upgrade is never acceptable.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        internal fun insertCategories(
            db: SupportSQLiteDatabase,
            categories: List<CategoryEntity>,
            includeIdentity: Boolean = false,
        ): List<Long> =
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
                    if (includeIdentity) put("uid", c.uid)
                })
            }
    }
}

/** Seeds default categories (with grocery groups) and a Cash account when the database is first created. */
class SeedDefaultsCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val mains = DefaultData.categories(now)
        val ids = AppDatabase.insertCategories(db, mains, includeIdentity = true)
        val groceriesId = ids[mains.indexOfFirst { it.name == DefaultData.GROCERIES }]
        AppDatabase.insertCategories(db, DefaultData.groceryGroups(groceriesId, now), includeIdentity = true)
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
                put("uid", a.uid)
                put("is_private", if (a.isPrivate) 1 else 0)
            })
        }
    }
}

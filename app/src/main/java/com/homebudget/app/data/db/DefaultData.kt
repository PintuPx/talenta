package com.homebudget.app.data.db

import com.homebudget.app.domain.model.AccountType
import com.homebudget.app.domain.model.CategoryKind

/**
 * Default categories and accounts created for new users (and after "Clear all data").
 * Category colors are functional (to tell categories apart), not brand colors; each is paired
 * with an icon and a text label so color is never the only cue.
 */
object DefaultData {

    private val expense = listOf(
        Triple("Groceries", "groceries", 0xFF2E7D32),
        Triple("Rent / Mortgage", "home", 0xFF5D4037),
        Triple("Electricity & Utilities", "utilities", 0xFFF9A825),
        Triple("Transportation", "transport", 0xFF1565C0),
        Triple("Fuel", "fuel", 0xFFE65100),
        Triple("Healthcare", "health", 0xFFC62828),
        Triple("Education", "education", 0xFF283593),
        Triple("Shopping", "shopping", 0xFFAD1457),
        Triple("Dining", "dining", 0xFFEF6C00),
        Triple("Entertainment", "entertainment", 0xFF6A1B9A),
        Triple("Insurance", "insurance", 0xFF00695C),
        Triple("Household Maintenance", "maintenance", 0xFF795548),
        Triple("Subscriptions", "subscriptions", 0xFF0277BD),
        Triple("Miscellaneous", "misc", 0xFF546E7A),
    )

    private val income = listOf(
        Triple("Salary", "salary", 0xFF2E7D32),
        Triple("Business Income", "business", 0xFF00695C),
        Triple("Freelance", "freelance", 0xFF1565C0),
        Triple("Interest", "interest", 0xFF6A1B9A),
        Triple("Investment Income", "investment", 0xFF283593),
        Triple("Other Income", "misc", 0xFF546E7A),
    )

    const val GROCERIES = "Groceries"

    /** Default groups inside Groceries (v2). Names match CategorySuggester.builtIn. */
    private val groceryGroups = listOf(
        Triple("Vegetables", "vegetables", 0xFF2E7D32),
        Triple("Fruits", "fruits", 0xFFEF6C00),
        Triple("Dairy & Eggs", "dairy", 0xFF0277BD),
        Triple("Grains & Pulses", "grains", 0xFF795548),
        Triple("Oils & Spices", "spices", 0xFFF9A825),
        Triple("Meat & Fish", "meat", 0xFFC62828),
        Triple("Bakery", "bakery", 0xFF5D4037),
        Triple("Snacks & Beverages", "snacks", 0xFFAD1457),
        Triple("Household Supplies", "household", 0xFF00695C),
        Triple("Personal Care", "personal_care", 0xFF6A1B9A),
    )

    fun groceryGroups(groceriesId: Long, now: Long): List<CategoryEntity> =
        groceryGroups.mapIndexed { i, (name, icon, color) ->
            CategoryEntity(0, name, CategoryKind.EXPENSE, icon, color, i, true, now, now, parentId = groceriesId)
        }

    /** Main groups only; insert [groceryGroups] afterwards with the new Groceries id. */
    fun categories(now: Long): List<CategoryEntity> =
        expense.mapIndexed { i, (name, icon, color) ->
            CategoryEntity(0, name, CategoryKind.EXPENSE, icon, color, i, true, now, now)
        } + income.mapIndexed { i, (name, icon, color) ->
            CategoryEntity(0, name, CategoryKind.INCOME, icon, color, i, true, now, now)
        }

    fun accounts(now: Long): List<AccountEntity> = listOf(
        AccountEntity(0, "Cash", AccountType.CASH, 0, includeInTotal = true, archived = false, sortOrder = 0, createdAt = now, updatedAt = now),
    )
}

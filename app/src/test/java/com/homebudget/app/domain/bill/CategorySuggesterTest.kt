package com.homebudget.app.domain.bill

import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySuggesterTest {

    private fun c(id: Long, name: String, parent: Long? = null, active: Boolean = true, kind: CategoryKind = CategoryKind.EXPENSE) =
        Category(id, name, kind, "misc", 0, active = active, parentId = parent)

    private val groceries = c(1, "Groceries")
    private val dining = c(2, "Dining")
    private val veg = c(10, "Vegetables", 1)
    private val dairy = c(11, "Dairy & Eggs", 1)
    private val spices = c(12, "Oils & Spices", 1)
    private val all = listOf(groceries, dining, veg, dairy, spices, c(20, "Salary", kind = CategoryKind.INCOME))

    @Test
    fun keyDropsQuantitiesUnitsAndPunctuation() {
        assertEquals("veggie", CategorySuggester.key("Veggie 1kg"))
        assertEquals("tomato red", CategorySuggester.key("TOMATO-RED 500 g"))
        assertEquals("", CategorySuggester.key("2 x 40.00"))
    }

    @Test
    fun veggieGoesToVegetablesInGroceries() {
        // The user's example: "Veggie" -> group Vegetables -> main group Groceries.
        assertEquals(veg.id, CategorySuggester.suggest("Veggie", emptyMap(), all))
        assertEquals(veg.id, CategorySuggester.suggest("Onions 1kg", emptyMap(), all))
        assertEquals(dairy.id, CategorySuggester.suggest("Amul Milk 500ml", emptyMap(), all))
    }

    @Test
    fun earlierGroupsWinTies() {
        assertEquals(spices.id, CategorySuggester.suggest("Chilli powder", emptyMap(), all))
        assertEquals(veg.id, CategorySuggester.suggest("Green chilli", emptyMap(), all))
    }

    @Test
    fun learnedRulesOverrideBuiltInKeywords() {
        val learned = mapOf("paneer tikka" to dining.id)
        assertEquals(dining.id, CategorySuggester.suggest("Paneer Tikka", learned, all))
        // Partial match: every word of the learned key appears in the item.
        assertEquals(dining.id, CategorySuggester.suggest("Paneer Tikka Half", learned, all))
        // Built-in keyword still applies to other paneer items.
        assertEquals(dairy.id, CategorySuggester.suggest("Paneer 200g", learned, all))
    }

    @Test
    fun fallsBackToMainGroupWhenTheGroupWasDeletedOrIsInactive() {
        val noVeg = all.map { if (it.id == veg.id) it.copy(active = false) else it }
        assertEquals(groceries.id, CategorySuggester.suggest("Tomato", emptyMap(), noVeg))
    }

    @Test
    fun learnedRuleToAnInactiveOrIncomeCategoryIsIgnored() {
        assertEquals(veg.id, CategorySuggester.suggest("Tomato", mapOf("tomato" to 20L), all))
        assertEquals(veg.id, CategorySuggester.suggest("Tomato", mapOf("tomato" to 999L), all))
    }

    @Test
    fun unknownItemsGetNoSuggestion() {
        assertNull(CategorySuggester.suggest("Widget XL", emptyMap(), all))
        assertNull(CategorySuggester.suggest("123", emptyMap(), all))
    }
}

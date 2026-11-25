package com.homebudget.app.domain.bill

import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.CategoryKind

/**
 * Suggests an expense category (preferably a group such as "Vegetables") for a bill line.
 *
 * Order of evidence:
 * 1. What the user approved before for the same item name ([learned], exact key).
 * 2. A learned rule whose words all appear in the item name (longest rule first).
 * 3. The built-in keyword list for the default grocery groups.
 *
 * A suggestion is only a pre-filled choice: the user approves or changes every line before posting.
 */
object CategorySuggester {

    private val unitWords = setOf(
        "kg", "kgs", "g", "gm", "gms", "gram", "grams", "pc", "pcs", "nos", "no", "ltr", "lt", "l", "ml",
        "pkt", "pack", "each", "ea", "x", "rs",
    )

    /** Stable key for an item name: lower case letters only, without quantities and units. */
    fun key(description: String): String =
        description.lowercase()
            .replace(Regex("""[^a-z\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length > 1 && it !in unitWords }
            .joinToString(" ")

    /**
     * Built-in keywords per default group name (see DefaultData). Groups listed earlier win ties,
     * so "chilli powder" goes to Oils & Spices while "chilli" alone goes to Vegetables.
     */
    val builtIn: List<Pair<String, Set<String>>> = listOf(
        "Oils & Spices" to setOf(
            "oil", "sunflower", "mustard", "groundnut", "salt", "sugar", "jaggery", "gur", "masala", "powder", "turmeric",
            "haldi", "jeera", "cumin", "pepper", "spice", "spices", "elaichi", "cardamom", "clove", "cinnamon", "hing", "vinegar",
        ),
        "Vegetables" to setOf(
            "veg", "veggie", "veggies", "vegetable", "vegetables", "sabzi", "sabji", "tomato", "tomatoes", "onion", "onions",
            "potato", "potatoes", "aloo", "carrot", "carrots", "cabbage", "cauliflower", "gobi", "brinjal", "baingan", "beans",
            "peas", "capsicum", "chilli", "chillies", "chili", "ginger", "garlic", "cucumber", "spinach", "palak", "coriander",
            "dhaniya", "methi", "bhindi", "okra", "ladyfinger", "beetroot", "radish", "mooli", "pumpkin", "gourd", "lauki",
            "drumstick", "mushroom", "lettuce", "broccoli", "corn", "lemon", "lemons", "curry", "mint", "pudina",
        ),
        "Fruits" to setOf(
            "fruit", "fruits", "apple", "apples", "banana", "bananas", "mango", "mangoes", "orange", "oranges", "grapes",
            "papaya", "pineapple", "watermelon", "melon", "guava", "pomegranate", "kiwi", "pear", "strawberry", "chikoo",
            "sapota", "coconut", "dates", "berries",
        ),
        "Dairy & Eggs" to setOf(
            "milk", "curd", "dahi", "yogurt", "yoghurt", "paneer", "cheese", "butter", "ghee", "cream", "egg", "eggs",
            "buttermilk", "lassi", "khoa",
        ),
        "Grains & Pulses" to setOf(
            "rice", "atta", "wheat", "flour", "maida", "dal", "daal", "dhal", "toor", "tur", "moong", "chana", "rajma", "urad",
            "masoor", "besan", "poha", "rava", "suji", "sooji", "oats", "millet", "ragi", "jowar", "bajra", "pasta", "lentils",
        ),
        "Meat & Fish" to setOf("chicken", "mutton", "fish", "prawn", "prawns", "meat", "beef", "pork", "crab", "keema"),
        "Bakery" to setOf("bread", "bun", "buns", "cake", "biscuit", "biscuits", "cookie", "cookies", "rusk", "pav", "croissant", "muffin"),
        "Snacks & Beverages" to setOf(
            "chips", "namkeen", "chocolate", "juice", "tea", "chai", "coffee", "cola", "soda", "drink", "water", "snack",
            "snacks", "noodles", "maggi", "sauce", "ketchup", "jam", "icecream", "bhujia",
        ),
        "Household Supplies" to setOf(
            "detergent", "surf", "dishwash", "vim", "cleaner", "phenyl", "tissue", "foil", "garbage", "mop", "broom",
            "harpic", "lizol", "matchbox", "agarbatti", "bulb", "battery", "batteries", "napkin",
        ),
        "Personal Care" to setOf(
            "soap", "shampoo", "toothpaste", "toothbrush", "brush", "lotion", "deodorant", "razor", "sanitary", "pads",
            "diaper", "diapers", "conditioner", "facewash", "handwash", "sanitizer", "colgate", "dettol",
        ),
    )

    private const val FALLBACK_MAIN_GROUP = "Groceries"

    /**
     * @param learned item key -> category id, from previously approved bills.
     * @param categories all categories; only active expense categories are suggested.
     * @return category id, or null when nothing fits.
     */
    fun suggest(description: String, learned: Map<String, Long>, categories: List<Category>): Long? {
        val usable = categories.filter { it.active && it.kind == CategoryKind.EXPENSE }
        val usableIds = usable.mapTo(HashSet()) { it.id }
        val k = key(description)
        if (k.isEmpty()) return null

        learned[k]?.takeIf { it in usableIds }?.let { return it }

        val words = k.split(' ').toSet()
        learned.entries
            .filter { (ruleKey, id) -> id in usableIds && ruleKey.split(' ').all { it in words } }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }

        var best: String? = null
        var bestScore = 0
        for ((group, keywords) in builtIn) {
            val score = words.count { w -> w in keywords || (w.endsWith("s") && w.dropLast(1) in keywords) }
            if (score > bestScore) {
                best = group
                bestScore = score
            }
        }
        val groupName = best ?: return null
        val byName = usable.associateBy { it.name.lowercase() }
        return (byName[groupName.lowercase()] ?: byName[FALLBACK_MAIN_GROUP.lowercase()])?.id
    }
}

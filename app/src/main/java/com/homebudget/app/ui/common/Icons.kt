package com.homebudget.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EggAlt
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.homebudget.app.domain.model.AccountType

/** Stable icon keys stored in the database, mapped to vector icons. Unknown keys fall back to "misc". */
object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "groceries" to Icons.Filled.ShoppingCart,
        "vegetables" to Icons.Filled.Eco,
        "fruits" to Icons.Filled.Spa,
        "dairy" to Icons.Filled.EggAlt,
        "grains" to Icons.Filled.Grain,
        "spices" to Icons.Filled.Kitchen,
        "meat" to Icons.Filled.SetMeal,
        "bakery" to Icons.Filled.BakeryDining,
        "snacks" to Icons.Filled.Fastfood,
        "household" to Icons.Filled.CleaningServices,
        "personal_care" to Icons.Filled.Sanitizer,
        "home" to Icons.Filled.Home,
        "utilities" to Icons.Filled.Bolt,
        "transport" to Icons.Filled.DirectionsBus,
        "fuel" to Icons.Filled.LocalGasStation,
        "health" to Icons.Filled.LocalHospital,
        "education" to Icons.Filled.School,
        "shopping" to Icons.Filled.ShoppingBag,
        "dining" to Icons.Filled.Restaurant,
        "entertainment" to Icons.Filled.Movie,
        "insurance" to Icons.Filled.Security,
        "maintenance" to Icons.Filled.Build,
        "subscriptions" to Icons.Filled.Subscriptions,
        "salary" to Icons.Filled.Payments,
        "business" to Icons.Filled.Business,
        "freelance" to Icons.Filled.Laptop,
        "interest" to Icons.Filled.Savings,
        "investment" to Icons.AutoMirrored.Filled.TrendingUp,
        "gift" to Icons.Filled.CardGiftcard,
        "phone" to Icons.Filled.PhoneAndroid,
        "travel" to Icons.Filled.Flight,
        "fitness" to Icons.Filled.FitnessCenter,
        "kids" to Icons.Filled.ChildCare,
        "pets" to Icons.Filled.Pets,
        "cafe" to Icons.Filled.LocalCafe,
        "paid" to Icons.Filled.Paid,
        "work" to Icons.Filled.Work,
        "misc" to Icons.Filled.MoreHoriz,
    )

    fun of(key: String): ImageVector = all[key] ?: Icons.Filled.MoreHoriz

    val transfer: ImageVector = Icons.Filled.SwapHoriz

    /** Accessible, distinguishable palette offered when creating categories. */
    val palette: List<Long> = listOf(
        0xFF2E7D32, 0xFF00695C, 0xFF0277BD, 0xFF1565C0, 0xFF283593, 0xFF6A1B9A,
        0xFFAD1457, 0xFFC62828, 0xFFE65100, 0xFFEF6C00, 0xFFF9A825, 0xFF5D4037,
        0xFF795548, 0xFF546E7A,
    )
}

fun AccountType.label(): String = when (this) {
    AccountType.CASH -> "Cash"
    AccountType.SAVINGS -> "Savings account"
    AccountType.CURRENT -> "Current account"
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.INVESTMENT -> "Investment"
    AccountType.OTHER -> "Other"
}

fun AccountType.icon(): ImageVector = when (this) {
    AccountType.CASH -> Icons.Filled.Payments
    AccountType.SAVINGS -> Icons.Filled.Savings
    AccountType.CURRENT -> Icons.Filled.AccountBalance
    AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
    AccountType.INVESTMENT -> Icons.AutoMirrored.Filled.TrendingUp
    AccountType.OTHER -> Icons.Filled.AccountBalanceWallet
}

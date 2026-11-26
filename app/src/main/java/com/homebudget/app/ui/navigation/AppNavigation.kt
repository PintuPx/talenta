package com.homebudget.app.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.homebudget.app.FeatureFlags
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.ui.accounts.AccountsScreen
import com.homebudget.app.ui.adminreview.AdminReviewScreen
import com.homebudget.app.ui.bills.BillReviewScreen
import com.homebudget.app.ui.bills.BillsScreen
import com.homebudget.app.ui.budgets.BudgetsScreen
import com.homebudget.app.ui.recurring.RecurringScreen
import com.homebudget.app.ui.reports.ReportsScreen
import com.homebudget.app.ui.categories.CategoriesScreen
import com.homebudget.app.ui.common.UserMessages
import com.homebudget.app.ui.dashboard.DashboardScreen
import com.homebudget.app.ui.edit.EditTransactionScreen
import com.homebudget.app.ui.family.AppModeScreen
import com.homebudget.app.ui.familymembers.ExpenseFamilyMembersScreen
import com.homebudget.app.ui.family.FamilySetupScreen
import com.homebudget.app.ui.family.FamilyRecoveryScreen
import com.homebudget.app.ui.family.FamilyHandOverScreen
import com.homebudget.app.ui.family.FamilyUpdateBanner
import com.homebudget.app.ui.history.AdminHistoryScreen
import com.homebudget.app.ui.history.EntryHistoryScreen
import com.homebudget.app.ui.monthclose.MonthCloseScreen
import com.homebudget.app.ui.settings.PrivacyScreen
import com.homebudget.app.ui.settings.SettingsScreen
import com.homebudget.app.ui.transactions.TransactionListScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// Type-safe routes. 0 is used as "none" for optional ids.
@Serializable object HomeRoute
@Serializable data class TransactionsRoute(val accountId: Long = 0)
@Serializable object AccountsRoute
@Serializable object SettingsRoute
@Serializable object CategoriesRoute
@Serializable object PrivacyRoute
@Serializable object ReportsRoute
@Serializable object BudgetsRoute
@Serializable object RecurringRoute
@Serializable object AdminHistoryRoute
@Serializable object AdminReviewRoute
@Serializable object MonthCloseRoute
@Serializable object ModeSettingsRoute
@Serializable object FamilySetupRoute
@Serializable object FamilyRecoveryRoute
@Serializable object FamilyHandOverRoute
@Serializable object ExpenseFamilyRoute
@Serializable data class EntryHistoryRoute(val entityType: String, val entityUid: String)

/** Bill list. [startWithAdd] opens the "Add a bill" options immediately (dashboard "Scan bill"). */
@Serializable data class BillsRoute(val startWithAdd: Boolean = false)

/** Review / edit one bill. */
@Serializable data class BillRoute(val id: Long)

/**
 * Add/edit transaction. [id] > 0 edits; [duplicateOf] > 0 prefills a new transaction from another one.
 * [type] is the initial type for a new transaction.
 */
@Serializable data class EditTransactionRoute(
    val id: Long = 0,
    val type: String = TransactionType.EXPENSE.name,
    val duplicateOf: Long = 0,
    /** > 0 edits that repeating transaction (rule) instead of a transaction. */
    val recurringId: Long = 0,
    /** Opens a new transaction with "Every month" preselected. */
    val repeat: Boolean = false,
)

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

private val topLevels = listOf(
    TopLevel("Home", Icons.Filled.Home, HomeRoute, HomeRoute::class),
    TopLevel("Transactions", Icons.Filled.Receipt, TransactionsRoute(), TransactionsRoute::class),
    TopLevel("Reports", Icons.Filled.Insights, ReportsRoute, ReportsRoute::class),
    TopLevel("Accounts", Icons.Filled.AccountBalanceWallet, AccountsRoute, AccountsRoute::class),
    TopLevel("Settings", Icons.Filled.Settings, SettingsRoute, SettingsRoute::class),
)

@Composable
fun AppRoot(
    userMessages: UserMessages,
    privateMode: Boolean,
    onTurnOffPrivateMode: () -> Unit,
    onRequestPrivateMode: () -> Unit,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = topLevels.any { tl -> destination?.hierarchy?.any { it.hasRoute(tl.routeClass) } == true }

    LaunchedEffect(userMessages) {
        userMessages.messages.collect { msg ->
            launch {
                val result = snackbar.showSnackbar(
                    message = msg.text,
                    actionLabel = msg.actionLabel,
                    withDismissAction = msg.actionLabel == null,
                    duration = if (msg.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) msg.onAction?.invoke()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevels.forEach { tl ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tl.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tl.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tl.icon, contentDescription = null) },
                            label = { Text(tl.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (privateMode) PrivateModeBanner(onTurnOffPrivateMode)
            if (FeatureFlags.FAMILY_SHARING) FamilyUpdateBanner()
            NavHost(
                navController = nav,
                startDestination = HomeRoute,
                modifier = Modifier.weight(1f),
            ) {
            composable<HomeRoute> {
                DashboardScreen(
                    onAddTransaction = { type -> nav.navigate(EditTransactionRoute(type = type.name)) },
                    onOpenTransaction = { id -> nav.navigate(EditTransactionRoute(id = id)) },
                    onSeeAllTransactions = {
                        nav.navigate(TransactionsRoute()) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenAccounts = {
                        nav.navigate(AccountsRoute) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onScanBill = { nav.navigate(BillsRoute(startWithAdd = true)) },
                    onOpenBills = { nav.navigate(BillsRoute()) },
                    onOpenBudgets = { nav.navigate(BudgetsRoute) },
                    onOpenMonthClose = { nav.navigate(MonthCloseRoute) },
                )
            }
            composable<TransactionsRoute> {
                TransactionListScreen(
                    onAddTransaction = { nav.navigate(EditTransactionRoute()) },
                    onOpenTransaction = { id -> nav.navigate(EditTransactionRoute(id = id)) },
                    onBack = if (it.toRoute<TransactionsRoute>().accountId != 0L) {
                        { nav.popBackStack() }
                    } else null,
                )
            }
            composable<AccountsRoute> {
                AccountsScreen(onOpenAccount = { id -> nav.navigate(TransactionsRoute(accountId = id)) })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onManageCategories = { nav.navigate(CategoriesRoute) },
                    onOpenPrivacy = { nav.navigate(PrivacyRoute) },
                    onOpenBudgets = { nav.navigate(BudgetsRoute) },
                    onOpenRecurring = { nav.navigate(RecurringRoute) },
                    onOpenHistory = { nav.navigate(AdminHistoryRoute) },
                    onOpenAdminReview = { if (FeatureFlags.FAMILY_SHARING) nav.navigate(AdminReviewRoute) },
                    onManageFamily = { if (FeatureFlags.FAMILY_SHARING) nav.navigate(FamilySetupRoute) },
                    onManageExpenseMembers = { if (FeatureFlags.FAMILY_EXPENSES) nav.navigate(ExpenseFamilyRoute) },
                    onOpenMonthClose = { nav.navigate(MonthCloseRoute) },
                    onOpenMode = { if (FeatureFlags.FAMILY_SHARING) nav.navigate(ModeSettingsRoute) },
                    onRequestPrivateMode = onRequestPrivateMode,
                )
            }
            composable<AdminHistoryRoute> { AdminHistoryScreen(onBack = { nav.popBackStack() }) }
            if (FeatureFlags.FAMILY_SHARING) {
                composable<AdminReviewRoute> { AdminReviewScreen() }
                composable<ModeSettingsRoute> {
                    AppModeScreen(
                        firstLaunch = false,
                        onBack = { nav.popBackStack() },
                        onOpenFamilySetup = { nav.navigate(FamilySetupRoute) },
                    )
                }
                composable<FamilySetupRoute> {
                    FamilySetupScreen(
                        onBack = { nav.popBackStack() },
                        onOpenRecovery = { nav.navigate(FamilyRecoveryRoute) },
                        onOpenHandOver = { nav.navigate(FamilyHandOverRoute) },
                    )
                }
                composable<FamilyRecoveryRoute> { FamilyRecoveryScreen(onBack = { nav.popBackStack() }) }
                composable<FamilyHandOverRoute> { FamilyHandOverScreen(onBack = { nav.popBackStack() }) }
            }
            composable<MonthCloseRoute> { MonthCloseScreen() }
            if (FeatureFlags.FAMILY_EXPENSES) {
                composable<ExpenseFamilyRoute> { ExpenseFamilyMembersScreen(onBack = { nav.popBackStack() }) }
            }
            composable<EntryHistoryRoute> {
                val route = it.toRoute<EntryHistoryRoute>()
                EntryHistoryScreen(
                    entityType = route.entityType,
                    entityUid = route.entityUid,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ReportsRoute> { ReportsScreen(onOpenBudgets = { nav.navigate(BudgetsRoute) }) }
            composable<BudgetsRoute> { BudgetsScreen(onBack = { nav.popBackStack() }) }
            composable<RecurringRoute> {
                RecurringScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(EditTransactionRoute(repeat = true)) },
                    onOpen = { id -> nav.navigate(EditTransactionRoute(recurringId = id)) },
                    onScanBill = { nav.navigate(BillsRoute(startWithAdd = true)) },
                )
            }
            composable<CategoriesRoute> { CategoriesScreen(onBack = { nav.popBackStack() }) }
            composable<PrivacyRoute> { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable<EditTransactionRoute> {
                EditTransactionScreen(
                    onDone = { nav.popBackStack() },
                    onDuplicate = { id ->
                        nav.navigate(EditTransactionRoute(duplicateOf = id)) {
                            popUpTo<EditTransactionRoute> { inclusive = true }
                        }
                    },
                    onManageCategories = { nav.navigate(CategoriesRoute) },
                    onOpenBill = { id -> nav.navigate(BillRoute(id)) },
                    onOpenRecurring = { id -> nav.navigate(EditTransactionRoute(recurringId = id)) },
                    onOpenHistory = { uid -> nav.navigate(EntryHistoryRoute("transaction", uid)) },
                )
            }
            composable<BillsRoute> {
                BillsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenBill = { id -> nav.navigate(BillRoute(id)) },
                    startWithAdd = it.toRoute<BillsRoute>().startWithAdd,
                )
            }
            composable<BillRoute> {
                BillReviewScreen(
                    onDone = { nav.popBackStack() },
                    onManageCategories = { nav.navigate(CategoriesRoute) },
                )
            }
            }
        }
    }
}

@Composable
private fun PrivateModeBanner(onTurnOff: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Private mode is on. Only private-space data is shown.", Modifier.weight(1f))
            TextButton(onClick = onTurnOff) { Text("Turn off") }
        }
    }
}

package com.homebudget.app.ui.wallet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.homebudget.app.MainActivity
import com.homebudget.app.R
import com.homebudget.app.domain.budget.WalletLevel
import com.homebudget.app.domain.money.MoneyFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget showing the wallet and how much of this month's budget is left. */
class WalletWidget : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun walletSnapshotLoader(): WalletSnapshotLoader
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val snapshot = runCatching { loader(app).load() }.getOrNull()
                if (snapshot != null) render(app, appWidgetManager, appWidgetIds, snapshot)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private fun loader(context: Context): WalletSnapshotLoader =
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).walletSnapshotLoader()

        /** Redraws every placed widget with [snapshot]. Call from a background coroutine. */
        fun update(context: Context, snapshot: WalletSnapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WalletWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, snapshot)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, snapshot: WalletSnapshot) {
            val (title, subtitle) = texts(snapshot)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.wallet_widget).apply {
                setImageViewResource(R.id.widget_wallet, drawableFor(snapshot.level))
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                setContentDescription(R.id.widget_root, listOf(title, subtitle).filter { it.isNotEmpty() }.joinToString(". "))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }

        /** Main line and small line of the widget. */
        internal fun texts(snapshot: WalletSnapshot): Pair<String, String> {
            val budget = snapshot.budget ?: return "No budget yet — tap to set" to ""
            val fmt = MoneyFormatter(snapshot.currencyCode)
            val title = if (snapshot.spent > budget.limit) {
                "Over by ${fmt.format(snapshot.spent - budget.limit)}"
            } else {
                "${fmt.format(budget.limit - snapshot.spent)} left of ${fmt.format(budget.limit)}"
            }
            return title to (if (budget.estimated) "Estimated" else snapshot.level.spoken)
        }

        fun drawableFor(level: WalletLevel): Int = when (level) {
            WalletLevel.FULL -> R.drawable.ic_wallet_full
            WalletLevel.THREE_QUARTERS -> R.drawable.ic_wallet_3q
            WalletLevel.HALF -> R.drawable.ic_wallet_half
            WalletLevel.LOW -> R.drawable.ic_wallet_low
            WalletLevel.EMPTY -> R.drawable.ic_wallet_empty
            WalletLevel.OVER -> R.drawable.ic_wallet_over
        }
    }
}

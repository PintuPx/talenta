package com.homebudget.app.ui.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homebudget.app.domain.budget.WalletLevel
import com.homebudget.app.ui.common.LocalMoneyFormatter

/** The wallet picture for [level]. The words ([WalletLevel.spoken]) are shown next to it, so colour is never the only cue. */
@Composable
fun WalletImage(level: WalletLevel, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Image(
        painter = painterResource(WalletWidget.drawableFor(level)),
        contentDescription = level.spoken,
        modifier = modifier.size(size),
    )
}

/**
 * "Set this month's budget" prompt: shows the suggestion (if any) with "Use suggestion" and "Enter amount".
 * [onUse] and [onEnter] are null for read-only users, which hides the buttons.
 */
@Composable
fun SetBudgetPrompt(
    suggestion: Long?,
    months: Int,
    onUse: (() -> Unit)?,
    onEnter: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalMoneyFormatter.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Set this month's budget", style = MaterialTheme.typography.titleSmall)
        Text(
            if (suggestion != null) {
                "Suggested ${fmt.format(suggestion)} based on your last $months month${if (months == 1) "" else "s"}"
            } else {
                "Not enough history to suggest an amount yet. Enter an amount for this month."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggestion != null && onUse != null) {
                FilledTonalButton(onClick = onUse) { Text("Use suggestion") }
            }
            if (onEnter != null) {
                OutlinedButton(onClick = onEnter) { Text("Enter amount") }
            }
        }
    }
}

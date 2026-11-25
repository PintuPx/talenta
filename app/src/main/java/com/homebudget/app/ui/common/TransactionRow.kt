package com.homebudget.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homebudget.app.domain.finance.FinanceRules
import com.homebudget.app.domain.model.Account
import com.homebudget.app.domain.model.Category
import com.homebudget.app.domain.model.Transaction
import com.homebudget.app.domain.model.TransactionType
import com.homebudget.app.domain.model.pathLabel
import com.homebudget.app.ui.theme.AmountTextStyle
import com.homebudget.app.ui.theme.LocalFinanceColors

/** Display model for one transaction row; built from domain data so every list renders identically. */
data class TransactionRowModel(
    val transaction: Transaction,
    val title: String,
    val subtitle: String,
    val iconKey: String?,
    val colorArgb: Long?,
) {
    companion object {
        fun from(t: Transaction, accounts: Map<Long, Account>, categories: Map<Long, Category>): TransactionRowModel {
            val accountName = accounts[t.accountId]?.name ?: "Unknown account"
            return when (t.type) {
                TransactionType.TRANSFER -> TransactionRowModel(
                    transaction = t,
                    title = "Transfer",
                    subtitle = "$accountName → ${accounts[t.toAccountId]?.name ?: "Unknown account"}",
                    iconKey = null,
                    colorArgb = null,
                )
                else -> {
                    val cat = t.categoryId?.let { categories[it] }
                    val name = categories.pathLabel(t.categoryId) ?: "Uncategorised"
                    TransactionRowModel(
                        transaction = t,
                        title = if (t.isRefund) "Refund · $name" else name,
                        subtitle = listOf(t.payee, t.note, accountName).filter { it.isNotBlank() }.joinToString(" · "),
                        iconKey = cat?.iconKey,
                        colorArgb = cat?.colorArgb,
                    )
                }
            }
        }
    }
}

@Composable
fun TransactionRow(model: TransactionRowModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fmt = LocalMoneyFormatter.current
    val fc = LocalFinanceColors.current
    val t = model.transaction
    val effect = FinanceRules.netWorthEffect(t)
    val (amountText, amountColor, kindLabel) = when {
        t.type == TransactionType.TRANSFER -> Triple(fmt.format(t.amount), fc.transfer, "Transfer")
        effect > 0 -> Triple(fmt.formatSigned(effect), fc.income, if (t.isRefund) "Refund" else "Income")
        else -> Triple(fmt.formatSigned(effect), fc.expense, "Expense")
    }
    val icon = if (t.type == TransactionType.TRANSFER) CategoryIcons.transfer else CategoryIcons.of(model.iconKey ?: "misc")
    val color = model.colorArgb?.let { Color(it) } ?: fc.transfer
    val directionIcon = when {
        t.type == TransactionType.TRANSFER -> Icons.Filled.SwapHoriz
        effect > 0 -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.ArrowUpward
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open transaction", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$kindLabel, ${model.title}, $amountText, ${model.subtitle}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryBadge(icon, color, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(model.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.subtitle.isNotBlank()) {
                Text(
                    model.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { }) {
            Icon(directionIcon, contentDescription = null, tint = amountColor, modifier = Modifier.size(14.dp))
            Text(amountText, style = AmountTextStyle, color = amountColor)
        }
    }
}

# Accounting rules

Every financial number in the app comes from one Kotlin file,
[`FinanceRules.kt`](../app/src/main/java/com/homebudget/app/domain/finance/FinanceRules.kt).
The dashboard, transaction list and accounts screen all call it, and future reports must too. No screen
does its own money arithmetic.

## Money representation

- Amounts are stored as `Long` **hundredths** of the selected currency (paise for INR). Floating point is
  never used for stored values or arithmetic. (`Float` appears only as a 0–1 fraction that sizes progress
  bars.)
- Input is parsed with `BigDecimal` (`AmountParser`). More than 2 decimal places is rejected (0 for JPY).
  The maximum single amount is 1,000,000,000,000.00, which keeps sums far below `Long` overflow.
- The scale is fixed at 100 for every supported currency, so changing the display currency never
  rescales stored data. Changing the currency does **not** convert amounts: it only changes how they're
  displayed.
- INR is formatted with Indian grouping (₹1,23,45,678.90). Other currencies use the device locale.

## Transaction types

A transaction's `amount` is always **> 0**. The direction comes from the type, never from a sign.

| Type | Effect on its account(s) | Counted as |
|---|---|---|
| `INCOME` | +amount to `accountId` | Income |
| `EXPENSE` | −amount from `accountId` | Expense (gross) |
| `EXPENSE` with `isRefund = true` | +amount to `accountId` | Reduces expense (refund) |
| `TRANSFER` | −amount from `accountId`, +amount to `toAccountId` | **Nothing**: never income or expense |

A transfer is **one row** that holds both sides (`accountId` → `toAccountId`), so its two sides can never
get out of step. The repository enforces these rules before every write:

- The account exists, and the category exists and matches the type (INCOME ↔ income category,
  EXPENSE ↔ expense category).
- A transfer has a different destination account and no category or refund flag.
- An income can't be a refund.

## Investment accounts

- Add or withdraw investment money with a `TRANSFER`. Transfers between investment accounts do not
  count as new contributions; transfers to investments from other account types are shown as
  invested for the selected report period. No transfer is income or expense.
- A saved current-value record is a valuation, not a transaction. The latest record replaces that
  investment account's ledger balance in account and total-balance displays.
- Gain or loss is latest current value minus the account's ledger balance (net recorded
  contributions). Valuations never change income, expenses, refunds, budgets or category spending.

## Formulas

| Quantity | Formula |
|---|---|
| Account balance | `openingBalance + Σ income − Σ expenses + Σ refunds − Σ transfers out + Σ transfers in` (all dates); for an investment account with a saved valuation, use its latest current value instead |
| Total balance | Σ balances of accounts that are **not archived** and have **Include in total** on |
| Income (period) | Σ `INCOME` amounts in the period |
| Gross expense (period) | Σ non-refund `EXPENSE` amounts in the period |
| Refunds (period) | Σ refund `EXPENSE` amounts in the period |
| Net expense (period) | Gross expense − refunds (shown as "Expenses") |
| Net savings (period) | Income − net expense (can be negative) |
| Category spending | Σ expenses − Σ refunds for that category; categories ≤ 0 are left out of the breakdown. On the dashboard, a group's spending is rolled up into its main group (Vegetables → Groceries). |
| Budget used | Net expense in the budget's month (for the category including its groups, or all categories for the overall budget), floored at 0 |
| Budget remaining | Limit − used (negative = overspent) |
| Suggested budget | Net expense of each of the last 6 complete months before the current one (months with 0 ignored). 1-2 months: average, rounded half up. 3+ months: median (even count: average of the two middle values, rounded half up). Then rounded **up** to a multiple of 100 currency units. Integer math only; none when there is no history. Shown as "estimated" and never saved until the user accepts it. |
| Wallet level | `remaining = limit − spent`. Over (spent > limit), Empty (remaining = 0), Full (> 75% left), Three quarters (> 50%), Half (> 25%), otherwise Low. |

Periods are half-open: `[start, endExclusive)`. A month runs from 00:00 on day 1 up to, but not
including, 00:00 on the first day of the next month.

## Specific cases

- **Opening balance** sets an account's starting point. It isn't income, so it never shows up in monthly
  totals. For credit cards the user enters the amount owed, and it's stored as a negative opening
  balance.
- **Credit cards**: a purchase made with the card is an `EXPENSE` on the card account, which makes its
  balance more negative (more owed). Paying the card bill from a bank account is a `TRANSFER`
  bank → card. The expense is counted **once**, on the purchase date, and the payment is never counted
  as an expense. The add-transaction screen says this explicitly when the destination is a credit card.
- **Refunds** use the original expense category with "This is a refund" on. They add to the account
  balance and reduce net spending, the category breakdown and budget usage. They're never counted as
  income.
- **Transfers** never change the total balance unless one side is excluded from the total or archived.
  Moving money into an excluded account (for example, an investment you don't want in the total) lowers
  the included total. That's intentional.
- **Adjustments**: Phase 1 has no balance-adjustment transaction. To correct a balance, edit the
  account's opening balance, or record an income/expense in a category such as "Miscellaneous". A
  dedicated adjustment type is planned. It must not count as income or expense.
- **Groups and main groups**: a category is either a main group (e.g. Groceries) or a group inside one
  (e.g. Vegetables). There are only two levels, and a group always has the same kind (income or expense)
  as its main group. A transaction can use either. Filtering the transaction list by a main group
  includes its groups.
- **Bills**: approving a bill creates one `EXPENSE` per distinct category on the bill, for the sum of its
  included lines, dated at the bill's date and time, paid from the chosen account. Example: Veggie 40 +
  Tomato 30 (Vegetables) and Milk 56 (Dairy & Eggs) gives two expenses, 70 and 56, both counted under
  Groceries. Excluded lines are kept on the bill but not posted. Approving again *replaces* the bill's
  expenses (never duplicates), and deleting a bill deletes them. Nothing is posted until the user
  approves; OCR results and suggested groups are only pre-filled values. The bill total printed on the
  receipt is shown for comparison only: the amounts posted are always the sum of the lines.
- **Budgets** are per month. *Spent* = net expense in that month (expenses minus refunds, never below
  zero). The overall budget counts all expenses; a main-group budget also counts its groups; a group
  budget counts only that group. Status: OK below 80% of the limit, *warning* from 80% (integer check
  `spent × 100 ≥ limit × 80`), *over* when spent > limit. "Per day left" = remaining ÷ days left in the
  month, including today, rounded down. There is at most one budget per category per month. Copying
  budgets never overwrites a category that already has one.
- **Repeating transactions**: occurrence *n* is at start + n × interval (days, weeks, months or years),
  always counted from the start date, so a rule on the 31st posts on 28/29 Feb and on 31 Mar again.
  Due occurrences are added as normal transactions when the app opens, in one database transaction per
  rule with its counter, so an occurrence is never added twice. Paused rules add nothing; resuming skips
  the dates missed while paused. Editing the date or frequency restarts counting from the new date.
  Deleting a rule keeps the transactions it already added. A rule that no longer validates (for
  example, a deleted category) is paused instead of failing.
- **Reports** use the same `FinanceRules` functions as the dashboard: income, net expense (refunds
  subtract), savings = income − net expense, savings rate = savings × 100 ÷ income (whole percent,
  rounded toward zero; none when income is 0). Transfers are excluded everywhere.
- **Item prices** come from the included lines of approved bills, matched by normalised name (case,
  digits and units ignored: "Sugar 1kg" = "SUGAR"). They show the amount paid for the line, not a unit price.
- **Historical transactions** (before the current month) affect balances but not the current month's
  totals.

## Dates and time zones

`occurredAt` is a **floating local date-time**: the wall-clock date and time the user picked, stored as
`LocalDateTime.toEpochSecond(UTC)` purely as a fixed reference. It's never converted between time zones.
A purchase at 23:30 on 31 March stays in March even if the user later travels or the device time zone
changes. `createdAt` and `updatedAt` are real instants (epoch milliseconds), used only for auditing.

## Test coverage

These rules are covered by `FinanceRulesTest` and `BillSplitTest` (pure JVM), and `RepositoryTest` and
`BillRepositoryTest` (Room, Robolectric). The
tests cover transfers, credit-card payments, refunds (including refunds larger than spending), opening
balances, month, year and leap-year boundaries, excluded and archived accounts, and large amounts.

# Talenta: feature reference

One line per feature. **Where** is the path in the app. Status: ✅ built and tested · 🧪 built, device testing pending · ⏳ planned.

## Money basics
| Feature | Where | Notes |
|---|---|---|
| ✅ Income, expense, transfer | Home → Income / Expense / Transfer | Green "Save income", red "Save expense". Amounts stored exactly (minor units). |
| ✅ Refunds | Expense → "This is a refund" | Adds to the balance and reduces category spending. |
| ✅ Edit, duplicate, delete with Undo | Tap a transaction | |
| ✅ Search, filter, sort | Transactions tab | By text, type, account, category (a main group includes its groups), date. |
| ✅ Accounts and balances | Accounts tab | Cash, savings, current, credit card. Archive instead of delete. A card payment is a transfer. |
| ✅ Categories with groups | Settings → Categories | Two levels, e.g. Groceries › Vegetables. Reports roll groups up into their main group. |
| ✅ Currency, theme | Settings → General | 20 currencies; INR uses lakh/crore grouping. |

## Bills
| Feature | Where | Notes |
|---|---|---|
| ✅ Store a bill photo | Home → Scan bill | Camera app or photo picker; no app permissions. Saved privately, without location metadata. |
| ✅ Read bill text on the device | Automatic after the photo | ML Kit, Latin script. Merchant, date, time, total and lines. |
| ✅ Review page (bulk import) | Bill → Review | Each line: Item · Amount · Group · Main group. Edit, exclude or add lines. |
| ✅ Suggested groups | Review page | From your past approvals first, then a keyword list. |
| ✅ Split and approve | Review → Approve | One expense per group. Re-approving replaces them, with no duplicates. |

## Planning and insights (Phase 3)
| Feature | Where | Notes |
|---|---|---|
| ✅ Monthly budgets | Home → Budget card, Reports → Budgets, Settings → Budgets | Overall or per category or group. Warning at 80%, "over" at 100%. Shows a daily amount left. |
| ✅ Smart budget suggestion | Home → Budget card, Budgets | When this month has no overall budget, suggests one from the median of up to your last 6 full months of spending (average if only 1-2 months), rounded up to the next 100; "Use suggestion" saves it, "Enter amount" opens the editor. Until accepted the home card shows it as "Estimated from your spending". |
| 🧪 Wallet widget | Home screen → Widgets → Talenta | Wallet picture plus "left of" / "Over by" text for this month's budget ("Estimated" when not saved yet); tap opens the app. Refreshes about every 30 minutes and when the app goes to the background. |
| 🧪 Changing app icon | Launcher | The launcher icon is a wallet that empties as the monthly budget is used (full, three quarters, half, low, empty, over). Updated when the app goes to the background; some launchers take a moment to refresh. The icon is a placeholder until final branding. |
| ✅ Copy budgets | Budgets (empty month) | Copies the last month that had budgets. |
| ✅ Spending without a budget | Budgets | Lists the categories you spend in that have no limit. |
| ✅ Reports by month or year | Reports tab | Income, expenses, savings, savings rate, change from the previous period. |
| ✅ Trend chart | Reports | Income and expense bars for the last 6 months, or each month of the year. Tap a bar for its values. |
| ✅ Category breakdown with drill-down | Reports | Tap a main group to see its groups. |
| ✅ Item price history | Reports → Item prices | Line chart of an item (e.g. sugar) over purchase dates, from approved bills. Shows the line amount paid, not the unit price. |
| ✅ CSV export | Reports → ⬇ | This period or all transactions. Spreadsheet-safe: numbers plain, formulas neutralised. File writing via the system picker not yet tried by hand. |
| ✅ Repeating transactions | New transaction → Repeat, Settings → Repeating | Daily, weekly, every 2 weeks, monthly, every 3 months, yearly. Added automatically when the app opens; pause, resume or delete. |

## Data and privacy
| Feature | Where | Notes |
|---|---|---|
| ✅ Private crash report | Shown after a crash | Saved on the phone only. Contains the error, code locations and app, Android and phone versions, but no amounts, notes or other entered data. Share it, or report it on GitHub with the report filled in. |
| ✅ Report a bug | Settings → Privacy & about | Opens the project's GitHub issues page. |
| ✅ Backup and restore (JSON) | Settings → Backup | Format v3. Restore replaces all data and never duplicates. Older versions restore. Photos are not included. |
| ✅ Clear all data | Settings → Danger zone | |
| ✅ No internet, no tracking | Manifest | No network permission, no ads, analytics or crash reporting. Android cloud backup off. |

## Known issues
| Issue | Status |
|---|---|
| Scan bill crashed after choosing a photo (APK 0.1.0, reported from a phone test) | Fixed in 0.1.1. Verified on the emulator and confirmed on a real phone with 0.1.2. |

## To do (not in the app yet)
| Feature | Notes |
|---|---|
| ⏳ Personal or shared mode | First launch asks: just me (no sign-in) or a shared household. |
| ⏳ Users and roles | One Admin, Reviewers, Read-only members. Members are added by Gmail. |
| ⏳ Approval of Reviewer changes | A Reviewer's expenses and edits stay pending, and don't count in budgets, until the Admin approves them. Admin review page with history. |
| ⏳ Encrypted household sync | Through a Google Drive folder the Admin shares. A log in the style of Kafka: a partition per member, monthly files, offsets, a lag view for everyone. AES-256 encryption with a household passphrase. Design: [SYNC.md](SYNC.md), setup: [GOOGLE_SETUP.md](GOOGLE_SETUP.md). Partly written, not yet in the app. |
| ⏳ Inventory and shopping list | Household stock (e.g. sugar), low-stock badge, purchase requests (the Admin approves a Reviewer's), mark bought. Price trend chart when adding an item. |
| ⏳ Payee suggestions | New transaction → Payee: suggestions from your past payees as you type (most used and recent first), all from data on the phone. |
| ⏳ Place / location | An optional "Place" field on a transaction (e.g. "Big Bazaar, MG Road"), typed or picked from past places, so no permission is needed. Filling it from the phone's location would need the location permission; that's optional and decided later. |
| ⏳ Update check | Optional. Settings → "Check for updates" looks at the project's GitHub Releases and offers to download a newer APK, which installs over the old one and keeps your data (same signing key). Off by default, because it connects to GitHub; never automatic in personal mode unless you turn it on. |
| ⏳ Pick text from the bill | On the Review page, open the bill's recognised text and tap a line to use it as the shop name (or an item name or amount), for when reading picked the wrong line. |
| ⏳ Smarter learned groups | Today the latest approval wins (one group per item name). Planned: pick the group used most often, so one accidental choice doesn't override history. Optionally remember the shop too (Milk at a dairy shop → Dairy; at a supermarket → Household), and let you see and edit learned items. |
| ⏳ Card and PIN warning | Warns if a bill photo seems to show a card number, PIN, OTP or CVV; card numbers are masked in the saved text. |
| ⏳ Network lock and Connections screen | All connections go through one gate (blocked in personal mode) and are listed in Settings for the user to check. |
| ⏳ User guide with screenshots | For GitHub. |
| ⏳ Laptop view | (Upcoming, on hold.) View the app in a laptop browser on the same Wi-Fi, view-only, with a PIN. |
| ⏳ Google Play beta | After all features: store listing, privacy policy URL, closed testing (needs a Play Console account). |

## Rules of the numbers
See [ACCOUNTING_RULES.md](ACCOUNTING_RULES.md). Short version: transfers are never income or expense, refunds reduce spending, and budgets and reports use the same calculation as the dashboard.

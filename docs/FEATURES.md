# Talenta: feature reference

One line per feature. **Where** is the path in the app. Status: ✅ checked on a real phone · 🧪 unit-tested, device testing pending · 🚫 code present, hidden in 0.2.0 · ⏳ planned, not built. Released in = first version with the feature. Priority: P1 must, P2 should, P3 could. The release plan at the end is a plan, subject to change, not a promise.

## Money basics
| Feature | Where | Released in | Notes |
|---|---|---|---|
| ✅ Income, expense, transfer | Home → Income / Expense / Transfer | 0.1.0 | Green "Save income", red "Save expense". Amounts stored exactly (minor units). |
| ✅ Refunds | Expense → "This is a refund" | 0.1.0 | Adds to the balance and reduces category spending. |
| ✅ Edit, duplicate, delete with Undo | Tap a transaction | 0.1.0 | |
| ✅ Search, filter, sort | Transactions tab | 0.1.0 | By text, type, account, category (a main group includes its groups), date. |
| ✅ Accounts and balances | Accounts tab | 0.1.0 | Cash, savings, current, credit card. Archive instead of delete. A card payment is a transfer. |
| 🧪 Investment accounts | Accounts → Add account → Investment | 0.2.0 | Add and withdraw with transfers, not income or expenses. Save current-value records; the latest value is used for the account and total balance, with gain/loss shown against net contributions. Reports show deposits into investments for the selected month or year. Device testing pending. |
| 🧪 Family members and tags on transactions | Settings → Expense members and tags; new income/expense → For | 0.2.0 | Members and tags are stored locally; "All" is built in. Select people directly or use a tag to select its current members, then adjust the selection and review the exact `Long` equal split before saving. Removed members are hidden from the picker but remain on past entries and person reports. Tag reports use the tag's current active members. Transfers are not assigned. Family sharing and cross-phone sync are hidden in 0.2.0. |
| ✅ Categories with groups | Settings → Categories | 0.1.0 | Two levels, e.g. Groceries › Vegetables. Reports roll groups up into their main group. |
| ✅ Currency, theme | Settings → General | 0.1.0 | 20 currencies; INR uses lakh/crore grouping. |
| 🧪 Payee suggestions | New transaction → Payee | 0.2.0 | Past payees filtered as you type, ranked by use count and then most recent. Device testing pending. |
| 🧪 Pick text from a bill | Bill → Review → Bill text | 0.2.0 | The recognised OCR text is listed by line. Choose a line for the shop name, selected item name or amount; the existing review and approval flow remains in control. Device testing pending. |

## Bills
| Feature | Where | Released in | Notes |
|---|---|---|---|
| ✅ Store a bill photo | Home → Scan bill | 0.1.0 | Camera app or photo picker; no app permissions. Saved privately, without location metadata. |
| ✅ Read bill text on the device | Automatic after the photo | 0.1.0 | ML Kit, Latin script. Merchant, date, time, total and lines. |
| 🧪 Card and PIN warning | Bill → Review | 0.2.0 | Warns if recognised text contains a likely card number, PIN, OTP or CVV; card numbers in stored OCR text are masked. Device testing pending. |
| 🧪 Smarter learned groups | Bill → Review | 0.2.0 | Changing a learned item's group offers "Only this time", "Always" and "Cancel". "Always" can also change matching past bill entries and offers Undo. Closed-month entries are skipped for non-Admins; a local notice offers "Apply to them" or "Ignore", but it is only on the Admin Review screen, which is hidden in 0.2.0. Cross-phone delivery still needs sync. Device testing pending. |
| ✅ Review page (bulk import) | Bill → Review | 0.1.0 | Each line: Item · Amount · Group · Main group. Edit, exclude or add lines. |
| ✅ Suggested groups | Review page | 0.1.0 | From your past approvals first, then a keyword list. |
| ✅ Split and approve | Review → Approve | 0.1.0 | One expense per group. Re-approving replaces them, with no duplicates. |

## Planning and insights (Phase 3)
| Feature | Where | Released in | Notes |
|---|---|---|---|
| ✅ Monthly budgets | Home → Budget card, Reports → Budgets, Settings → Budgets | 0.1.0 | Overall or per category or group. Warning at 80%, "over" at 100%. Shows a daily amount left. |
| ✅ Smart budget suggestion | Home → Budget card, Budgets | 0.1.2 | When this month has no overall budget, suggests one from the median of up to your last 6 full months of spending (average if only 1-2 months), rounded up to the next 100; "Use suggestion" saves it, "Enter amount" opens the editor. Until accepted the home card shows it as "Estimated from your spending". |
| 🧪 Wallet widget | Home screen → Widgets → Talenta | 0.1.2 | Wallet picture plus "left of" / "Over by" text for this month's budget ("Estimated" when not saved yet); tap opens the app. Refreshes about every 30 minutes and when the app goes to the background. |
| 🧪 Changing app icon | Launcher | 0.1.2 | The launcher icon is a wallet that empties as the monthly budget is used (full, three quarters, half, low, empty, over). Updated when the app goes to the background; some launchers take a moment to refresh. The icon is a placeholder until final branding. |
| ✅ Copy budgets | Budgets (empty month) | 0.1.0 | Copies the last month that had budgets. |
| ✅ Spending without a budget | Budgets | 0.1.0 | Lists the categories you spend in that have no limit. |
| ✅ Reports by month or year | Reports tab | 0.1.0 | Income, expenses, savings, savings rate, change from the previous period. Reports can filter locally assigned transactions by person, tag, or unassigned (person/tag filter: 0.2.0). |
| ✅ Trend chart | Reports | 0.1.0 | Income and expense bars for the last 6 months, or each month of the year. Tap a bar for its values. |
| ✅ Category breakdown with drill-down | Reports | 0.1.0 | Tap a main group to see its groups. |
| ✅ Item price history | Reports → Item prices | 0.1.0 | Line chart of an item (e.g. sugar) over purchase dates, from approved bills. Shows the line amount paid, not the unit price. |
| ✅ CSV export | Reports → ⬇ | 0.1.0 | This period or all transactions. Spreadsheet-safe: numbers plain, formulas neutralised. File writing via the system picker not yet tried by hand. |
| ✅ Repeating transactions | New transaction → Repeat, Settings → Repeating | 0.1.0 | Daily, weekly, every 2 weeks, monthly, every 3 months, yearly. Added automatically when the app opens; pause, resume or delete. |

## Data and privacy
| Feature | Where | Released in | Notes |
|---|---|---|---|
| ✅ Private crash report | Shown after a crash | 0.1.2 | Saved on the phone only. Contains the error, code locations and app, Android and phone versions, but no amounts, notes or other entered data. Share it, or report it on GitHub with the report filled in. |
| ✅ Report a bug | Settings → Privacy & about | 0.1.2 | Opens the project's GitHub issues page. |
| 🧪 Backup and restore (JSON) | Settings → Backup | 0.1.0 | Frozen format v8 (0.2.0) includes entity history, month locks, family members, expense tags and transaction shares, investment values, changing-amount recurring rules, stable entity IDs and private-space flags. Older formats restore with new IDs and version-1 history. Family-mode restore is Admin-only; restored values become new history versions. Photos are not included. Device testing pending. |
| 🧪 Full change history | Transaction → History; Settings → History | 0.2.0 | Per-entry history screens read versioned changes, show field differences (readable values) and delete/restore markers. Archive retention and bulk undo are not connected. |
| 🧪 Month lock | Settings → Month close | 0.2.0 | Close/reopen is persistent and versioned. Home reminder to close the previous month appears only when that month has at least one transaction in the current space (not on a fresh install). The writer blocks non-Admin edits and deletes for closed months and skips locked entries in bulk changes. Admin skipped-month counts are stored locally; the "Apply to them / Ignore" notice is hidden (Admin Review screen not exposed). |
| 🧪 Private personal spending | Settings → Private mode | 0.2.0 | Private rows are filtered from shared lists and totals. Private mode asks for device authentication by default and uses FLAG_SECURE. |
| ✅ Clear all data | Settings → Danger zone | 0.1.0 | |
| ✅ No internet, no tracking | Manifest | 0.1.0 | No network permission, no ads, analytics or crash reporting. Android cloud backup off. |
| ✅ User guide (untested) | [docs/USER_GUIDE.md](USER_GUIDE.md) | 0.2.0 | Plain-English guide to install steps and current screens and features, with screenshots (sample data, taken before 0.2.0). |

## Known issues
| Issue | Status |
|---|---|
| Scan bill crashed after choosing a photo (APK 0.1.0, reported from a phone test) | Fixed in 0.1.1. Verified on the emulator and confirmed on a real phone with 0.1.2. |

## Release plan
Priorities and target releases below are defaults and can be adjusted by the owner. A plan, subject to change, not a promise. Releases after 0.2.0 are not built yet unless a row says the code is present. See [CHANGELOG](../CHANGELOG.md) for what actually shipped. Rows in each table are sorted by priority.

### 0.2.0, current release (GitHub pre-release)
Goal: ship the 🧪 features. Shipped features marked 0.2.0 above. Checked by unit tests (369), device tests (9) and an emulator upgrade from 0.1.2; not yet checked on a real phone.

Known limitations in 0.2.0:
- Private mode with device lock on Android 9-10 (see 0.2.1).
- Backups are not encrypted.

### 0.2.1, hardening (no new features, no schema change)
Goal: confirm 0.2.0 on a real phone and fix the build and release process.

| Feature | Priority | Target | Notes |
|---|---|---|---|
| ⏳ Real-phone check of the 0.2.0 🧪 features | P1 | 0.2.1 | Passing features can become ✅. |
| ⏳ Turn on GitHub CI | P1 | 0.2.1 | `.github/workflows/ci.yml` exists but has never run. Build and run unit tests on every push. Owner approval needed. |
| ⏳ One done-rule for every coding assistant | P1 | 0.2.1 | Build and run the unit tests before reporting done. |
| ⏳ Pre-push hook tests the pushed commit | P2 | 0.2.1 | Test the exact pushed commit, not the working folder. |
| ⏳ Private mode on Android 9-10 | P2 | 0.2.1 | Choose a fix: a weaker biometric class there, or hide the device-lock option. Owner decision. |
| ⏳ Clean up unused feature flags | P3 | 0.2.1 | 7 flags are never read: ARCHIVE, INVENTORY, PRICE_CHECKS, PLACE, UPDATE_CHECK, ENCRYPTED_BACKUP, CONNECTIONS. Remove them or wire them to their screens. |

### 0.3.0, money tools (one schema bump for fuel mileage: v6, backup v9; changing-amount bills already fit v5/v8)
Goal: vehicle, bill and price tools, all kept on the phone.

| Feature | Priority | Target | Notes |
|---|---|---|---|
| ⏳ Versioning and deprecation rules | P1 | 0.3.0 | Skip-upgrade guarantee: a user on any released version can install the newest release directly. This already works: each APK carries every Room migration and they chain automatically (e.g. 1→2→3→4→5, checked by `allMigrationsReachCurrentSchema`), and every older backup format restores. Rules: never delete a migration or drop a backup format; each release also tests upgrading from the last release of the previous major; optional direct shortcut migrations (Room takes the shortest path) for big jumps. Public rules: MAJOR.MINOR.PATCH plus a build number; release labels (Good to have / Must have / Critical); a version history in CHANGELOG with "What's new" and format versions; backward compatibility between app versions; deprecation (announce, keep working for at least 2 minor releases and 90 days, migrate, remove only in a major release). |
| ⏳ Fuel mileage per vehicle | P2 | 0.3.0 | Add vehicles once; mark any expense category as a fuel category (Fuel is marked by default), and expenses in it can record the vehicle, odometer reading, litres and "Full tank?". Mileage uses the full-to-full method (distance between two full fills ÷ litres added since the earlier one; partial top-ups are summed) and is plotted per vehicle over time in km/l only, with average km/l, cost per km and monthly fuel spend. Warns when an odometer reading is lower than the previous one; a missed fill-up shows that interval as skipped. No odometer reminders. Odometer in whole km and litres in millilitres (`Long`). Needs schema v6 and backup format v9 with migrations. Stays on the phone; no location used. |
| 🚫 Bills with changing amounts | P2 | 0.3.0 | Code present, but the amount-changes option and the paid list stay hidden (controls already built). The "Bills due" list (Settings → Repeating) is shown for fixed repeating transactions due in a closed month: choose **Add to due month** (Admin; reopens that month) or **Add to current month**. Schema v5 and backup v8 are frozen; Scan bill remains a separate bill flow and is not linked to recurring payment. |
| 🚫 Price per unit and price chart on transactions | P3 | 0.3.0 | Quantity/unit parsing and unit-price math are not exposed or integrated. |
| 🚫 Price checks | P3 | 0.3.0 | Entry points are not exposed; persistence and chart markers are not connected. |

### 0.4.0, organise and protect (offline only)
Goal: protect and tidy data without any network use.

| Feature | Priority | Target | Notes |
|---|---|---|---|
| 🚫 Encrypted backup | P1 | 0.4.0 | Password-protected file. Entry points are not exposed; normal JSON backup stays readable and unencrypted. Must be implemented and tested before any encryption claim. |
| 🚫 Archive old years | P2 | 0.4.0 | Entry points are not exposed; archive files and storage are not connected. |
| 🚫 Buying list and smart suggestions | P3 | 0.4.0 | No buying-list screen or stock/request storage is exposed. |
| 🚫 Place / location | P3 | 0.4.0 | Entry points are not exposed. Optional; needs a location permission decision, prefer manual entry. |

### 0.5.0, family sharing (needs owner approval: network use beyond the privacy rule)
Goal: share a household between phones.

Network note: the project's privacy rule currently allows only Google sign-in for identity. Syncing household data, even encrypted, needs the owner to change that rule first. The Drive code exists and is switched off.

| Feature | Priority | Target | Notes |
|---|---|---|---|
| ⏳ Before enabling: validate Admin Review custom values in the repository | P1 | 0.5.0 | Required before family sharing is switched on. |
| ⏳ Before enabling: add month-lock month checks | P1 | 0.5.0 | Required before family sharing is switched on. |
| ⏳ Before enabling: decide whether "Keep private" also hides earlier shared history | P1 | 0.5.0 | Owner decision. |
| 🚫 Family sharing and mode selection | P2 | 0.5.0 | Fresh installs and existing UNSET settings start in Individual mode without a choice screen. Family setup, roles, recovery and mode switching are not exposed. Private mode remains available and its filtering is unchanged. |
| 🚫 Users and roles | P2 | 0.5.0 | Local Admin, Reviewer and Read-only member management is not exposed. Cross-phone role changes and recovery require the sync engine. |
| 🚫 Approval of Reviewer changes | P2 | 0.5.0 | The Admin Review route and Settings row are not exposed. |
| 🚫 Encrypted household sync (Drive) | P2 | 0.5.0 | Sync and Drive entry points are not exposed. Needs owner approval for network use. Design: [SYNC.md](SYNC.md), setup: [GOOGLE_SETUP.md](GOOGLE_SETUP.md). |
| 🚫 Family update banner | P3 | 0.5.0 | The banner and its entry points are not exposed. |
| 🚫 Fast local sync | P3 | 0.5.0 | Sync entry points are not exposed. |
| 🚫 Network lock and Connections screen | P3 | 0.5.0 | Connections and network-gated activity are not exposed. |

### Before 1.0
Goal: decisions and store steps that wait on the owner.

| Feature | Priority | Target | Notes |
|---|---|---|---|
| ⏳ App name decision | P1 | Before 1.0 | Needed before the store listing. Rename on hold; package id `com.talenta.finance` never changes. |
| ⏳ Google Play beta | P1 | Before 1.0 | After all planned features: store listing, privacy policy URL, closed testing (12 testers for 14 days; needs a Play Console account). |
| 🚫 Update check | P3 | Before 1.0 | Entry points are not exposed. Needs a network request to GitHub, which is outside the current privacy rule. Owner decision; otherwise drop it. |
| ⏳ Laptop view | P3 | Before 1.0 | (On hold.) Use the app in a laptop browser on the same Wi-Fi, protected by a PIN: bulk import, bulk review, bulk edit, and graphs on a big screen. Data stays on the phone; the laptop only shows it and sends back edits. |

## Rules of the numbers
See [ACCOUNTING_RULES.md](ACCOUNTING_RULES.md). Short version: transfers are never income or expense, refunds reduce spending, and budgets and reports use the same calculation as the dashboard.

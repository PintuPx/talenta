# Project status (2026-11-24)

## Version 0.1.2: verified on the emulator and tested on a real phone
- **Unit tests:** 238 pass, 0 fail. **Lint:** 0 errors (2 old warnings). **Release APK** builds and is signed.
- **Emulator (release build, API 36):**
  - The app opens from the launcher (wallet icon alias).
  - Scan bill → Choose a photo → review screen: 9 lines, merchant and total read. No crash.
  - Home shows "Set this month's budget".
- **Fixed in 0.1.1:** Scan bill crashed in release builds only (code shrinking removed ML Kit parts). See [CHANGELOG](../CHANGELOG.md).
- **Real phone:** the owner tested 0.1.2 and reported it works, including the Scan bill fix.
- **Not verified yet:** the CI workflow on GitHub; the wallet widget and icon switching on more phones and launchers.
- **In the code but not used by the app yet:** roles and permissions, inventory and proposal models, sync encryption and file format (`domain/household`, `domain/inventory`, `domain/proposal`, `domain/sync`). Backup format v4 already reserves fields for inventory and requests.
- **Everything else planned:** see the To-do list in [FEATURES.md](FEATURES.md).

## Phase 3 (planning and insights): implemented and verified

### Built
- **Budgets:** monthly overall and category/group limits; warning at 80%, over at 100% (shown with an icon and text, not only color); amount per day left; copy from an earlier month; "spending without a budget"; Home budget card.
- **Reports:** month or year; income, expenses, savings, savings rate, change from the previous period, average monthly spend; income-vs-expense bar chart (tap a month); category breakdown with drill-down into groups.
- **Item price history:** line chart per item from approved bill lines (e.g. Sugar over purchase dates), with latest, lowest, highest, average and % change.
- **CSV export:** this period or all transactions; formula injection guarded; UTF-8 BOM so ₹ shows in Excel.
- **Repeating transactions:** Repeat option on a new transaction (daily to yearly); added automatically when the app opens; list with pause/resume; edit or delete a rule.
- Room schema **v3** (`recurring_rules`, `transactions.recurring_id`) with `MIGRATION_2_3`; backup format **v3** (older files still restore).
- Charts are drawn with Compose Canvas (no chart library). Every chart has a TalkBack description, and its numbers are also shown as text.

### Guardrails and project hygiene
- `GuardrailTest` fails the build if: a network or dangerous permission is added; a tracking/ads SDK appears; code logs to Logcat; `domain/` imports Android; money code uses floating point; a schema version lacks an exported file, a migration or a migration test; a new table isn't added to backup or clear-all; secrets or AI prompt files aren't git-ignored; a dependency has no license entry.
- Added `THIRD_PARTY_NOTICES.md` (licenses checked; ML Kit is proprietary), `CONTRIBUTING.md`, `SECURITY.md`, a CI workflow (`.github/workflows/ci.yml`, not yet run on GitHub), `docs/RELEASE.md` (Play checklist, listing draft, Data safety answers), `docs/privacy-policy.md`, `docs/FEATURES.md`.
- AI-assistant files (`CLAUDE.md`, `.claude/`) are git-ignored, as requested.

### Verification
- **JVM tests:** 207 pass, 0 fail (up from 94). New: recurrence, budget rules, reports, price history, CSV, budget and repeating repositories, backup v3, repeat-field UI, share labels, guardrails.
- **Instrumented (API 36 emulator):** 5 pass: migrations 1→2→3 (incl. new 2→3 with data), real ML Kit OCR, app smoke test.
- **Lint:** no errors. **Builds:** debug APK and minified release AAB (25.0 MB, unsigned). The merged release manifest still has no network permission and no telemetry service.
- **Manual emulator run:** monthly ₹15,000 rent posted immediately, next date Dec 23 shown; overall budget ₹20,000 → ₹5,000 left, ₹714.28/day (7 days left, correct); rent budget ₹14,000 → "Over by ₹1,000.00"; reports bar chart and drill-down; two manual bills "Sugar 1kg" ₹48 (24 Nov) and "SUGAR" ₹44 (15 Nov) grouped as one item with a line chart and +9%. No crashes; no app telemetry in logcat.
- **Fixed during this phase:** a tiny category share showed "0%" (now "<1%"); an invisible byte-order-mark character in source flagged by lint.

### Phase 3 limitations
- Repeating transactions are added when the app is opened (no background job or notifications, which would need extra permissions).
- Paused rules skip missed dates on resume; there's no end date for a rule yet.
- Item prices are line totals, not unit prices (quantity isn't read from bills).

## Phase 2 (bills): implemented and verified earlier

### Requested changes
- [x] **Save button colors.** Income Save is green and expense Save is red (finance colors, contrast checked: light 6.3:1 / 6.5:1, dark 7.7:1). The label also says "Save income" / "Save expense", so color isn't the only cue. Transfers keep the neutral color. Verified on the emulator.
- [x] **Store bills.** Take a photo (your camera app) or choose one (system photo picker). The app needs no camera or storage permission. Photos are re-saved upright, at most 2400 px, as JPEG without metadata, in the app's private storage.
- [x] **Read the bill text** on the device with ML Kit's bundled Latin-script model. Nothing is downloaded or uploaded.
- [x] **Bulk import page ("Review bill").** One card per line: **Item · Amount · Group · Main group**, for example *Veggie · ₹40 · Vegetables · Groceries*. Lines can be edited, excluded, removed or added. "Fill blanks" assigns one group to every unassigned line. Merchant, date, time and bill total are pre-filled.
- [x] **Automatic sections, approved by you.** Each line gets a suggested group, from:
  1. what you approved before for the same item (learned rules), then
  2. a built-in keyword list (veggie, tomato, milk, dal, bread, detergent…).

  Nothing is saved as an expense until you tap **Approve**, and every approval teaches the app.
- [x] **Split a bill.** The Split card shows each main group with its groups (Groceries → Vegetables, Dairy & Eggs…). Approving adds one expense per group. Approving again replaces those expenses (no duplicates). Deleting a bill deletes them too.
- [x] **Groups inside categories** (two levels). There are 10 default groups under Groceries. You can add groups and move categories in Settings → Categories. The dashboard rolls groups up into their main group, and filtering by a main group includes its groups.

### Supporting work
- [x] Room schema **v2** with `MIGRATION_1_2`. Existing data is kept, and grocery groups are added for existing users.
- [x] Backup format **v2** (groups, bills, bill lines, learned rules). Version 1 files still restore. **Bill photos are not included in backups**, and the export warning and Privacy page say so.
- [x] "Clear all data" also deletes bills and photos.
- [x] Privacy: ML Kit's `INTERNET`, `ACCESS_NETWORK_STATE` and usage-statistics uploader are removed from the manifest. The merged **release** manifest has no network permission.

### Verification
- **JVM tests:** 94 pass, 0 fail. New tests:
  - `ReceiptParserTest`: column layouts, tilted photos, ₹ and Indian grouping, dates and times, tax and payment rows.
  - `CategorySuggesterTest`.
  - `BillSplitTest`, including roll-up in reports and budgets.
  - `BillRepositoryTest`: approve, re-approve, validation, OCR with a fake recognizer, delete, backup round trip, v1 restore. Runs in Robolectric native-graphics mode.
  - A group-picker UI test.
- **Instrumented tests (API 36 emulator):** 4 pass.
  - `MigrationTest`: both tests, including 1→2 with data.
  - `OnDeviceOcrTest`: real ML Kit on a rendered receipt, with the app's no-internet manifest.
  - `AppSmokeTest`.
- **Lint:** no issues.
- **Manual run on the emulator:** scanned a receipt photo through the photo picker.
  - Read: 9 items, merchant, date, time and the ₹649 total. 8 items were suggested; "Widget clip" was unknown.
  - Approval was blocked until Widget clip had a group.
  - After approval: 6 expenses. Dashboard Groceries ₹618 + Shopping ₹25.
  - A second scan suggested all 9 items (learned rule).
  - Delete removed the bill's photo. No crashes and no telemetry activity in logcat.
- **Bugs found and fixed during this phase:**
  - Photo import always failed on a real device. The bounds-only decode returns null by design and was treated as an error. Robolectric's default graphics hid it; the tests now use native graphics and fail on the old code.
  - On tilted photos, prices didn't join their item names. Rows are now de-skewed using the OCR text angle.
  - Discounted items were dropped when an item cost more than the net total.
  - A misread quantity ("N") was kept in the item name.

### Bill reading: known limitations
- Latin-script text only (English and numbers). Devanagari or other scripts aren't recognised; type those items instead.
- Parsing is heuristic. Handwritten bills, very faded thermal paper, multi-column items or items split over two lines may need manual fixes, which is why every line is reviewed before approval.
- Discount and tax rows aren't turned into lines automatically. The page shows the difference from the bill total and offers "Add as a line" for positive differences.
- The APK grows by about 4 MB per CPU architecture for the bundled OCR model. A Play AAB delivers only the device's architecture.
- Bill photos aren't in backups; restoring on a new phone keeps the bills and their lines, without photos.

## Phase 1: implemented and verified earlier
Room schema; money as `Long` minor units with INR grouping; `FinanceRules`; transactions (add, edit, delete with Undo, duplicate); search, filters and sorting; accounts; categories; dashboard; settings; SAF backup and restore; privacy page. Details are in the git history and in [ARCHITECTURE.md](ARCHITECTURE.md).

## Not yet verified
- [ ] Physical device: real camera capture with your camera app, real receipts in different lighting, airplane mode, dark mode, large font, TalkBack.
- [ ] Release build at runtime. The minified AAB builds, but an unsigned bundle can't be installed for a runtime check.
- [ ] Backup export and import through the real SAF picker (logic is covered by tests).

## Pending: next phases
- [ ] Optional users and roles: first-launch mode choice (personal / shared), optional Google sign-in (identity only, data stays on the phone), one Admin, Reviewer, Read-only, Admin review of shared users' changes.
- [ ] Inventory and purchase requests, with an item price trend when adding an item.
- [ ] Google Play beta (after all features).
- [ ] Bill reading for Indian scripts (ML Kit Devanagari model), item quantity and unit price columns, multi-page bills.
- [ ] App lock, localization (strings are still inline English).
- [ ] Final branding (colors, font, icon: waiting on owner input; see `BRANDING.md`).
- [ ] Release signing, store listing, privacy policy URL, Play Console forms. The Data safety form must mention that photos are processed on the device only.

## Known limitations (unchanged from Phase 1)
- Single currency for the whole app; three-decimal currencies aren't supported.
- The database isn't encrypted by the app (Android sandbox plus device encryption).
- Backups are plain JSON; restore replaces all data.

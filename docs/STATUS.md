# Project status (2026-11-25)

Current: **0.2.0 release candidate**. Not published anywhere.

## 0.2.0 release candidate

### Verified today (2026-11-25, working tree)
| Check | Result |
|---|---|
| Unit tests (`gradlew testDebugUnitTest`) | 369 run, 0 failed, 0 skipped |
| Lint (`lintDebug`) | 0 issues |
| Builds | debug APK and androidTest APK build |

Device tests (API 36 emulator): 9 run, 0 failed, 0 skipped (AppSmokeTest, 7 MigrationTest incl. v3→v4 and v3→v5 with a repeating rule, OnDeviceOcrTest)

First run on the API 36 emulator (budget36): 9 tests, 7 passed, 2 failed.
- Passed: all 7 `MigrationTest` migrations, including v3→v4, v4→v5, the new v3→v4 and v3→v5 tests with a repeating rule, and `allMigrationsReachCurrentSchema`. `OnDeviceOcrTest` also passed.
- The 2 failures were test-side and are being fixed. No final number is reported yet.

### Data formats
- Database schema **v5**: migrations 1→2→3→4→5, schema JSON 1–5 exported.
- Backup format **v8**. Older formats restore (`BackupFormatRestoreTest` covers v3–v8).

### Fixed today
- Code did not compile (151 errors).
- Schema 4 JSON was missing.
- v3→v4 migration crashed for users with repeating transactions.
- Private bill lines leaked into shared history.
- Per-entry history ignored private space.
- Family shares were not updated after changing past bill groups (backup then failed restore).
- History snapshot crashed when a share's transaction row was missing.
- Repeating transactions stuck after a month close.
- `restore()` did not check the backup format version.
- Investment gain/loss moved into `FinanceRules`.
- History field labels are now sentence case.

### Not verified yet (pending)
- Release build signature: pending.
- Merged release manifest check: pending.
- Release APK smoke test: pending.
- Upgrade test from 0.1.2: pending.
- Physical device: camera capture, airplane mode, dark mode, large font, TalkBack, real SAF picker for backup (no runtime check of these yet).
- CI workflow on GitHub (not yet run).

### Known limitations
- Private mode with "Ask for device lock" cannot be turned on on Android 9–10.
- Hidden behind `FeatureFlags`: family sharing, sync, archive, buying list, price checks, changing-amount bills, place, update check, encrypted backup, connections. The sync code is present but switched off.
- Backups and CSV files are not encrypted. The database is not encrypted by the app.
- Single currency for the whole app; three-decimal currencies are not supported.
- Restore replaces all data. Bill photos are not in backups.

Feature list: [FEATURES.md](FEATURES.md). Release notes: [CHANGELOG](../CHANGELOG.md).

## Version 0.1.2: verified on the emulator and a real phone
- Unit tests 238 pass (at that release). Lint 0 errors. Release APK built and signed.
- Emulator (release build, API 36): app opens from the launcher; Scan bill → Choose a photo → review (9 lines, merchant and total read); Home shows "Set this month's budget".
- Fixed in 0.1.1: Scan bill crashed in release builds only (code shrinking removed ML Kit parts).
- Real phone: the owner tested 0.1.2 and reported it works, including the Scan bill fix.
- In the code but not used by the app then: roles, inventory, proposals, sync format (`domain/household`, `domain/inventory`, `domain/proposal`, `domain/sync`). Backup format v8 retains reserved fields for inventory and requests.

## Phase 3 (planning and insights)
- **Budgets:** monthly overall and category/group limits; warning at 80%, over at 100% (icon and text); amount per day left; copy from an earlier month; spending without a budget.
- **Reports:** month or year; income, expenses, savings, savings rate, change from previous period; income-vs-expense chart; category drill-down.
- **Item price history:** line chart per item from approved bill lines.
- **CSV export:** formula injection guarded; UTF-8 BOM.
- **Repeating transactions:** daily to yearly; added when the app opens; pause/resume; edit or delete.
- Charts use Compose Canvas (no chart library), with TalkBack descriptions and numbers as text.
- `GuardrailTest` fails the build on: network or dangerous permissions, tracking/ads SDKs, Logcat logging, Android imports in `domain/`, floating point in money code, a schema version without exported file/migration/migration test, a new table missing from backup or clear-all, secrets files not git-ignored, a dependency without a license entry.
- Limitations: repeating transactions run only when the app opens; paused rules skip missed dates on resume; no end date for a rule; item prices are line totals, not unit prices.
- Results at that phase: 207 JVM tests passed; 5 emulator tests passed; lint no errors; manual emulator run without crashes.

## Phase 2 (bills)
- Bills: photo from camera app or photo picker (no camera or storage permission); re-saved upright, max 2400 px, JPEG without metadata, in private storage.
- On-device text reading with ML Kit's bundled Latin-script model; nothing is downloaded or uploaded.
- Review page: one card per line (Item, Amount, Group, Main group); edit, exclude, remove, add; "Fill blanks".
- Suggested groups come from learned rules, then a built-in keyword list. Nothing is saved until Approve.
- Split a bill: one expense per group; re-approve replaces them; deleting the bill deletes them.
- Two-level categories (10 default groups under Groceries).
- Bill photos are not in backups. "Clear all data" deletes bills and photos.
- ML Kit's `INTERNET`, `ACCESS_NETWORK_STATE` and usage-statistics uploader are removed from the manifest.
- Limitations: Latin script only; heuristic parsing (handwriting, faded paper, multi-line items need manual fixes); discount/tax rows are not turned into lines automatically; APK grows about 4 MB per CPU architecture for the OCR model.

## Phase 1
Room schema; money as `Long` minor units with INR grouping; `FinanceRules`; transactions (add, edit, delete with Undo, duplicate); search, filters, sorting; accounts; categories; dashboard; settings; SAF backup and restore; privacy page. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Planned
- Family sharing between phones and sync (code present, switched off).
- Buying list and price checks.
- Bill reading for Indian scripts, quantity and unit price, multi-page bills.
- Localization (strings are inline English).
- Final branding (see `BRANDING.md`).
- Release signing, store listing, privacy policy URL, Play Console forms. Google Play beta only after all planned features.

# Architecture

The app is a single module (`:app`), offline-first, built with Kotlin, Jetpack Compose (Material 3),
MVVM, Hilt, Room, DataStore and Navigation Compose.

The code is split into three layers by package. They're not separate Gradle modules: for one developer,
one module with strict package boundaries is simpler to build and refactor, and it can be split later if
build times grow.

```
com.homebudget.app
├── domain/            Pure Kotlin. No Android imports. Fully unit-testable.
│   ├── model/         Account, Category, Transaction, BudgetLimit, enums
│   ├── money/         Money (minor units), AmountParser, MoneyFormatter
│   ├── finance/       FinanceRules: the ONLY place financial numbers are calculated
│   ├── validation/    TransactionValidator (form-level rules)
│   ├── bill/          ReceiptParser, CategorySuggester, BillSplit, BillTextRecognizer interface
│   └── backup/        Backup file format, codec, validator, BackupStorage interface
├── data/
│   ├── db/            Room entities, DAOs, AppDatabase, seed data, entity<->domain mappers
│   ├── repository/    Account/Category/Transaction repositories, data maintenance
│   ├── settings/      SettingsRepository (Preferences DataStore)
│   ├── bill/          BillRepository, BillImageStore (private photo files), MlKitBillTextRecognizer
│   └── backup/        BackupRepository, SafBackupStorage (Storage Access Framework)
├── di/                Hilt modules (database, DataStore, Clock, BackupStorage and recognizer bindings)
└── ui/
    ├── theme/         AppTheme, BrandTokens (placeholder brand values), FinanceColors
    ├── common/        Shared components, icons, TransactionRow, UserMessages (snackbar bus)
    ├── navigation/    Type-safe routes, bottom navigation, NavHost
    └── dashboard/ transactions/ edit/ accounts/ categories/ bills/ settings/   (Screen + ViewModel each)
```

## Dependency direction

```
ui (Compose screens, ViewModels) ──► data (repositories) ──► Room / DataStore / SAF
        │                                  │
        └──────────────► domain ◄──────────┘
```

- `domain` depends on nothing in this app.
- Repositories return domain models as `Flow`, so screens update automatically when the database
  changes.
- ViewModels combine repository flows, call `FinanceRules` to produce numbers, and expose one immutable
  `StateFlow<UiState>` per screen. The UiState includes `loading` and `error` fields.
- Repositories are concrete classes, not interfaces. With a single implementation, an interface would
  add files without making testing easier: tests use a real in-memory Room database through
  Robolectric. The deliberate interfaces are `BackupStorage`, because a second implementation (cloud)
  is planned, and `BillTextRecognizer`, so tests can run without ML Kit.

## Key decisions

| Decision | Reason |
|---|---|
| Money as `Long` hundredths | Exact arithmetic. See [ACCOUNTING_RULES.md](ACCOUNTING_RULES.md). |
| Transfer = one row with `accountId` and `toAccountId` | Both sides are a single logical operation and can't get out of step. |
| Calculations in Kotlin (`FinanceRules`), not SQL aggregates | One rule set, shared by every screen and unit-testable. Household data (thousands to tens of thousands of rows) easily fits in memory. If data grows a lot, add SQL aggregation behind the same API. |
| Floating local date-time | Transactions never jump between months when the time zone changes. |
| Foreign keys with `RESTRICT` | An account or category with history can't be deleted, only archived or deactivated. |
| Preferences in DataStore, not Room | Small key-value settings, needed before any database work. |
| No INTERNET permission, cloud backup disabled | Financial data can't leave the device except through a file export the user starts. |
| Hilt | About 10 injectable classes with a shared database and clock. Hilt removes hand-written factories and ViewModel wiring. |
| App-wide `UserMessages` snackbar bus | Lets a screen that closes (for example, after a delete) show "Deleted · Undo" on the screen below it. |
| `Clock` injected | Repository timestamps are deterministic in tests. |
| Bill OCR with ML Kit's **bundled** Latin model | Runs on the device with no model download. ML Kit merges `INTERNET`, `ACCESS_NETWORK_STATE` and a usage-statistics uploader (`TransportBackendDiscovery`) into the manifest; all three are removed with `tools:node="remove"` in `AndroidManifest.xml`. Verified in the merged release manifest and on an emulator. |
| Camera through `ACTION_IMAGE_CAPTURE`, gallery through the photo picker | No CAMERA or storage permission. The camera writes to a FileProvider URI in the cache; the photo is then re-encoded (upright, ≤ 2400 px, JPEG, no EXIF) into `files/bills/`. |
| Parsing in pure Kotlin (`ReceiptParser`) | OCR output differs per receipt; keeping the heuristics out of Android code makes them unit-testable with synthetic OCR lines. |
| One expense per category per bill | Keeps the transaction list readable while the bill keeps line-level detail. |

## Database schema (Room v2)

Exported schemas: [`app/schemas/com.homebudget.app.data.db.AppDatabase/`](../app/schemas) (`1.json`, `2.json`). Commit them.

| Table | Columns | Constraints and indexes |
|---|---|---|
| `accounts` | id PK, name, type (TEXT enum), opening_balance INT, include_in_total, archived, sort_order, created_at, updated_at | — |
| `categories` | id PK, name, kind (INCOME/EXPENSE), icon_key, color_argb, sort_order, active, created_at, updated_at, parent_id FK nullable (v2: main group of a group) | index (kind, sort_order), index parent_id; parent FK RESTRICT |
| `transactions` | id PK, type, amount INT (>0), account_id FK, to_account_id FK nullable, category_id FK nullable, is_refund, occurred_at INT (floating epoch seconds), note, payee, created_at, updated_at, bill_id FK nullable (v2) | FKs RESTRICT (bill_id: SET NULL); indexes on account_id, to_account_id, category_id, occurred_at, bill_id |
| `bills` (v2) | id PK, merchant, bill_date INT (floating), total INT nullable, account_id FK nullable, image_file (file name in files/bills), ocr_text, status (DRAFT/POSTED), created_at, updated_at | account FK RESTRICT; indexes account_id, bill_date |
| `bill_items` (v2) | id PK, bill_id FK, position, description, amount INT, category_id FK nullable, included | bill FK CASCADE, category FK RESTRICT |
| `item_rules` (v2) | key PK (normalised item name), category_id FK, use_count, updated_at | category FK CASCADE |
| `budgets` | id PK, month INT (yyyyMM), category_id FK nullable (null = overall), limit_amount INT | FK CASCADE; index (month, category_id) |

Default data (14 expense main groups, 10 groups inside Groceries, 6 income categories and a "Cash"
account) is inserted by `SeedDefaultsCallback` when the database is first created. "Clear all data"
restores the same defaults. `MIGRATION_1_2` adds the 10 grocery groups for existing users if they still
have an expense category named "Groceries".

## Schema migrations

1. Change the entities and bump `AppDatabase.VERSION`.
2. Write `MIGRATION_<old>_<new>` with explicit SQL and add it to `AppDatabase.MIGRATIONS`.
3. Build. The Room Gradle plugin exports the new schema JSON to `app/schemas/`. Commit it.
4. Extend `MigrationTest` (in androidTest) to create the old version, insert representative rows,
   migrate, and check that the data, and account balances in particular, are unchanged.

Destructive fallback migration is **not** enabled. Losing financial data on an upgrade is never
acceptable, so a missing migration crashes in testing rather than silently wiping data.

The backup file format has its own `formatVersion`, separate from the Room version. Newer backup files
are rejected with a clear message.

## Backup and restore

- **Export**: `BackupRepository.createSnapshot()` reads every table in one database transaction and
  produces JSON (`BackupCodec`). It's written through `BackupStorage`. The Storage Access Framework
  "Create document" picker means no storage permission is needed.
- **Import**: read the file (capped at 50 MB), decode it, run `BackupValidator` (ids, enums, amounts,
  references, transfer and refund rules, category kinds, dates, currency), and show a confirmation with
  counts. Only then does `restore()` **replace** all data in a single database transaction. If anything
  fails, the transaction rolls back. Because restore replaces rather than merges, importing the same file
  twice can't create duplicates.
- **Format version 2** adds `parentId` on categories, `billId` on transactions, and `bills`,
  `billItems` and `itemRules`. Version 1 files still restore (the new fields default to empty).
  **Bill photos are not in the backup file.** After a restore, a bill keeps its photo only if that file
  still exists on the device; photos not referenced by any restored bill are deleted.
- **Future cloud backup**: implement `BackupStorage` (for example, a Google Drive provider) and bind it
  in `BindingsModule`, or add a provider selector. Serialization, validation and restore stay unchanged.
  Cloud backup would need the INTERNET permission and an update to the privacy policy and Data safety
  form.

## Testing strategy

| Layer | Test type | Runs on |
|---|---|---|
| `domain` | Plain JUnit (`FinanceRulesTest`, `MoneyTest`, `TransactionValidatorTest`, `BackupValidationTest`, `ReceiptParserTest`, `CategorySuggesterTest`, `BillSplitTest`) | JVM |
| `data` | Room in-memory with Robolectric (`RepositoryTest`, `BillRepositoryTest` with a fake recognizer) | JVM |
| `ui` | Compose UI test of the stateless form with Robolectric (`EditTransactionFormTest`) | JVM |
| Migrations, OCR, end-to-end | `MigrationTest` (1→2), `OnDeviceOcrTest` (real ML Kit on a rendered receipt), `AppSmokeTest` (androidTest) | Device or emulator only |

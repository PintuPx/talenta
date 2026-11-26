# Contributing

Thanks for helping. Keep changes small and focused.

Please follow our [Code of Conduct](CODE_OF_CONDUCT.md) in all project spaces.

## Before you push
Install the push guardrail once per clone: `git config core.hooksPath scripts/git-hooks`. It blocks pushes that contain secrets, keystores, private notes, personal emails or phone numbers, commits without a GitHub noreply email, and code that fails the unit tests. Do not bypass it with `--no-verify`.

## Ground rules
- **Privacy first.** No network calls, analytics, ads or crash-reporting SDKs. Never log amounts, payees, notes or bill text.
- **Money is `Long` minor units.** No floating point in money math. All totals go through `FinanceRules` ([docs/ACCOUNTING_RULES.md](docs/ACCOUNTING_RULES.md)).
- **Database changes** need a version bump, an exported schema (`app/schemas`), an explicit `Migration` and a `MigrationTest`. Destructive migrations are not accepted.
- **New dependencies:** check the license first and add it to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **No secrets** in commits (keystores, passwords, API keys).

These rules are also enforced by `GuardrailTest`. If it fails, read its message: it says what to update.

## Before opening a pull request
```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
- Add tests for new logic: pure JVM for `domain/`, Robolectric for repositories, Compose UI tests for screens.
- Update [docs/FEATURES.md](docs/FEATURES.md) if behaviour changes.
- Describe what you changed and how you tested it.

## Reporting bugs
Open an issue with steps to reproduce, expected vs actual behaviour, and the Android version. **Don't include real financial data or bill photos.** For security issues, see [SECURITY.md](SECURITY.md).

## License of contributions

By submitting a contribution you agree that it is licensed under the [Apache License 2.0](LICENSE), as described in section 5 of that license.

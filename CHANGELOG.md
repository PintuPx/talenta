# Changelog

## 0.1.2
- Smart budget: when a month has no overall budget, the app suggests one from your past spending (median of up to the last 6 full months), or asks you to enter one.
- Wallet picture on the Home budget card; a home-screen wallet widget and a launcher icon that empties as the budget is used (device testing pending).
- Private crash report after a crash, with Share and "Report on GitHub" (no amounts or notes included).
- Settings → Report a bug.

## 0.1.1
- **Fixed:** Scan bill crashed right after choosing or taking a photo, in the installable (release) APK only. Code shrinking removed parts of the on-device text-recognition library, which are now kept. Reported by the first phone test; fix verified on the emulator and confirmed on a real phone (0.1.2).

## 0.1.0
- First installable build: transactions, accounts, categories, bill scanning, budgets, reports, CSV export, repeating transactions, backup and restore.

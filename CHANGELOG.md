# Changelog

## 0.2.0
**New**
- **Payee suggestions:** when you type a payee, your past payees appear as chips, most used first. Tap one to fill it in.
- **Pick text from the bill:** on the bill Review page, **Bill text** lists the recognised lines. Tap a line to use it as the shop name or the selected item's name or amount.
- **Card and PIN warning:** if a bill photo seems to show a card number, PIN, OTP or CVV, Review warns you. Card numbers are masked before the bill text is saved.
- **Learned groups:** changing a bill item's group asks **Only this time** or **Always**. With Always, **Change past entries too?** can update matching past entries, and you can Undo.
- **Expense members and tags:** Settings → Expense members and tags. Pick who an income or expense is **For**, with an equal split. Reports has a **Family shares** card.
- **Investment accounts:** add an Investment account and use **Update current value**. Reports shows **Invested this month / this year**.
- **Private mode:** Settings → Private mode shows only private data and blocks screenshots. By default it asks for your fingerprint or phone PIN to turn on (setting: Ask for device lock). It turns off when the app goes to the background.
- **History:** a History button on each transaction, and Settings → History for all changes.
- **Month close:** Settings → Month close closes or reopens the previous month. Home reminds you only when last month has entries.
- User guide with screenshots in `docs/USER_GUIDE.md`.
- For contributors: Code of Conduct, pull request template, and a push guardrail (`scripts/git-hooks/pre-push`) that blocks secrets, private files and personal data.

**Fixed**
- A repeating transaction whose next date fell in a closed month used to stop silently. It now appears under **Bills due**, where you can reopen the month or record it in the current month.
- After changing past bill groups, family shares now follow the new amount, so backups restore.
- A private bill's lines no longer appear in shared history.
- Restore now checks the backup format version itself.
- Upgrading from 0.1.2 no longer fails to open the app when you have repeating transactions (database migration fix).
- An entry's History no longer shows changes made while it was private when you are in the shared view.
- Saving family shares for an entry whose transaction was not stored yet no longer crashes.
- History field names now read naturally ("Occurred at" instead of "Occurred At").

**Upgrade notes**
- Database schema v3 to v5; backup format v4 to v8. Older backups still restore.
- A backup made by 0.2.0 cannot be restored by 0.1.x.
- Installing over 0.1.2 keeps your data if the APK is signed with the same key.

**Known limitations**
- On Android 9 and 10, Private mode cannot be turned on while **Ask for device lock** is on.
- Family sharing between phones and sync are not in this release.
- Backups and CSV files are not encrypted.

## 0.1.2
- Smart budget: when a month has no overall budget, the app suggests one from your past spending (median of up to the last 6 full months), or asks you to enter one.
- Wallet picture on the Home budget card; a home-screen wallet widget and a launcher icon that empties as the budget is used (device testing pending).
- Private crash report after a crash, with Share and "Report on GitHub" (no amounts or notes included).
- Settings → Report a bug.

## 0.1.1
- **Fixed:** Scan bill crashed right after choosing or taking a photo, in the installable (release) APK only. Code shrinking removed parts of the on-device text-recognition library, which are now kept. Reported by the first phone test; fix verified on the emulator and confirmed on a real phone (0.1.2).

## 0.1.0
- First installable build: transactions, accounts, categories, bill scanning, budgets, reports, CSV export, repeating transactions, backup and restore.

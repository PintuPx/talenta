# Security policy

## Reporting a vulnerability
Please **don't open a public issue** for security problems. Use GitHub's private vulnerability reporting ("Report a vulnerability" on the Security tab) for this repository. Include steps to reproduce and the affected version. You can expect an answer within 14 days.

## Scope and design
- All data is stored locally in the app's private storage. The app has no internet permission.
- The database relies on Android's app sandbox and device encryption. The app does **not** add its own database encryption.
- Backup (JSON) and export (CSV) files are not encrypted; users are warned before exporting.
- Release signing keys are never stored in this repository.

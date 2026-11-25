# Releasing on Google Play

Status: **not released.** The beta waits until all planned features are built (see [FEATURES.md](FEATURES.md)).

## One-time setup (owner only)
| Step | How | Done |
|---|---|---|
| Play Console developer account | play.google.com/console, one-time fee, identity verification | ☐ |
| Upload key | `keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`. Back it up offline; never commit it. | ☐ |
| `keystore.properties` | In the project root (git-ignored); see README → Release signing | ☐ |
| Play App Signing | Accept when creating the app (Google keeps the app signing key) | ☐ |
| Privacy policy URL | Host [privacy-policy.md](privacy-policy.md), e.g. with GitHub Pages | ☐ |
| Final branding | Icon (512×512), feature graphic (1024×500), colors; see [BRANDING.md](BRANDING.md) | ☐ |

## Every release
1. Bump `APP_VERSION_CODE` (+1) and `APP_VERSION_NAME` in `gradle.properties`.
2. `gradlew :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest`: all must pass.
3. `gradlew :app:bundleRelease`, which writes `app/build/outputs/bundle/release/app-release.aab` (signed when `keystore.properties` exists).
4. Install it on a device with bundletool and smoke test it. Minification can break things that debug builds don't.
5. Upload to **Internal testing** first, then **Closed testing**.
6. New personal developer accounts must run a closed test with at least 12 testers for 14 days before production access (Google Play policy; check the current rule in Play Console).

## Store listing draft
- **Name:** Talenta
- **Short description (≤ 80 chars):** Private household budget: expenses, bills, budgets. No ads, works offline.
- **Full description:** Track income, expenses, transfers and account balances. Scan shop bills: text is read on your phone and items are split into groups such as Groceries › Vegetables. Set monthly budgets, see reports and item price trends, and add repeating payments. No account, no ads, no internet access: your data stays on your device. Backups are files you control.
- **Category:** Finance · **Tags:** budget, expense tracker
- **Contact email:** (owner to provide)

## Data safety form (current version)
| Question | Answer |
|---|---|
| Does the app collect or share user data? | **No.** No data leaves the device; the app has no internet permission. |
| Data encrypted in transit? | Not applicable (no transmission). |
| Can users request deletion? | Yes: delete in the app (Clear all data) or uninstall. |
| Photos | Processed on the device only, not collected. |

When optional Google sign-in is added, revisit this form: the sign-in identity (name, email) would be handled by Google Sign-In on the device. Keep financial data off the network.

## Content rating and other forms
- Content rating questionnaire: finance utility, no user-generated content shared, no ads.
- Target audience: 18+. Ads: none. Government app: no. Financial features: personal budgeting only (no loans, no payments). In Play's financial-services declaration this is a budgeting tool, not a financial service.

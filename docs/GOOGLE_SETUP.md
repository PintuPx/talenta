# Google setup for shared-household sync

**Only needed for shared mode.** Personal mode works without any of this.

The person who builds and signs the APK does this once. It's free.

1. **Create a project**
   - Go to <https://console.cloud.google.com/> and choose **Select a project → New project**, e.g. "Talenta".
2. **Enable the Drive API**
   - Go to **APIs & Services → Library**, search for **Google Drive API** and tap **Enable**.
3. **Set up the consent screen** (Google Auth Platform)
   - App name: Talenta. User type: **External**. Use your email for support and developer contact.
   - Under **Data access**, add the scope `https://www.googleapis.com/auth/drive`.
   - Under **Audience**, add every family member's Gmail as a **test user** (up to 100).
4. **Create the Android OAuth clients** (APIs & Services → Credentials → Create credentials → OAuth client ID → Android)
   - Package name: `com.talenta.finance`
   - SHA-1 of the release signing certificate:
     ```
     keytool -list -v -keystore C:\Users\<you>\talenta-signing\talenta-release.jks -alias talenta
     ```
   - Create a second Android client with the **debug** SHA-1 if you run debug builds:
     ```
     keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android
     ```
   - The app needs no client ID or secret in its code. Google recognises it by the package name and certificate.

## What family members see
- When they sign in, Google shows **"Google hasn't verified this app"**. Tap **Advanced → Go to Talenta (unsafe)**. The warning appears because the app hasn't gone through Google's paid review for full Drive access.
- While the project's publishing status is **Testing**, Google may ask everyone to sign in again about every 7 days. The app then shows "Sign in to Google again". Switching the project to **In production** without verification keeps the warning screen but may avoid the weekly sign-in. Check Google's current rules.

## What is sent to Drive
- Only encrypted files, plus `household.json`, which holds no financial data. See [SYNC.md](SYNC.md).
- The household passphrase never leaves the phones.

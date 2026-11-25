# Applying your branding

**Done:** app name **Talenta**, application ID **com.talenta.finance**.
**Still placeholders:** colors, typography and the launcher icon. Here's where each item lives:

| Item | Where to change it |
|---|---|
| App name | `APP_NAME` in `gradle.properties` (generates the `app_name` string) |
| Application ID (Play package name) | `APP_ID` in `gradle.properties`. **It can never change after the first Play upload.** |
| Brand colors (light and dark) | `BrandTokens` in `app/src/main/java/com/homebudget/app/ui/theme/Theme.kt` |
| Window and launcher background colors | `app/src/main/res/values/colors.xml` |
| Font | Add the font files under `app/src/main/res/font/`, then set `BrandTokens.fontFamily` |
| Launcher icon | Replace `res/drawable/ic_launcher_foreground.xml` and the background color, or regenerate with Android Studio's Image Asset tool (adaptive icon plus monochrome layer) |
| Store graphics | See the release checklist (512×512 icon, 1024×500 feature graphic, screenshots) |

Semantic finance colors (`FinanceColors`: income, expense, transfer, warning) are separate from brand
colors. They have been chosen for contrast. If you change them, keep WCAG AA contrast against the
surface colors, in both light and dark themes.

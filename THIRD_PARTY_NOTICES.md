# Third-party notices

Talenta uses the libraries below. Every library in `gradle/libs.versions.toml` must be listed here (`GuardrailTest.everyDependencyHasALicenseEntry` enforces this). Check a library's license before adding it.

## Shipped in the app

| Library (Maven module) | License | Notes |
|---|---|---|
| `androidx.core:core-ktx` | Apache-2.0 | AndroidX |
| `androidx.activity:activity-compose` | Apache-2.0 | AndroidX |
| `androidx.lifecycle:lifecycle-runtime-compose` | Apache-2.0 | AndroidX |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | Apache-2.0 | AndroidX |
| `androidx.navigation:navigation-compose` | Apache-2.0 | AndroidX |
| `androidx.datastore:datastore-preferences` | Apache-2.0 | AndroidX |
| `androidx.exifinterface:exifinterface` | Apache-2.0 | AndroidX |
| `androidx.compose:compose-bom` | Apache-2.0 | Version alignment only |
| `androidx.compose.ui:ui` | Apache-2.0 | Jetpack Compose |
| `androidx.compose.ui:ui-graphics` | Apache-2.0 | Jetpack Compose |
| `androidx.compose.ui:ui-tooling-preview` | Apache-2.0 | Jetpack Compose |
| `androidx.compose.material3:material3` | Apache-2.0 | Material 3 components |
| `androidx.compose.material:material-icons-extended` | Apache-2.0 | Google Material Icons (the same icon family Angular Material uses) |
| `com.google.dagger:hilt-android` | Apache-2.0 | Dependency injection |
| `androidx.hilt:hilt-lifecycle-viewmodel-compose` | Apache-2.0 | AndroidX |
| `androidx.room:room-runtime` | Apache-2.0 | Database |
| `androidx.room:room-ktx` | Apache-2.0 | Database |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | Apache-2.0 | Backup files |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | |
| `androidx.biometric:biometric` | Apache-2.0 | AndroidX. Fingerprint / phone PIN prompt for Private mode. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | Apache-2.0 | Awaits Google Play services tasks (sign-in). |
| `com.google.android.gms:play-services-auth` | **Proprietary**: Android Software Development Kit License | Google sign-in and Drive authorization for family sync. Free to use but **not open source**. Used only when the user turns on family sync. |
| `com.google.mlkit:text-recognition` | **Proprietary**: ML Kit Terms of Service and Google APIs Terms of Service | Free to use but **not open source**. Bundled on-device model, so no download is needed. Its network permissions and usage-statistics service are removed in the manifest. Because of this dependency, the app can't be listed on F-Droid. |

## Build and test only (not shipped)

| Library (Maven module) | License |
|---|---|
| `androidx.compose.ui:ui-tooling` | Apache-2.0 (debug builds only) |
| `androidx.compose.ui:ui-test-junit4` | Apache-2.0 |
| `androidx.compose.ui:ui-test-manifest` | Apache-2.0 |
| `com.google.dagger:hilt-compiler` | Apache-2.0 |
| `androidx.room:room-compiler` | Apache-2.0 |
| `androidx.room:room-testing` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Apache-2.0 |
| `junit:junit` | EPL-1.0 |
| `androidx.test:core` | Apache-2.0 |
| `androidx.test.ext:junit` | Apache-2.0 |
| `androidx.test:runner` | Apache-2.0 |
| `org.robolectric:robolectric` | MIT |

Gradle plugins (Android Gradle Plugin, Kotlin, KSP, Hilt, Room) are Apache-2.0.

Transitive dependencies are pulled in by the libraries above. ML Kit brings Google Play services components, which are covered by Google's terms. Run `gradlew :app:dependencies --configuration releaseRuntimeClasspath` to list them.

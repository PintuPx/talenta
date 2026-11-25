// Top-level build file. Plugins are declared here (not applied) so every module resolves the same versions.
// AGP 9 provides built-in Kotlin support, so org.jetbrains.kotlin.android is intentionally NOT used.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

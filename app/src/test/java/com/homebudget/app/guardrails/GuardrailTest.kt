package com.homebudget.app.guardrails

import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.domain.backup.BackupFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Project guardrails as tests. These fail when new development breaks a project rule, so the
 * change is noticed and reviewed. When a failure is intentional (e.g. a new table), follow the
 * instructions in the failure message and then update the expected value here.
 */
class GuardrailTest {

    /** Gradle runs unit tests with the module (app/) as working directory; IDEs may use the root. */
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }.canonicalFile
    private val root: File = app.parentFile
    private val mainSrc = File(app, "src/main/java")

    private fun kotlinFiles(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    private fun codeLines(f: File) = f.readLines().mapIndexed { i, l -> i + 1 to l }
        .filterNot { (_, l) -> l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

    // ---------------------------------------------------------------- privacy

    @Test
    fun manifestRequestsNoNetworkOrDangerousPermissions() {
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"([^>]*)>""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val kept = declared.filterNot { (_, attrs) -> attrs.contains("""tools:node="remove"""") }.map { it.first }
        // Changing this list is a privacy decision: update the Privacy screen, privacy policy and the
        // Play Data safety answers first (e.g. INTERNET for optional Google sign-in).
        assertEquals("Permissions requested by the app", emptyList<String>(), kept)
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE").forEach { p ->
            assertTrue("$p must stay explicitly removed (libraries merge it in)", declared.any { it.first == p && it.second.contains("tools:node=\"remove\"") })
        }
        assertTrue("Android cloud backup must stay off", manifest.contains("""android:allowBackup="false""""))
    }

    @Test
    fun noTrackingAdsOrCrashReportingLibraries() {
        val catalog = File(root, "gradle/libs.versions.toml").readText().lowercase()
        val build = File(app, "build.gradle.kts").readText().lowercase()
        val banned = listOf(
            "firebase-analytics", "firebase-crashlytics", "crashlytics", "play-services-ads", "play-services-analytics",
            "appsflyer", "adjust", "mixpanel", "sentry", "amplitude", "facebook", "bugsnag", "segment", "onesignal",
            "google-services",
        )
        banned.forEach { b -> assertTrue("Banned SDK '$b' found", !catalog.contains(b) && !build.contains(b)) }
    }

    @Test
    fun productionCodeNeverLogs() {
        val pattern = Regex("""\b(Log\.[vdiwe]|Log\.wtf|println\(|print\(|Timber\.|printStackTrace\(|System\.out|System\.err)""")
        val hits = kotlinFiles(mainSrc).flatMap { f -> codeLines(f).filter { (_, l) -> pattern.containsMatchIn(l) }.map { (n, l) -> "${rel(f)}:$n ${l.trim()}" } }
        assertEquals("Logging can leak financial data; remove these", emptyList<String>(), hits)
    }

    // ---------------------------------------------------------------- architecture

    @Test
    fun domainLayerHasNoAndroidDependencies() {
        val domain = File(mainSrc, "com/homebudget/app/domain")
        val hits = kotlinFiles(domain).flatMap { f ->
            codeLines(f).filter { (_, l) -> l.startsWith("import android.") || l.startsWith("import androidx.") || l.startsWith("import com.homebudget.app.data") || l.startsWith("import com.homebudget.app.ui") }
                .map { (n, l) -> "${rel(f)}:$n $l" }
        }
        assertEquals("domain/ must stay pure Kotlin (testable on the JVM)", emptyList<String>(), hits)
    }

    @Test
    fun moneyAndFinanceCodeUsesNoFloatingPointForAmounts() {
        val dirs = listOf("domain/money", "domain/finance", "domain/budget", "domain/recurring", "domain/export")
        val pattern = Regex("""(:\s*(Double|Float)\b|\.toDouble\(\)|\.toFloat\(\)|\bDouble\(|\bFloat\()""")
        // fractionUsed / share values are for progress bars only, never stored or added up.
        val allowed = listOf("fractionUsed", "progress", "share")
        val hits = dirs.flatMap { d -> kotlinFiles(File(mainSrc, "com/homebudget/app/$d")) }.flatMap { f ->
            val all = f.readLines()
            codeLines(f).filter { (n, l) ->
                // The allowed name may be on the declaration line just above a getter.
                val context = l + " " + all.getOrElse(n - 2) { "" } + " " + all.getOrElse(n - 3) { "" }
                pattern.containsMatchIn(l) && allowed.none { context.contains(it) }
            }.map { (n, l) -> "${rel(f)}:$n ${l.trim()}" }
        }
        assertEquals("Money must be Long minor units", emptyList<String>(), hits)
    }

    // ---------------------------------------------------------------- data safety

    @Test
    fun everySchemaVersionIsExportedAndMigrated() {
        val schemas = File(app, "schemas/com.homebudget.app.data.db.AppDatabase")
        (1..AppDatabase.VERSION).forEach { v -> assertTrue("Missing exported schema $v.json (commit it)", File(schemas, "$v.json").exists()) }
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        (1 until AppDatabase.VERSION).forEach { v -> assertTrue("Missing Migration $v -> ${v + 1}", (v to v + 1) in steps) }
        val db = File(mainSrc, "com/homebudget/app/data/db/AppDatabase.kt").readText() + File(mainSrc, "com/homebudget/app/di/AppModule.kt").readText()
        assertTrue("Destructive migration must never be enabled", !db.contains("fallbackToDestructiveMigration"))
        val migrationTest = File(app, "src/androidTest/java/com/homebudget/app/MigrationTest.kt").readText()
        (1 until AppDatabase.VERSION).forEach { v ->
            assertTrue("MigrationTest needs a test for ${v} -> ${v + 1}", migrationTest.contains("MIGRATION_${v}_${v + 1}"))
        }
    }

    @Test
    fun tablesAreKnownToBackupAndClearAll() {
        val dbSource = File(mainSrc, "com/homebudget/app/data/db/AppDatabase.kt").readText()
        val entities = Regex("""(\w+Entity)::class""").findAll(dbSource.substringBefore("version =")).map { it.groupValues[1] }.toSortedSet()
        // A new table must be added to: BackupFile + BackupRepository (export and restore) + BackupValidator +
        // DataMaintenanceRepository.deleteAllRows + docs. Then add it here and bump AppDatabase.VERSION.
        assertEquals(
            sortedSetOf(
                "AccountEntity", "BillEntity", "BillItemEntity", "BudgetEntity", "CategoryEntity", "ItemRuleEntity",
                "RecurringRuleEntity", "TransactionEntity",
                "EntityVersionEntity", "MonthLockEntity", "MemberEntity", "SyncStateEntity",
                "FamilyPersonEntity", "FamilyTagEntity", "FamilyTagMemberEntity",
                "TransactionMemberEntity", "InvestmentValueEntity",
            ),
            entities,
        )
        assertEquals("Database version", 5, AppDatabase.VERSION)
        assertEquals("Backup format version", 8, BackupFile.CURRENT_FORMAT_VERSION)

        val clear = File(mainSrc, "com/homebudget/app/data/repository/Repositories.kt").readText().substringAfter("fun deleteAllRows")
        // Every DAO (one or more tables each) must be cleared by clear-all, sync_state included.
        listOf(
            "transactionDao", "recurringDao", "billDao", "itemRuleDao", "budgetDao", "accountDao", "categoryDao",
            "entityVersionDao", "monthLockDao", "memberDao", "syncStateDao", "familyAllocationDao", "investmentValueDao",
        ).forEach {
            assertTrue("deleteAllRows must clear $it", clear.contains("db.$it()"))
        }
        // familyAllocationDao owns four tables: people, tags, tag members, transaction members.
        listOf("deleteAllTransactionMembers", "deleteAllTagMembers", "deleteAllTags", "deleteAllPeople").forEach {
            assertTrue("deleteAllRows must call $it", clear.contains(".$it()"))
        }
        val backup = File(mainSrc, "com/homebudget/app/data/backup/BackupRepository.kt").readText()
        // sync_state (device-local sync cursors/notices) is intentionally not part of the backup file.
        listOf(
            "accountDao", "categoryDao", "transactionDao", "budgetDao", "billDao", "itemRuleDao", "recurringDao",
            "entityVersionDao", "monthLockDao", "memberDao", "familyAllocationDao", "investmentValueDao",
        ).forEach {
            assertTrue("BackupRepository must export and restore $it", Regex("""db\.$it\(\)""").findAll(backup).count() >= 2)
        }
    }

    // ---------------------------------------------------------------- repository hygiene

    @Test
    fun secretsAndAiPromptFilesAreGitIgnored() {
        val ignore = File(root, ".gitignore").readLines().map { it.trim() }
        listOf("*.jks", "*.keystore", "keystore.properties", "local.properties", "CLAUDE.md", ".claude/").forEach { p ->
            assertTrue(".gitignore must contain $p", ignore.any { it == p || it == "/$p" })
        }
    }

    @Test
    fun noSecretsInSource() {
        val patterns = listOf(
            Regex("""AIza[0-9A-Za-z_\-]{35}"""), // Google API key
            Regex("""-----BEGIN (RSA |EC )?PRIVATE KEY-----"""),
            Regex("""(?i)(storePassword|keyPassword)\s*=\s*"[^"]+""""),
        )
        val files = (kotlinFiles(File(app, "src")) + File(app, "build.gradle.kts") + File(root, "gradle.properties"))
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (patterns.any { it.containsMatchIn(l) }) "${rel(f)}:${i + 1}" else null } }
        assertEquals("Possible secrets in source", emptyList<String>(), hits)
    }

    @Test
    fun everyDependencyHasALicenseEntry() {
        val notices = File(root, "THIRD_PARTY_NOTICES.md")
        if (!notices.exists()) fail("THIRD_PARTY_NOTICES.md is missing")
        val text = notices.readText()
        val catalog = File(root, "gradle/libs.versions.toml").readText().substringAfter("[libraries]").substringBefore("[plugins]")
        val modules = Regex("""module\s*=\s*"([^"]+)"""").findAll(catalog).map { it.groupValues[1] }.toList()
        val missing = modules.filterNot { text.contains(it) }
        assertEquals("Check the license, then add these to THIRD_PARTY_NOTICES.md", emptyList<String>(), missing)
    }

    @Test
    fun projectIsApacheLicensed() {
        val license = File(root, "LICENSE")
        assertTrue("LICENSE file is missing", license.exists())
        val text = license.readText()
        assertTrue("LICENSE must be the unmodified Apache License 2.0 text", text.contains("Apache License") && text.contains("Version 2.0, January 2004") && text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue("README must state the license", File(root, "README.md").readText().contains("Apache License 2.0"))
    }
}

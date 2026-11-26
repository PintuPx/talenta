package com.homebudget.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Runs under Robolectric (API from robolectric.properties, >= minSdk 26) so DataStore takes the same
 * Files.move path as on devices. On a plain JVM Build.VERSION.SDK_INT is 0 and DataStore falls back to
 * File.renameTo, which on Windows cannot replace an existing file, so every second write failed.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = runBlocking {
        // Stop the DataStore (and release its file) before TemporaryFolder deletes the directory.
        dataStoreScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun repository(fileName: String) = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(temporaryFolder.root, fileName)
        },
    )

    @Test
    fun freshInstallUnsetModeInitializesToIndividual() = runTest {
        val settings = repository("fresh.preferences_pb")
        assertEquals(AppMode.UNSET, settings.settings.first().appMode)

        settings.initializeIndividualModeIfUnset()

        assertEquals(AppMode.PERSONAL, settings.settings.first().appMode)
        assertFalse(settings.settings.first().privateMode)
    }

    @Test
    fun upgradedUnsetModeInitializesToIndividualAndPreservesPrivateMode() = runTest {
        val settings = repository("upgrade.preferences_pb")
        settings.setPrivateMode(true)

        settings.initializeIndividualModeIfUnset()

        assertEquals(AppMode.PERSONAL, settings.settings.first().appMode)
        assertEquals(true, settings.settings.first().privateMode)
    }

    @Test
    fun alreadySelectedModeIsNotOverwritten() = runTest {
        val settings = repository("selected.preferences_pb")
        settings.setAppMode(AppMode.SHARED)
        settings.setPrivateMode(true)

        settings.initializeIndividualModeIfUnset()

        assertEquals(AppMode.SHARED, settings.settings.first().appMode)
        assertEquals(true, settings.settings.first().privateMode)
    }
}

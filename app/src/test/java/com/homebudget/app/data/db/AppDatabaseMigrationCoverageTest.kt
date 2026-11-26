package com.homebudget.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDatabaseMigrationCoverageTest {
    @Test
    fun registeredMigrationsCoverEverySchemaVersion() {
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5),
            AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }
}

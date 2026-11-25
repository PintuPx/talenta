package com.homebudget.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.homebudget.app.data.backup.SafBackupStorage
import com.homebudget.app.data.bill.MlKitBillTextRecognizer
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.SeedDefaultsCallback
import com.homebudget.app.domain.backup.BackupStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .addCallback(SeedDefaultsCallback())
            .build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** Swap this binding to plug in a different backup destination (e.g. a cloud provider). */
    @Binds
    abstract fun bindBackupStorage(impl: SafBackupStorage): BackupStorage

    /** On-device OCR for bill photos. */
    @Binds
    abstract fun bindBillTextRecognizer(impl: MlKitBillTextRecognizer): BillTextRecognizer
}

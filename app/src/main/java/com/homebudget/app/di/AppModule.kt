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
import com.homebudget.app.data.settings.CurrencyProvider
import com.homebudget.app.data.settings.SettingsCurrencyProvider
import com.homebudget.app.data.settings.SettingsSpaceProvider
import com.homebudget.app.data.repository.RoomHistoryRepository
import com.homebudget.app.data.repository.RoomAdminReviewRepository
import com.homebudget.app.data.repository.RoomFamilyRepository
import com.homebudget.app.data.repository.RoomExpenseFamilyRepository
import com.homebudget.app.data.repository.RoomMonthLockRepository
import com.homebudget.app.data.repository.RoomInvestmentRepository
import com.homebudget.app.data.repository.RoomIdentityProvider
import com.homebudget.app.data.repository.IdentityProvider
import com.homebudget.app.data.sync.AndroidKeyVault
import com.homebudget.app.data.sync.KeyVault
import com.homebudget.app.domain.history.HistoryRepository
import com.homebudget.app.domain.review.AdminReviewRepository
import com.homebudget.app.domain.family.FamilyRepository
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.monthclose.MonthLockRepository
import com.homebudget.app.domain.investment.InvestmentRepository
import com.homebudget.app.domain.backup.BackupStorage
import com.homebudget.app.data.settings.SpaceProvider
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
    @Binds
    abstract fun bindExpenseFamilyRepository(impl: RoomExpenseFamilyRepository): ExpenseFamilyRepository

    @Binds
    abstract fun bindInvestmentRepository(impl: RoomInvestmentRepository): InvestmentRepository

    @Binds
    abstract fun bindFamilyRepository(impl: RoomFamilyRepository): FamilyRepository

    @Binds
    abstract fun bindAdminReviewRepository(impl: RoomAdminReviewRepository): AdminReviewRepository

    @Binds
    abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds
    abstract fun bindMonthLockRepository(impl: RoomMonthLockRepository): MonthLockRepository

    @Binds
    abstract fun bindIdentityProvider(impl: RoomIdentityProvider): IdentityProvider

    @Binds
    abstract fun bindKeyVault(impl: AndroidKeyVault): KeyVault

    @Binds
    abstract fun bindSpaceProvider(impl: SettingsSpaceProvider): SpaceProvider

    @Binds
    abstract fun bindCurrencyProvider(impl: SettingsCurrencyProvider): CurrencyProvider

    /** Swap this binding to plug in a different backup destination (e.g. a cloud provider). */
    @Binds
    abstract fun bindBackupStorage(impl: SafBackupStorage): BackupStorage

    /** On-device OCR for bill photos. */
    @Binds
    abstract fun bindBillTextRecognizer(impl: MlKitBillTextRecognizer): BillTextRecognizer
}

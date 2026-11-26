package com.homebudget.app.data.settings

import com.homebudget.app.domain.money.MoneyFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The user's selected display currency, for code outside Compose that formats money for display. */
interface CurrencyProvider {
    val currencyCode: Flow<String>
}

/** The app default currency; used where no settings are available (e.g. tests). */
object DefaultCurrencyProvider : CurrencyProvider {
    override val currencyCode: Flow<String> = flowOf(MoneyFormatter.DEFAULT_CURRENCY)
}

@Singleton
class SettingsCurrencyProvider @Inject constructor(
    settingsRepository: SettingsRepository,
) : CurrencyProvider {
    override val currencyCode: Flow<String> = settingsRepository.settings
        .map { it.currencyCode }
        .distinctUntilChanged()
}

package com.homebudget.app.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

enum class Space {
    SHARED,
    PRIVATE,
}

interface SpaceProvider {
    val current: StateFlow<Space>
}

object SharedSpaceProvider : SpaceProvider {
    override val current: StateFlow<Space> = MutableStateFlow(Space.SHARED)
}

@Singleton
class SettingsSpaceProvider @Inject constructor(
    settingsRepository: SettingsRepository,
) : SpaceProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val current: StateFlow<Space> = settingsRepository.settings
        .map { if (it.privateMode) Space.PRIVATE else Space.SHARED }
        .stateIn(scope, SharingStarted.Eagerly, Space.SHARED)
}

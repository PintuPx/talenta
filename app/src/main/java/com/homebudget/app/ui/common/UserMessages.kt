package com.homebudget.app.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A snackbar message, optionally with an action such as Undo. */
data class UserMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (suspend () -> Unit)? = null,
)

/**
 * App-wide message channel shown by the root Scaffold's SnackbarHost. Lets a screen that is being
 * closed (e.g. after deleting a transaction) still show "Deleted — Undo" on the screen below it.
 */
@Singleton
class UserMessages @Inject constructor() {
    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    fun show(message: UserMessage) {
        _messages.tryEmit(message)
    }

    fun show(text: String) = show(UserMessage(text))
}

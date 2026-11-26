package com.homebudget.app.data.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.homebudget.app.domain.sync.SyncException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when asking Google for Drive access. */
sealed interface DriveAuthResult {
    data class Granted(val accessToken: String) : DriveAuthResult
    /** The user must choose an account / consent: launch [intent] and pass the result to [GoogleDriveAuth.finish]. */
    data class NeedsUser(val intent: PendingIntent) : DriveAuthResult
}

/**
 * Google sign-in for Drive access, using Google Play services' Authorization API. Google keeps and
 * refreshes the token; the app stores no password or token. Requires an Android OAuth client in the
 * developer's Google Cloud project for this app's package name and signing certificate (see
 * docs/GOOGLE_SETUP.md).
 */
@Singleton
// REVIEW: handles OAuth credentials and scoped Drive access.
class GoogleDriveAuth @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        /** Full Drive access is needed to read files other members created in the shared folder (see docs/SYNC.md). */
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }

    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()

    suspend fun authorize(): DriveAuthResult {
        val result: AuthorizationResult = try {
            Identity.getAuthorizationClient(context).authorize(request).await()
        } catch (e: ApiException) {
            throw SyncException("Google sign-in is not available (${e.statusCode}). Check Google Play services and the app's Google Cloud setup.")
        }
        val pending = result.pendingIntent
        return when {
            result.hasResolution() && pending != null -> DriveAuthResult.NeedsUser(pending)
            result.accessToken != null -> DriveAuthResult.Granted(result.accessToken!!)
            else -> throw SyncException("Google did not grant Drive access")
        }
    }

    /** Completes [DriveAuthResult.NeedsUser] with the activity result data. */
    fun finish(data: Intent?): String {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw SyncException("Google sign-in was cancelled")
        }
        return result.accessToken ?: throw SyncException("Google did not grant Drive access")
    }

    /** A token without user interaction (for background sync). Throws if the user must sign in again. */
    suspend fun silentToken(): String = when (val r = authorize()) {
        is DriveAuthResult.Granted -> r.accessToken
        is DriveAuthResult.NeedsUser -> throw SyncException("Sign in to Google again (Settings → Household)")
    }
}

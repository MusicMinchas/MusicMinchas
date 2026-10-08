package com.kike.musicplayer.data.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.kike.musicplayer.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Google OAuth 2.0 for Drive through Google Identity Services' AuthorizationClient (the replacement
 * for the deprecated GoogleSignIn API). The OAuth client is matched by package name + signing
 * certificate in Google Cloud Console, so no client ID or secret lives in the app.
 */
class DriveAuth(context: Context, private val settings: Settings) {
    private val client = Identity.getAuthorizationClient(context)

    private val _account = MutableStateFlow(if (settings.driveConnected) settings.driveAccount ?: "" else null)
    /** Null when Drive is not connected; otherwise the account e-mail (blank if unknown). */
    val account: StateFlow<String?> = _account.asStateFlow()

    @Volatile private var token: String? = null

    private val request: AuthorizationRequest = AuthorizationRequest.Builder()
        .setRequestedScopes(SCOPES)
        .build()

    /** First step of connecting. If the result has a resolution, the UI must launch its PendingIntent. */
    suspend fun authorize(): AuthorizationResult = client.authorize(request).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    /** Stores a successful authorization. Returns false if it did not include a token. */
    fun onAuthorized(result: AuthorizationResult): Boolean {
        val accessToken = result.accessToken ?: return false
        token = accessToken
        // Only used for display and for revoking access; the deprecated accessor is the only way to get it.
        @Suppress("DEPRECATION")
        val email = result.toGoogleSignInAccount()?.email
        settings.driveConnected = true
        settings.driveAccount = email
        _account.value = email ?: ""
        return true
    }

    /**
     * A valid access token, blocking. For background threads only (OkHttp, metadata extraction).
     * Play services refreshes it silently because the user already granted the scope.
     */
    fun tokenBlocking(forceRefresh: Boolean = false): String? {
        if (!settings.driveConnected) return null
        synchronized(this) {
            val current = token
            if (current != null && !forceRefresh) return current
            return try {
                if (forceRefresh && current != null) {
                    Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(current).build()), 10, TimeUnit.SECONDS)
                }
                val result = Tasks.await(client.authorize(request), 20, TimeUnit.SECONDS)
                if (result.hasResolution()) {
                    Log.w(TAG, "Drive access needs user interaction again")
                    null
                } else {
                    result.accessToken.also { token = it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh failed", e)
                null
            }
        }
    }

    suspend fun disconnect() {
        val email = settings.driveAccount
        token?.let { t -> runCatching { client.clearToken(ClearTokenRequest.builder().setToken(t).build()).await() } }
        if (!email.isNullOrBlank()) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, "com.google"))
                        .setScopes(SCOPES)
                        .build()
                ).await()
            }.onFailure { Log.w(TAG, "Revoke failed", it) }
        }
        token = null
        settings.driveConnected = false
        settings.driveAccount = null
        _account.value = null
    }

    companion object {
        private const val TAG = "DriveAuth"
        private val SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive.readonly"), Scope("email"))
    }
}

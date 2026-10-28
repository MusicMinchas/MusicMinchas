package com.kike.musicplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kike.musicplayer.R
import com.kike.musicplayer.data.subsonic.SubsonicException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

object NetworkUtils {
    /**
     * Checks if the device has an active, validated internet connection.
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Observes validated internet connectivity status as a cold Flow.
     */
    fun observeOnline(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val online = caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(online)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(online)
            }

            override fun onLost(network: Network) {
                trySend(isOnline(context))
            }
        }
        trySend(isOnline(context))
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    /**
     * Formats network and API exceptions into clear, localized user-facing strings.
     */
    fun describeError(context: Context, e: Throwable): String = when (e) {
        is UnknownHostException -> context.getString(R.string.network_error_server_unreachable)
        is SocketTimeoutException -> context.getString(R.string.network_error_timeout)
        is ConnectException -> context.getString(R.string.network_error_server_unreachable)
        is SSLException -> context.getString(R.string.network_error_ssl)
        is UnknownServiceException -> e.message ?: context.getString(R.string.network_error_generic)
        is SubsonicException -> when (e.code) {
            SubsonicException.WRONG_CREDENTIALS -> context.getString(R.string.server_error_wrong_credentials)
            SubsonicException.NOT_AUTHORIZED -> context.getString(R.string.server_error_not_authorized)
            else -> e.message ?: context.getString(R.string.server_error_generic, e.code)
        }
        is IOException -> e.message ?: context.getString(R.string.network_error_generic)
        else -> e.message ?: e.javaClass.simpleName
    }
}

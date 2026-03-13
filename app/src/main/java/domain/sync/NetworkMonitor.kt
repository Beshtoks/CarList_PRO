package com.carlist.pro.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ((Boolean) -> Unit)? = null
    private var isStarted = false
    private var lastKnownOnline: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            dispatchCurrentState()
        }

        override fun onLost(network: Network) {
            dispatchCurrentState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            dispatchCurrentState()
        }

        override fun onUnavailable() {
            dispatchCurrentState()
        }
    }

    fun start(onChanged: (Boolean) -> Unit) {
        callback = onChanged

        if (isStarted) {
            dispatchCurrentState(force = true)
            return
        }

        isStarted = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
        } catch (_: Throwable) {
            // Если callback зарегистрировать не удалось, всё равно отдадим текущее состояние.
        }

        dispatchCurrentState(force = true)
    }

    fun stop() {
        if (!isStarted) {
            callback = null
            lastKnownOnline = null
            return
        }

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
            // Уже снят или не был зарегистрирован.
        }

        isStarted = false
        callback = null
        lastKnownOnline = null
    }

    fun isCurrentlyOnline(): Boolean {
        return currentOnlineState()
    }

    private fun dispatchCurrentState(force: Boolean = false) {
        val online = currentOnlineState()

        if (!force && lastKnownOnline == online) return

        lastKnownOnline = online
        callback?.invoke(online)
    }

    private fun currentOnlineState(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            info != null && info.isConnected
        }
    }
}

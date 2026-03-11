package com.carlist.pro.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private var isStarted = false
    private var listener: ((Boolean) -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            listener?.invoke(isCurrentlyOnline())
        }

        override fun onLost(network: Network) {
            listener?.invoke(isCurrentlyOnline())
        }

        override fun onUnavailable() {
            listener?.invoke(false)
        }
    }

    fun start(listener: (Boolean) -> Unit) {
        this.listener = listener

        if (isStarted) {
            listener.invoke(isCurrentlyOnline())
            return
        }

        isStarted = true

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }

        listener.invoke(isCurrentlyOnline())
    }

    fun stop() {
        if (isStarted) {
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }

        isStarted = false
        listener = null
    }

    fun isCurrentlyOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
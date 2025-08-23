package com.example.android_app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ConnectivityManager.NetworkCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object Net {

    /** Chequeo rápido: ¿hay Internet validado? */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Observa cambios de conectividad como Flow<Boolean> (true=online, false=offline). */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Emitir estado inicial
        trySend(isOnline(context))

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                trySend(true)
            }
            override fun onLost(network: android.net.Network) {
                trySend(isOnline(context)) // reevalúa por si hay otra red
            }
            override fun onUnavailable() {
                trySend(false)
            }
        }

        cm.registerNetworkCallback(req, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
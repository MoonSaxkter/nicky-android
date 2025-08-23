package com.example.android_app.util

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build

object Bt {
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    fun isSupported(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    /** Abre el diálogo del sistema para encender BT (recomendado). */
    fun requestEnableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** Intenta apagar (puede fallar o estar bloqueado por OEM/política). */
    fun tryDisable(): Boolean = adapter?.disable() == true
}
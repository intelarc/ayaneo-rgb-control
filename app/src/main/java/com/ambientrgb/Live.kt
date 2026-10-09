package com.ambientrgb

import android.os.SystemClock

/** Live inputs for the smart modes, written by the service / key service, read by the engine. */
object Live {
    @Volatile var batteryPct = -1        // 0..100, -1 = unknown
    @Volatile var charging = false
    @Volatile var tempC = -1f            // battery temperature
    @Volatile var bass = 0f              // 0..1, smoothed
    @Volatile var treble = 0f
    @Volatile var level = 0f
    @Volatile var audioOk = false        // music capture running
    @Volatile var lastPressLeft = 0L     // uptime ms of the last button press on each side
    @Volatile var lastPressRight = 0L
    @Volatile var keysOk = false         // reactive key service connected

    fun press(right: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (right) lastPressRight = now else lastPressLeft = now
    }
}

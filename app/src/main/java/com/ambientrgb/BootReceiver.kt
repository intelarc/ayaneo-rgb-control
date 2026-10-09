package com.ambientrgb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the lights after a reboot if "Start on boot" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val c = Store(ctx).load()
        if (c.enabled && c.startOnBoot) LightService.start(ctx)
    }
}

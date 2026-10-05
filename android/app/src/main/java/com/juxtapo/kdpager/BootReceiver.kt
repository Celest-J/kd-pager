package com.juxtapo.kdpager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Kd.sessionFromPrefs(ctx) != null) PagerService.start(ctx)
    }
}

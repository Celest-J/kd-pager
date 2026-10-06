package com.juxtapo.kdpager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// The pager is the hub + FCM. The old on-phone listener (PagerService) refreshed the KD session itself,
// which would fight the hub for the one refresh token, so boot no longer starts it.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        L.init(ctx)
        L.i("boot: pager runs on the hub, nothing to start")
    }
}

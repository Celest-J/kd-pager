package com.juxtapo.kdpager

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// "Not this time" on a raid alert: just clears that notification.
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        ctx.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("id", 0))
    }
}

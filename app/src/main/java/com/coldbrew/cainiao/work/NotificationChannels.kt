package com.coldbrew.cainiao.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val PICKUP = "cainiao_pickup"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(PICKUP) != null) return
        val ch = NotificationChannel(
            PICKUP,
            "取件提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "有新包裹到驿站、或包裹滞留太久时提醒你"
        }
        mgr.createNotificationChannel(ch)
    }
}

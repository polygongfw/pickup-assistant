package com.coldbrew.pickupassistant.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 定时任务编排。
 *
 * 原来电脑上跑的是"每天 19:30 生成 pending_xxx.txt"；这里换成两个时间点主动推送：
 *  · 11:30 —— 午休前提醒，方便顺路取
 *  · 19:30 —— 和原脚本同一时刻
 * WorkManager 的周期任务在 Doze 下可能被推迟，属于系统行为，不强求分钟级准确。
 */
object NotifyScheduler {

    private const val TAG_NOON = "pickup_notify_noon"
    private const val TAG_EVENING = "pickup_notify_evening"

    fun reschedule(ctx: Context) {
        schedule(ctx, TAG_NOON, 11, 30)
        schedule(ctx, TAG_EVENING, 19, 30)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(TAG_NOON)
        wm.cancelUniqueWork(TAG_EVENING)
    }

    private fun schedule(ctx: Context, tag: String, hour: Int, minute: Int) {
        val delay = millisUntil(hour, minute)
        val req = PeriodicWorkRequestBuilder<NotifyWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(tag, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun millisUntil(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}

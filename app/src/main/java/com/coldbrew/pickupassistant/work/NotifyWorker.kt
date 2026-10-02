package com.coldbrew.pickupassistant.work

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.coldbrew.pickupassistant.R
import com.coldbrew.pickupassistant.data.AccountStore
import com.coldbrew.pickupassistant.data.MtopException
import com.coldbrew.pickupassistant.data.Pkg
import com.coldbrew.pickupassistant.data.Repo
import com.coldbrew.pickupassistant.data.Settings
import com.coldbrew.pickupassistant.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时拉取待取件并本地通知。
 *
 * 这替代了原来电脑上那条 19:30 的自动化脚本 —— 逻辑一样（拉 pending → 找新件/滞留件 → 出提醒），
 * 只是不再需要那台机器开着。
 */
class NotifyWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        if (!Settings.isNotifyEnabled(ctx)) return Result.success()

        AccountStore.ensureMigrated(ctx)
        val accounts = AccountStore.active(ctx)
        if (accounts.isEmpty()) {
            notifyOnce(ctx, ID_SESSION, "需要登录", "还没有登录淘宝账号，点开登录一下才能同步包裹。")
            return Result.success()
        }

        // 多账号合并去重后再判断新件/滞留，避免同一件包裹在两个号里各报一次
        val agg = try {
            Repo.homeAll(ctx)
        } catch (e: MtopException) {
            if (e.ret.contains("SESSION")) {
                notifyOnce(ctx, ID_SESSION, "需要重新登录", "菜鸟登录态已失效，点开重新登录一下。")
                return Result.success()
            }
            return Result.retry()
        } catch (_: Exception) {
            return Result.retry()
        }

        if (agg.okAccounts.isEmpty()) {
            val ret = agg.failed.firstOrNull()?.second ?: ""
            if (ret.contains("SESSION")) {
                notifyOnce(ctx, ID_SESSION, "需要重新登录", "菜鸟登录态已失效，点开重新登录一下。")
                return Result.success()
            }
            return Result.retry()
        }

        val pending: List<Pkg> = agg.pkgs.filter { it.pending }
        val multi = accounts.size > 1

        val known = Settings.snapshot(ctx)
        val nowIds = pending.map { it.mailNo }.toSet()
        val fresh = pending.filter { it.mailNo.isNotEmpty() && it.mailNo !in known }
        val overdue = pending.filter { it.dwellLevel >= 1 }

        when {
            fresh.isNotEmpty() -> {
                val first = fresh.first()
                val title = if (fresh.size == 1) "有包裹到驿站了" else "${fresh.size} 件新包裹到驿站"
                val body = buildString {
                    append(first.stationName.ifEmpty { "驿站" })
                    if (multi && first.accountLabel.isNotEmpty()) {
                        append(" · ").append(first.accountLabel)
                    }
                    if (first.pickupCode.isNotEmpty()) append(" · 取件码 ").append(first.pickupCode)
                    if (fresh.size > 1) append("\n还有 ").append(fresh.size - 1).append(" 件待取")
                }
                notifyOnce(ctx, ID_PICKUP, title, body)
            }
            overdue.isNotEmpty() -> {
                val worst = overdue.maxByOrNull { it.dwellDays } ?: overdue.first()
                val title = "${overdue.size} 件还没取"
                val body = buildString {
                    append("最久的已滞留 ").append(worst.dwellDays).append(" 天")
                    if (worst.pickupCode.isNotEmpty()) append(" · 取件码 ").append(worst.pickupCode)
                    append("\n").append(worst.stationName)
                    if (multi && worst.accountLabel.isNotEmpty()) {
                        append(" · ").append(worst.accountLabel)
                    }
                }
                notifyOnce(ctx, ID_PICKUP, title, body)
            }
        }

        Settings.saveSnapshot(ctx, nowIds)
        return Result.success()
    }

    /** 同一天同一类提醒只发一次。 */
    private fun notifyOnce(ctx: Context, id: Int, title: String, body: String) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()) + "#" + id
        if (Settings.lastNotifyDay(ctx) == day) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        NotificationChannels.ensure(ctx)

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, id, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, NotificationChannels.PICKUP)
            .setSmallIcon(R.drawable.ic_stat_box)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
            Settings.markNotifyDay(ctx, day)
        } catch (_: SecurityException) {
            // 没授权就算了，不要把后台任务搞崩
        }
    }

    companion object {
        const val ID_PICKUP = 1001
        const val ID_SESSION = 1002
    }
}

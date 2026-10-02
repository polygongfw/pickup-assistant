package com.coldbrew.pickupassistant.data

import android.content.Context

/** 轻量设置与通知快照（用来判断"哪几件是新到站的"）。 */
object Settings {

    private const val SP = "pickup_settings"
    private const val KEY_NOTIFY = "notify_enabled"
    private const val KEY_SNAPSHOT = "snapshot_mailnos"
    private const val KEY_LAST_NOTIFY_DAY = "last_notify_day"
    private const val KEY_ASKED_NOTIFY_PERM = "asked_notify_perm"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun ensureDefaults(c: Context) {
        val p = sp(c)
        if (!p.contains(KEY_NOTIFY)) p.edit().putBoolean(KEY_NOTIFY, true).apply()
    }

    fun isNotifyEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_NOTIFY, true)

    fun setNotifyEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * 是否已经主动申请过通知权限。
     *
     * 存在的理由：提醒开关默认是开的，但 Android 13+ 的通知权限**不申请就是拒绝**，
     * 结果就是「开关显示已开启、通知永远不来」这种静默失效。所以首次启动时补申请一次，
     * 用这个标记保证只弹一次，不在每次启动都骚扰用户。
     */
    fun askedNotifyPermission(c: Context): Boolean =
        sp(c).getBoolean(KEY_ASKED_NOTIFY_PERM, false)

    fun markAskedNotifyPermission(c: Context) =
        sp(c).edit().putBoolean(KEY_ASKED_NOTIFY_PERM, true).apply()

    /** 上一次已知的待取件运单号集合。 */
    fun snapshot(c: Context): Set<String> =
        sp(c).getStringSet(KEY_SNAPSHOT, emptySet())?.toSet() ?: emptySet()

    fun saveSnapshot(c: Context, ids: Set<String>) =
        sp(c).edit().putStringSet(KEY_SNAPSHOT, ids).apply()

    /** 同一天最多提醒多次，避免打扰。 */
    fun lastNotifyDay(c: Context): String = sp(c).getString(KEY_LAST_NOTIFY_DAY, "") ?: ""
    fun markNotifyDay(c: Context, day: String) = sp(c).edit().putString(KEY_LAST_NOTIFY_DAY, day).apply()
}

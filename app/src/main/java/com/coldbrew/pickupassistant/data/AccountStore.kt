package com.coldbrew.pickupassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 一个淘宝账号（= 一个登录槽位）。
 *
 * `id` 同时是 [SessionStore] 的 slot，cookie 存在 `pickup_session_<id>` 里。
 */
data class Account(
    val id: String,
    /** 淘宝昵称（从 `_nk_` / `tracknick` 解出，登录后自动回填） */
    val nick: String = "",
    /** 用户手动起的备注名（如「家人的号」），非空时优先展示 */
    val customName: String = "",
    /** 淘宝数字用户 ID（`unb`），用于识别「同一个账号重复添加」 */
    val unb: String = "",
    val addedAt: Long = 0L,
    val savedAt: Long = 0L,
    /** 是否参与首页合并展示 */
    val enabled: Boolean = true
) {
    /** 界面上显示的名字 */
    val displayName: String
        get() = customName.ifEmpty { nick.ifEmpty { "淘宝账号" } }

    /** 头像占位用首字 */
    val initial: String
        get() = displayName.trim().take(1).ifEmpty { "淘" }

    /** 是否有可用登录凭证（由 [AccountStore] 注入判断，见 [AccountStore.isLoggedIn]） */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nick", nick)
        put("customName", customName)
        put("unb", unb)
        put("addedAt", addedAt)
        put("savedAt", savedAt)
        put("enabled", enabled)
    }

    companion object {
        fun from(o: JSONObject): Account = Account(
            id = o.optString("id"),
            nick = o.optString("nick"),
            customName = o.optString("customName"),
            unb = o.optString("unb"),
            addedAt = o.optLong("addedAt"),
            savedAt = o.optLong("savedAt"),
            enabled = o.optBoolean("enabled", true)
        )
    }
}

/**
 * 多账号注册表。
 *
 * 设计要点：
 * - 账号元数据（昵称/备注/启用状态）存 `pickup_accounts`；
 *   真正的 cookie 各自存在 `pickup_session_<id>`，由 [SessionStore] 管。
 * - 老版本只有一份 cookie，槽位是 [SessionStore.SLOT_LEGACY]；
 *   [ensureMigrated] 在首次启动时把它登记成一个账号，**不搬数据**，原地可用。
 * - 第一个账号天然是「主账号」，[primary] 返回它，用于查快递这类不区分账号的场景。
 */
object AccountStore {

    private const val SP = "pickup_accounts"
    private const val KEY_LIST = "list"
    private const val KEY_INITED = "inited"

    @Volatile
    private var cache: List<Account>? = null

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ─────────────────────── 读 ───────────────────────

    @Synchronized
    fun list(c: Context): List<Account> {
        cache?.let { return it }
        val out = ArrayList<Account>()
        val raw = sp(c).getString(KEY_LIST, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val a = Account.from(o)
                    if (a.id.isNotEmpty()) out.add(a)
                }
            } catch (_: Exception) {
                // 存储损坏按空处理，不崩溃
            }
        }
        cache = out
        return out
    }

    fun get(c: Context, id: String): Account? = list(c).firstOrNull { it.id == id }

    /** 参与合并展示的账号（有登录态 + 用户没关掉）。 */
    fun active(c: Context): List<Account> =
        list(c).filter { it.enabled && isLoggedIn(c, it.id) }

    fun store(c: Context, id: String): SessionStore = SessionStore.open(c, id)

    fun isLoggedIn(c: Context, id: String): Boolean = store(c, id).hasLogin()

    /** 主账号：列表里第一个有登录态的；没有则第一个账号。 */
    fun primary(c: Context): Account? {
        val all = list(c)
        return all.firstOrNull { isLoggedIn(c, it.id) } ?: all.firstOrNull()
    }

    /** 主账号的 cookie 槽位；无账号时退到历史槽位，保证老路径不炸。 */
    fun primaryStore(c: Context): SessionStore =
        SessionStore.open(c, primary(c)?.id ?: SessionStore.SLOT_LEGACY)

    // ─────────────────────── 写 ───────────────────────

    @Synchronized
    fun add(
        c: Context,
        nick: String = "",
        unb: String = "",
        slotId: String? = null
    ): Account {
        val cur = list(c)
        val id = slotId ?: newId(cur)
        val a = Account(
            id = id,
            nick = nick,
            unb = unb,
            addedAt = System.currentTimeMillis()
        )
        save(c, cur + a)
        return a
    }

    /** 按 id 更新（找不到就忽略）。 */
    @Synchronized
    fun update(c: Context, a: Account) {
        val cur = list(c)
        if (cur.none { it.id == a.id }) return
        save(c, cur.map { if (it.id == a.id) a else it })
    }

    @Synchronized
    fun rename(c: Context, id: String, name: String) {
        val cur = list(c)
        save(c, cur.map { if (it.id == id) it.copy(customName = name.trim()) else it })
    }

    @Synchronized
    fun setEnabled(c: Context, id: String, on: Boolean) {
        val cur = list(c)
        save(c, cur.map { if (it.id == id) it.copy(enabled = on) else it })
    }

    /** 删除账号（连同它的 cookie）。最后一个账号不会被删掉，避免界面出现"无槽位可登录"。 */
    @Synchronized
    fun remove(c: Context, id: String): Boolean {
        val cur = list(c)
        if (cur.size <= 1) return false
        store(c, id).clear()
        save(c, cur.filterNot { it.id == id })
        return true
    }

    /**
     * 登录成功后回写凭证信息。
     *
     * 返回最终落库的账号：如果 `unb` 已有同号（用户把同一个淘宝号登了两次），
     * 就复用它、把 cookie 覆盖到那个槽位上，避免列表里出现两个一模一样的账号。
     */
    @Synchronized
    fun onLoggedIn(c: Context, slotId: String, nick: String, unb: String): Account {
        val cur = list(c)
        val dup = if (unb.isNotEmpty()) {
            cur.firstOrNull { it.unb == unb && it.id != slotId }
        } else null

        val now = System.currentTimeMillis()
        if (dup != null) {
            // 把新 cookie 挪到已存在的槽位，丢弃刚创建的空槽位
            val fresh = store(c, slotId)
            val target = store(c, dup.id)
            fresh.all().forEach { target.put(it) }
            target.persist()
            fresh.clear()
            val merged = dup.copy(
                nick = nick.ifEmpty { dup.nick },
                savedAt = now,
                enabled = true
            )
            save(c, cur.filterNot { it.id == slotId }.map { if (it.id == dup.id) merged else it })
            return merged
        }

        val a = cur.firstOrNull { it.id == slotId }
            ?: return add(c, nick = nick, unb = unb, slotId = slotId)
        val updated = a.copy(
            nick = nick.ifEmpty { a.nick },
            unb = unb.ifEmpty { a.unb },
            savedAt = now,
            enabled = true
        )
        save(c, cur.map { if (it.id == slotId) updated else it })
        return updated
    }

    /** 记号：该槽位的 cookie 刚被网关刷新过。 */
    fun markSaved(c: Context, id: String) {
        val a = get(c, id) ?: return
        update(c, a.copy(savedAt = System.currentTimeMillis()))
    }

    // ─────────────────────── 迁移 ───────────────────────

    /**
     * 首次启动时把老版本的单账号 cookie 登记成账号。
     *
     * 只在 `inited` 标记缺失时执行一次 —— 否则用户主动删光账号后，
     * 下次启动会被"又自动冒出来一个"。
     */
    @Synchronized
    fun ensureMigrated(c: Context) {
        val p = sp(c)
        if (p.getBoolean(KEY_INITED, false)) return
        if (list(c).isEmpty()) {
            val legacy = SessionStore.open(c, SessionStore.SLOT_LEGACY)
            val legacyExists = SessionStore.exists(c, SessionStore.SLOT_LEGACY)
            if (legacy.hasLogin()) {
                add(
                    c,
                    nick = NickCodec.decode(legacy.get("_nk_") ?: legacy.get("tracknick") ?: ""),
                    unb = legacy.get("unb") ?: "",
                    slotId = SessionStore.SLOT_LEGACY
                )
            } else if (!legacyExists) {
                // 全新安装：先开一个空槽位，界面上直接点「登录」即可
                add(c, slotId = SessionStore.SLOT_LEGACY)
            } else {
                // 老数据存在但已失效（字段在、服务端过期）—— 也要登记，用户重登即可
                add(c, slotId = SessionStore.SLOT_LEGACY)
            }
        }
        p.edit().putBoolean(KEY_INITED, true).apply()
    }

    // ─────────────────────── 内部 ───────────────────────

    private fun newId(cur: List<Account>): String {
        val used = cur.mapNotNull { it.id.removePrefix("a").toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return "a$n"
    }

    private fun save(c: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(c).edit().putString(KEY_LIST, arr.toString()).apply()
        cache = list
    }

    /** 仅供测试重置内存缓存。 */
    internal fun invalidateCache() {
        cache = null
    }
}

/**
 * 淘宝昵称解码。
 *
 * `_nk_` 是 URL-encoded 的昵称，中文常见 **GBK** 编码（阿里历史遗留），
 * 直接 `URLDecoder.decode(s, "UTF-8")` 会得到一串乱码，
 * 所以这里先自己解 `%XX` 成字节，再按 UTF-8 → GBK 的顺序试。
 */
object NickCodec {

    /** 解出可读昵称；解不出来就返回原串。 */
    fun decode(raw: String): String {
        if (raw.isEmpty()) return ""
        // 不含转义符，可能已经是明文
        if (!raw.contains('%') && !raw.contains('+')) return raw.trim()

        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val b = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) {
                    bytes.write(b)
                    i += 3
                    continue
                }
            }
            if (ch == '+') {
                bytes.write(' '.code)
                i++
                continue
            }
            val arr = ch.toString().toByteArray(Charsets.UTF_8)
            bytes.write(arr, 0, arr.size)
            i++
        }

        val data = bytes.toByteArray()
        val utf8 = String(data, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8.trim()
        return try {
            String(data, charset("GBK")).trim()
        } catch (_: Exception) {
            utf8.trim()
        }
    }

    /** 脱敏展示：`家人的号` 这类自定义名不动，淘宝昵称保留首尾。 */
    fun mask(s: String): String {
        if (s.isEmpty()) return "(未知)"
        if (s.length <= 2) return s
        if (s.length <= 3) return s.substring(0, 1) + "**"
        return s.substring(0, 1) + "****" + s.substring(s.length - 1)
    }
}

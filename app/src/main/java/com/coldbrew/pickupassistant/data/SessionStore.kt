package com.coldbrew.pickupassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条 cookie。domain 仅作记录与导出用（本项目只访问淘系域名，请求时会全量带上）。 */
data class Ck(
    val name: String,
    val value: String,
    val domain: String = ".taobao.com",
    val path: String = "/",
    val expires: Long = 0L
)

/**
 * 登录态存储 —— **一个实例 = 一个淘宝账号的 cookie 槽位**。
 *
 * 与 Python 版 `session.json` 语义对齐：保存浏览器/内嵌 WebView 导出的一整份 cookie，
 * 里面同时包含登录凭证（cookie2 / unb / _nk_ / tracknick / lgc）与网关 token（_m_h5_tk）。
 *
 * 多账号：`slot` 就是 [AccountStore] 里的账号 id，每个账号一份独立 SharedPreferences
 * （`pickup_session_<slot>`）。历史单账号数据落在 [SLOT_LEGACY] 槽位上，不做搬迁即可继续用。
 */
class SessionStore internal constructor(context: Context, val slot: String) : CookieStore {

    private val sp = context.applicationContext
        .getSharedPreferences(spName(slot), Context.MODE_PRIVATE)
    private val jar = LinkedHashMap<String, Ck>()

    init {
        restore()
    }

    val size: Int get() = jar.size

    fun all(): List<Ck> = jar.values.toList()

    fun get(name: String): String? = jar.values.firstOrNull { it.name == name }?.value

    override fun put(c: Ck) {
        jar[c.name] = c
    }

    override fun remove(name: String) {
        jar.remove(name)
    }

    fun lastSavedAt(): Long = sp.getLong(KEY_SAVED_AT, 0L)

    fun clear() {
        jar.clear()
        sp.edit().clear().apply()
    }

    /** 请求头用的 cookie 串。 */
    override fun cookieHeader(): String =
        jar.values.filter { it.value.isNotEmpty() }
            .joinToString("; ") { "${it.name}=${it.value}" }

    /** 是否具备真实登录态。注意 cookie2 匿名访客也会下发，不能当判据。 */
    fun hasLogin(): Boolean = loginFields().isNotEmpty()

    fun loginFields(): List<String> =
        LOGIN_KEYS.filter { (get(it) ?: "").isNotEmpty() }

    /** _m_h5_tk 形如 "abcdef123456_1696000000000"，签名只用下划线前的 token 段。 */
    override fun token(): String = (get("_m_h5_tk") ?: "").substringBefore('_')

    /** 合并一段 "k=v; k=v" 形式的 cookie 文本（用于粘贴导入）。 */
    fun mergeCookieText(text: String): Int {
        var n = 0
        text.split(';', '\n').forEach { raw ->
            val s = raw.trim()
            val i = s.indexOf('=')
            if (i > 0) {
                val name = s.substring(0, i).trim()
                val value = s.substring(i + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    put(Ck(name, value))
                    n++
                }
            }
        }
        if (n > 0) persist()
        return n
    }

    override fun persist() {
        val arr = JSONArray()
        jar.values.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("domain", c.domain)
                put("path", c.path)
                put("expires", c.expires)
            })
        }
        sp.edit()
            .putString(KEY_JAR, arr.toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 导出成可粘贴文本（备用通道：手动迁移登录态用）。 */
    fun exportText(): String = cookieHeader()

    private fun restore() {
        val raw = sp.getString(KEY_JAR, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isEmpty()) continue
                jar[name] = Ck(
                    name = name,
                    value = o.optString("value"),
                    domain = o.optString("domain", ".taobao.com"),
                    path = o.optString("path", "/"),
                    expires = o.optLong("expires", 0L)
                )
            }
        } catch (_: Exception) {
            // 存储损坏时按未登录处理，不崩溃
            jar.clear()
        }
    }

    companion object {
        private const val KEY_JAR = "jar"
        private const val KEY_SAVED_AT = "saved_at"

        /**
         * 历史单账号槽位。SP 名保持旧的 `pickup_session`（不加后缀），
         * 这样老版本升级上来时已登录的 cookie 原地可用，不需要迁移动作。
         */
        const val SLOT_LEGACY = "default"

        /** ⚠ cookie2 匿名访客也有，必须用登录后才出现的字段判定 */
        val LOGIN_KEYS = listOf("unb", "_nk_", "tracknick", "lgc")

        fun spName(slot: String): String =
            if (slot.isEmpty() || slot == SLOT_LEGACY) "pickup_session"
            else "pickup_session_$slot"

        fun open(context: Context, slot: String): SessionStore =
            SessionStore(context, slot)

        /** 该槽位是否从未落过盘（用于判断迁移必要性）。 */
        fun exists(context: Context, slot: String): Boolean =
            context.applicationContext
                .getSharedPreferences(spName(slot), Context.MODE_PRIVATE)
                .contains(KEY_JAR)
    }
}

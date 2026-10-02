package com.coldbrew.cainiao.data

/**
 * Set-Cookie 解析 —— 纯 JDK，无 Android 依赖。
 *
 * 抽出来是因为**登录态全部靠 Set-Cookie 下发**，而不止 MTOP 一条链路会拿到它：
 *   · MTOP 网关每次调用刷 `_m_h5_tk`
 *   · 淘宝扫码登录的 generate / query 两个接口下发 `cookie2 / t / _tb_token_`，
 *     扫码确认后一次性下发 `unb / _nk_ / lgc / sgcookie`（真正的登录凭证）
 * 两处必须用同一套规则，否则「扫码登录建好了会话、MTOP 却读不到」这种问题会很难查。
 */
object Cookies {

    /** 容错值：没写 domain 时按 `.taobao.com` 记（本项目只访问淘系域名，请求时全量带上）。 */
    const val DEFAULT_DOMAIN = ".taobao.com"

    /**
     * 解析一条 Set-Cookie 并写入会话。
     *
     * 注意 `value` 为空串表示**服务端要求删除该 cookie**（注销时就是这么下发 unb 的），
     * 所以空值走 remove 而不是 put —— 否则会把上一份登录态留在 jar 里。
     */
    fun absorb(store: CookieStore, raw: String) {
        val parts = raw.split(";")
        val nv = parts.firstOrNull()?.trim() ?: return
        val eq = nv.indexOf('=')
        if (eq <= 0) return
        val name = nv.substring(0, eq).trim()
        val value = nv.substring(eq + 1).trim()
        if (name.isEmpty()) return

        var domain = DEFAULT_DOMAIN
        var path = "/"
        var expires = 0L

        parts.drop(1).forEach { p ->
            val s = p.trim()
            val i = s.indexOf('=')
            if (i <= 0) return@forEach
            when (s.substring(0, i).trim().lowercase()) {
                "domain" -> domain = s.substring(i + 1).trim()
                "path" -> path = s.substring(i + 1).trim()
                "max-age" -> s.substring(i + 1).trim().toLongOrNull()
                    ?.let { expires = System.currentTimeMillis() + it * 1000 }
            }
        }

        if (value.isEmpty()) store.remove(name) else store.put(Ck(name, value, domain, path, expires))
    }
}

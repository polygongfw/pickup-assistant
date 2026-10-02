package com.coldbrew.cainiao.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 扫码登录当前状态。取值逐字对齐官方登录页 JS（havana-nlogin）里的 `qrCodeStatus`。 */
enum class QrStatus {
    /** 二维码还没被扫 */
    NEW,

    /** 已扫码，等用户在手机淘宝上点确认 */
    SCANED,

    /** 用户已确认 —— 此时响应里已经下发了登录凭证 */
    CONFIRMED,

    /** 二维码过期，需要刷新 */
    EXPIRED,

    /** 用户取消了本次登录 */
    CANCELED,

    /** 服务端报错（如风控拦截） */
    ERROR,

    /** 出现了官方 JS 没定义过的状态，按未知处理（继续轮询，不要误判成功/失败） */
    UNKNOWN;

    companion object {
        fun of(raw: String?): QrStatus = when (raw?.trim()?.uppercase()) {
            "NEW" -> NEW
            "SCANED" -> SCANED
            "CONFIRMED" -> CONFIRMED
            "EXPIRED" -> EXPIRED
            "CANCELED" -> CANCELED
            "ERROR" -> ERROR
            else -> UNKNOWN
        }

        /** 终态：不会再变，轮询该停 */
        val TERMINAL = setOf(CONFIRMED, EXPIRED, CANCELED, ERROR)
    }
}

class QrLoginException(message: String) : Exception(message)

/**
 * 淘宝「扫码登录」客户端 —— **纯 JDK，不依赖 Android，也不依赖 WebView**。
 *
 * 这是逆向菜鸟/淘宝登录页后选定的落地路径，接口全部来自官方页面自带的配置
 * （`login.taobao.com/member/login.jhtml` 内联的 `getQRCodeApi` / `checkQRCodeApi`）：
 *
 *   GET  /havanaone/loginLegacy/qrCode/generate.do?bizEntrance=taobao_h5&bizName=taobao
 *        → { content: { data: { t, ck, codeContent } } }
 *   POST /havanaone/loginLegacy/qrCode/query.do?bizEntrance=taobao_h5&bizName=taobao
 *        ← t / ck / ua / navlanguage / navUserAgent / navPlatform
 *        → { content: { data: { qrCodeStatus: NEW|SCANED|CONFIRMED|EXPIRED|CANCELED|ERROR } } }
 *
 * 用户用手机淘宝扫 `codeContent` 并点确认后，**query 的响应会直接 Set-Cookie 下发
 * `unb / _nk_ / lgc / sgcookie`** —— 也就是完整登录态；本类把它们吸收进 [store]，
 * 后续 MTOP 请求自然带上，不需要任何跳转、不需要输密码、也不需要淘宝开放平台的
 * AppKey / AppSecret（「跳淘宝 App 点一下同意」的 SSO 路需要阿里注册过的 appKey，
 * 拿不到，该入口已移除）。
 *
 * ⚠ 三个易错点：
 *   1. `query` 只认 **POST + form-urlencoded**。用 GET 会直接拿到 EXPIRED（实操踩过）。
 *   2. `t` 在每次 generate 时刷新，和 `ck` 是配对使用的；刷新二维码必须两个都换。
 *   3. **`CONFIRMED` 之后还要跑一遍收尾请求**（[completeLogin]）—— 官方页面此时会用
 *      隐藏 `<img>` 去戳 `asyncUrls` 再跳转，剩下的登录 cookie 在那几个响应里。
 *      漏了这一步的典型症状是「扫码成功但 MTOP 仍判 SESSION_EXPIRED」。
 */
class QrLogin(private val store: CookieStore) {

    /** 一次扫码会话。`t` 与 `ck` 必须成对使用。 */
    data class Qr(val t: Long, val ck: String, val codeContent: String)

    /**
     * 申请一张新二维码。
     *
     * 同时会把响应里的 `cookie2 / t / _tb_token_` 收进会话 —— 这些是 query 接口用来
     * 认领这张二维码的凭据，**少了它们 query 会一直返回 EXPIRED**。
     */
    fun generate(): Qr {
        val body = request(
            method = "GET",
            url = Api.QR_GENERATE + "?" + Api.QR_BIZ
        )
        val data = body.optJSONObject("content")?.optJSONObject("data")
            ?: throw QrLoginException("generate::no_data")
        val ck = data.optString("ck")
        val content = data.optString("codeContent")
        if (ck.isEmpty() || content.isEmpty()) throw QrLoginException("generate::empty_qr")
        return Qr(t = data.optLong("t"), ck = ck, codeContent = content)
    }

    /** 查询一次状态。CONFIRMED 时登录凭证已经被 [store] 收下。 */
    fun query(qr: Qr): QrStatus {
        val form = buildString {
            append("t=").append(encode(qr.t.toString()))
            append("&ck=").append(encode(qr.ck))
            append("&ua=")
            append("&navlanguage=").append(encode("zh-CN"))
            append("&navUserAgent=").append(encode(Api.UA))
            append("&navPlatform=").append(encode("Linux armv8l"))
        }
        val body = request(
            method = "POST",
            url = Api.QR_QUERY + "?" + Api.QR_BIZ,
            form = form
        )
        val data = body.optJSONObject("content")?.optJSONObject("data")
            ?: return QrStatus.ERROR
        val st = QrStatus.of(data.optString("qrCodeStatus"))
        if (st == QrStatus.CONFIRMED) completeLogin(data)
        return st
    }

    // ─────────────────── CONFIRMED 之后的收尾（关键） ───────────────────

    /**
     * 用户确认后，把官方页面紧接着要跑的那批「收尾请求」补上。
     *
     * 逐个对齐 `havana-nlogin` 的 `handleLoginResult()`：
     *
     * ```js
     * if (e.asyncUrls.length > 0) {                       // ← 主要靠这个
     *     for (...) { var img = document.createElement("img");
     *                 img.src = asyncUrls[n];             // 隐藏 <img>，跨域写 cookie 的老手法
     *                 document.body.appendChild(img); }
     *     setTimeout(() => handelResult(e), 500);
     * } else if (e.miniLogouts || e.miniVsts) { ... }     // 隐藏 <script>/<img>
     * handelResult(e):
     *     if (e.redirect)      top.location.href    = getSafeUrl(e.redirectUrl);
     *     else if (e.replace)  location.replace(getSafeUrl(e.replaceUrl));
     * ```
     *
     * **为什么必须照做**：这些 URL 的响应头里才有剩下的登录 cookie
     * （淘系登录态是分散下发的，query 那一条只能给你一部分）。
     * 只拿 query 的 Set-Cookie 往往不够 —— 会表现成「扫码成功了，
     * 但 MTOP 仍判 SESSION_EXPIRED」，而且极难排查。
     *
     * 顺带说明为什么用「逐跳手收 cookie」而不是 `instanceFollowRedirects=true`：
     * JDK 跟重定向时**丢弃中间响应的 Set-Cookie**（除非装了全局 CookieHandler），
     * 而跳转链里恰恰每一跳都在下发 cookie。所以见 [fetchFollowing]。
     *
     * @return 实际打过的 URL（便于日志/排查）
     */
    fun completeLogin(data: JSONObject): List<String> {
        val fired = ArrayList<String>()
        for (u in completionUrls(data)) {
            try {
                fetchFollowing(u)
                fired.add(u)
            } catch (_: Exception) {
                // 收尾请求失败不该让整个登录失败：query 那次下发的凭证可能已经够用，
                // 外层还有 Repo.verify 做最终判定。
            }
        }
        store.persist()
        return fired
    }

    /**
     * 抓一个 URL（最多跟 [MAX_REDIRECTS] 跳），**每一跳的 Set-Cookie 都收进会话**。
     * 注意这里不返回业务数据，只为了 cookie —— 收尾 URL 返回的多是 HTML/空图。
     */
    private fun fetchFollowing(url: String): String {
        var cur = url
        var hops = 0
        while (hops++ < MAX_REDIRECTS) {
            val conn = URL(cur).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                // 自己跟，才能拿到中间响应的 Set-Cookie
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", Api.UA)
                conn.setRequestProperty("Referer", Api.LOGIN_REFERER)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                val ck = store.cookieHeader()
                if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)

                val code = conn.responseCode
                collectCookies(conn) // ★ 本跳的 Set-Cookie 先收下，再决定去哪

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrEmpty()) return ""
                    // Location 可能是相对路径，按 RFC 以当前 URL 为基准解析
                    cur = URL(URL(cur), loc).toString()
                    continue
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return ""
    }

    // ──────────────────────── 底层 ────────────────────────

    private fun request(method: String, url: String, form: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Api.UA)
            conn.setRequestProperty("Referer", Api.LOGIN_REFERER)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val ck = store.cookieHeader()
            if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
                )
            }

            if (form != null) {
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            collectCookies(conn)

            if (text.isBlank()) throw QrLoginException("http::$code::empty")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw QrLoginException("http::$code::bad_json")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun collectCookies(conn: HttpURLConnection) {
        conn.headerFields.forEach { (key, values) ->
            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { Cookies.absorb(store, it) }
            }
        }
        store.persist()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {

        /** 收尾请求最多跟几跳重定向。淘系登录跳转链一般 1~3 跳。 */
        private const val MAX_REDIRECTS = 6

        /**
         * 从 CONFIRMED 的 `data` 里挑出所有「收尾 URL」，按官方执行顺序去重。
         *
         * 字段名全部来自 `havana-nlogin` 的 `handleLoginResult()` / `handelResult()`：
         * `asyncUrls`（数组或对象）、`miniLogouts`、`miniVsts`、`redirectUrl`、
         * `replaceUrl`、`parentRedirectUrl`、`iframeRedirectUrl`。
         *
         * 一个都没有时兜底回访登录页 —— 官方页面在 CONFIRMED 后无论如何都会走一次跳转，
         * 我们不跳也得摸一下，否则可能少收几个 cookie。
         *
         * 抽成纯函数是为了能单测（不需要网络，也不需要在 HTTP 上做假）。
         */
        internal fun completionUrls(data: JSONObject): List<String> {
            val out = LinkedHashSet<String>()

            // asyncUrls / miniLogouts / miniVsts：官方是 for...in 遍历，
            // 数组和对象两种形态都吃，所以这里也两种都接。
            for (key in listOf("asyncUrls", "miniLogouts", "miniVsts")) {
                when (val v = data.opt(key)) {
                    is JSONArray -> for (i in 0 until v.length()) addUrl(out, v.optString(i))
                    is JSONObject -> v.keys().forEach { addUrl(out, v.optString(it)) }
                    is String -> addUrl(out, v)
                }
            }

            // 跳转类：官方按 redirect > parentRedirect > iframeRedirect > replace 优先级，
            // 但对我们来说「全打一遍」只有好处（每个都会下发 cookie）。
            for (key in listOf(
                "redirectUrl", "replaceUrl", "parentRedirectUrl", "iframeRedirectUrl"
            )) {
                addUrl(out, data.optString(key))
            }

            if (out.isEmpty()) out.add(Api.LOGIN_REFERER)
            return out.toList()
        }

        private fun addUrl(into: MutableSet<String>, raw: String?) {
            val s = raw?.trim().orEmpty()
            if (!s.startsWith("http://") && !s.startsWith("https://")) return
            into.add(s)
        }
    }
}

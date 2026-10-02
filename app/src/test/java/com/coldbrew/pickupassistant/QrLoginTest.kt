package com.coldbrew.pickupassistant

import com.coldbrew.pickupassistant.data.Api
import com.coldbrew.pickupassistant.data.Cookies
import com.coldbrew.pickupassistant.data.MemoryCookieStore
import com.coldbrew.pickupassistant.data.QrLogin
import com.coldbrew.pickupassistant.data.QrStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 扫码登录链路的验证。
 *
 * 分两层：
 *  ① 纯函数层（无需网络）—— 状态枚举映射、Set-Cookie 解析。这两块错了会表现为
 *     「扫完码 App 却拿不到登录态」，而且线上难复现，必须用测试锁住。
 *  ② 联网层 —— 真打 `login.taobao.com` 的 generate/query。只验证到 `NEW` 为止：
 *     后面必须真人扫一次码，脚本没法代劳。
 *
 * 运行全部（含联网）：
 *   bash gradle.sh :app:testDebugUnitTest --tests '*QrLoginTest*' -i
 */
class QrLoginTest {

    private fun online(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("login.taobao.com", 443), 6000) }
        true
    } catch (_: Exception) {
        false
    }

    // ─────────────────────── ① 纯函数 ───────────────────────

    /**
     * 状态字符串必须逐字对齐官方 JS 的 `qrCodeStatus` 取值。
     *
     * 特别注意两件事：
     *   · 大小写/空白要容错（服务端历史上出现过大小写不一致）；
     *   · **没见过的值必须是 UNKNOWN 而不是失败** —— 把未知状态当成 ERROR 会在
     *     服务端新增状态时把用户的二维码误判死。
     */
    @Test
    fun `qrCodeStatus 映射与未知值兜底`() {
        assertEquals(QrStatus.NEW, QrStatus.of("NEW"))
        assertEquals(QrStatus.SCANED, QrStatus.of("SCANED"))
        assertEquals(QrStatus.CONFIRMED, QrStatus.of("CONFIRMED"))
        assertEquals(QrStatus.EXPIRED, QrStatus.of("EXPIRED"))
        assertEquals(QrStatus.CANCELED, QrStatus.of("CANCELED"))
        assertEquals(QrStatus.ERROR, QrStatus.of("ERROR"))

        // 容错：小写、两侧空白
        assertEquals(QrStatus.CONFIRMED, QrStatus.of(" confirmed "))
        // 未知/缺失 → UNKNOWN，绝不能是 ERROR
        assertEquals(QrStatus.UNKNOWN, QrStatus.of("SOMETHING_NEW"))
        assertEquals(QrStatus.UNKNOWN, QrStatus.of(null))
        assertEquals(QrStatus.UNKNOWN, QrStatus.of(""))
    }

    /** 终态集合决定「轮询还要不要继续」，漏一个就会空转到 3 分钟超时。 */
    @Test
    fun `只有终态才停止轮询`() {
        assertTrue(QrStatus.TERMINAL.containsAll(
            listOf(QrStatus.CONFIRMED, QrStatus.EXPIRED, QrStatus.CANCELED, QrStatus.ERROR)
        ))
        assertFalse(QrStatus.TERMINAL.contains(QrStatus.NEW))
        assertFalse(QrStatus.TERMINAL.contains(QrStatus.SCANED))
        assertFalse(QrStatus.TERMINAL.contains(QrStatus.UNKNOWN))
    }

    /** 普通 Set-Cookie：name/value 必须落到 jar 里，并且 Domain/Path 能被解析走。 */
    @Test
    fun `absorb 写入普通 cookie 并剥离属性`() {
        val s = MemoryCookieStore()
        Cookies.absorb(
            s,
            "cookie2=1a2b3c4d5e; Domain=.taobao.com; Path=/; Max-Age=31536000; Secure; HttpOnly"
        )
        assertTrue("cookie2 应已写入", s.cookieHeader().contains("cookie2=1a2b3c4d5e"))
        // 属性不能被当成独立 cookie
        assertFalse(s.cookieHeader().contains("Domain"))
        assertFalse(s.cookieHeader().contains("Max-Age"))
        assertFalse(s.cookieHeader().contains("HttpOnly"))
    }

    /**
     * 空值 = 服务端要求删除该 cookie（注销时 `unb` 就是这么下发的）。
     *
     * 如果实现成 `put("")`，jar 里会留下 `unb=` 这种空壳，`hasLogin()` 之类的判断
     * 就可能把「已注销」误判成「还登着」。
     */
    @Test
    fun `absorb 空值表示删除而不是写入`() {
        val s = MemoryCookieStore()
        Cookies.absorb(s, "unb=1234567890; Domain=.taobao.com; Path=/")
        assertTrue(s.cookieHeader().contains("unb=1234567890"))

        Cookies.absorb(s, "unb=; Domain=.taobao.com; Path=/; Max-Age=0")
        assertFalse("空值应把它从 jar 里删掉", s.cookieHeader().contains("unb"))
    }

    /** 畸形输入不能抛异常 —— 这里在 HTTP 响应解析路径上，抛出去会中断整条登录流程。 */
    @Test
    fun `absorb 对畸形输入不抛异常`() {
        val s = MemoryCookieStore()
        listOf("", "   ", "novalue", "=noname", "; ; ;", "a=1").forEach {
            Cookies.absorb(s, it)
        }
        // a=1 是合法的，应当被收下
        assertTrue(s.cookieHeader().contains("a=1"))
    }

    /** `_m_h5_tk` 的 token 段 = 下划线前部分；扫码链路的 store 也要遵守，否则 MTOP 会 401。 */
    @Test
    fun `token 取 _m_h5_tk 下划线前段`() {
        val s = MemoryCookieStore()
        Cookies.absorb(s, "_m_h5_tk=abc123def_1790872284832; Domain=.taobao.com; Path=/")
        assertEquals("abc123def", s.token())
    }

    // ───────── CONFIRMED 收尾 URL 抽取（对齐官方 handleLoginResult） ─────────

    /**
     * `asyncUrls` 是**数组**形态（最常见）。
     *
     * 这是登录态补全的主通道 —— 官方页面用隐藏 `<img>` 去戳它们。
     * 抽漏了就会「扫码成功但 MTOP 说没登录」。
     */
    @Test
    fun `收尾 URL 解析 asyncUrls 数组`() {
        val d = JSONObject(
            """{"qrCodeStatus":"CONFIRMED","asyncUrls":[
                 "https://login.taobao.com/member/mini_login.htm?t=1",
                 "https://passport.alibaba.com/mini_login.htm?t=2"]}"""
        )
        val urls = QrLogin.completionUrls(d)
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://login.taobao.com/member/mini_login.htm?t=1"))
        assertTrue(urls.contains("https://passport.alibaba.com/mini_login.htm?t=2"))
    }

    /** `miniLogouts` / `miniVsts` 是官方 else 分支里的另一组收尾请求，同样不能漏。 */
    @Test
    fun `收尾 URL 解析 miniLogouts 与 miniVsts`() {
        val d = JSONObject(
            """{"miniLogouts":["https://login.taobao.com/member/logout.htm"],
                "miniVsts":["https://login.taobao.com/member/vst.htm"]}"""
        )
        val urls = QrLogin.completionUrls(d)
        assertTrue(urls.contains("https://login.taobao.com/member/logout.htm"))
        assertTrue(urls.contains("https://login.taobao.com/member/vst.htm"))
    }

    /** 跳转类字段（redirect / replace / parent / iframe）里的 URL 也要打。 */
    @Test
    fun `收尾 URL 解析跳转字段`() {
        val d = JSONObject(
            """{"redirect":true,"redirectUrl":"https://www.taobao.com/",
                "replace":true,"replaceUrl":"https://i.taobao.com/my_taobao.htm"}"""
        )
        val urls = QrLogin.completionUrls(d)
        assertTrue(urls.contains("https://www.taobao.com/"))
        assertTrue(urls.contains("https://i.taobao.com/my_taobao.htm"))
    }

    /**
     * 一个收尾 URL 都没给 → 兜底回访登录页。
     *
     * 宁可多打一次，也不能什么都不做：少收 cookie 的后果是**静默失败**
     * （用户以为登上了，实际 MTOP 拒绝），比多打一次请求难查得多。
     */
    @Test
    fun `收尾 URL 全空时兜底回访登录页`() {
        val urls = QrLogin.completionUrls(JSONObject("""{"qrCodeStatus":"CONFIRMED"}"""))
        assertEquals(listOf(Api.LOGIN_REFERER), urls)
    }

    /** 非 http(s) 的值（`javascript:`、相对路径、空串）必须被丢掉，不能拿去 openConnection。 */
    @Test
    fun `收尾 URL 过滤掉非法值`() {
        val d = JSONObject(
            """{"asyncUrls":["javascript:void(0)","/member/x.htm","",null,"ftp://a/b",
                             "https://ok.taobao.com/x"]}"""
        )
        val urls = QrLogin.completionUrls(d)
        assertEquals(1, urls.size)
        assertEquals("https://ok.taobao.com/x", urls[0])
    }

    /** 重复 URL 只打一次。 */
    @Test
    fun `收尾 URL 去重`() {
        val d = JSONObject(
            """{"asyncUrls":["https://a.taobao.com/x","https://a.taobao.com/x"],
                "redirectUrl":"https://a.taobao.com/x"}"""
        )
        assertEquals(1, QrLogin.completionUrls(d).size)
    }

    // ─────────────────────── ② 联网 ───────────────────────

    /**
     * 真打 generate → query。
     *
     * 断言到 `NEW` 就够了 —— 继续往下要么是 SCANED/CONFIRMED（需要真人扫码），
     * 要么是 EXPIRED（3 分钟没人扫）。这个用例真正锁的是三件事：
     *  ① 接口地址与 bizEntrance/bizName 参数仍然有效；
     *  ② **generate 下发的 Set-Cookie 被收进了 store** —— 少了它 query 必然返回 EXPIRED；
     *  ③ query 用 POST + form-urlencoded 能拿到合法状态（用 GET 会直接 EXPIRED）。
     */
    @Test
    fun `扫码 generate 与 query 真实可用`() {
        Assume.assumeTrue("网络不可达 login.taobao.com，跳过联网验证", online())

        val store = MemoryCookieStore()
        val qr = QrLogin(store)

        val code = try {
            qr.generate()
        } catch (e: Exception) {
            Assume.assumeNoException("generate 不可用（可能出口被墙/风控），跳过", e)
            return
        }

        println("──────── QR generate ────────")
        println("t           : ${code.t}")
        println("ck          : ${code.ck}")
        println("codeContent : ${code.codeContent}")
        println("Set-Cookie  : ${store.cookieHeader().take(200)}")

        assertTrue("t 应非 0", code.t > 0)
        assertTrue("ck 不应为空", code.ck.isNotEmpty())
        assertTrue("codeContent 应是 lgToken 形式的扫码地址",
            code.codeContent.contains("lgToken="))
        assertTrue(
            "generate 的 Set-Cookie 必须被收下（query 靠它认领二维码）",
            store.cookieHeader().isNotEmpty()
        )

        val st = qr.query(code)
        println("query 状态  : $st")
        println("query 后 cookie: ${store.cookieHeader().take(200)}")

        // 脚本跑的时候没人扫，正常就是 NEW；若恰好被扫过则是 SCANED/CONFIRMED，也算通过。
        assertTrue(
            "刚生成的码不应是终态，实际: $st",
            st == QrStatus.NEW || st == QrStatus.SCANED || st == QrStatus.CONFIRMED
        )
    }

    /** 同一张码连续查两次不能把会话查坏（模拟 2s 轮询，快速版）。 */
    @Test
    fun `重复轮询不会把会话弄失效`() {
        Assume.assumeTrue("网络不可达 login.taobao.com，跳过联网验证", online())

        val store = MemoryCookieStore()
        val qr = QrLogin(store)
        val code = try {
            qr.generate()
        } catch (e: Exception) {
            Assume.assumeNoException("generate 不可用，跳过", e)
            return
        }

        repeat(3) { i ->
            val st = qr.query(code)
            println("第 ${i + 1} 次轮询: $st")
            assertFalse("第 ${i + 1} 次轮询就拿不到状态了", st == QrStatus.UNKNOWN)
        }
        assertTrue("轮询后 cookie 不应被清空", store.cookieHeader().isNotEmpty())
    }
}

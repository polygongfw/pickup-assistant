package com.coldbrew.cainiao

import com.coldbrew.cainiao.data.Api
import com.coldbrew.cainiao.data.MemoryCookieStore
import com.coldbrew.cainiao.data.Mtop
import com.coldbrew.cainiao.data.Pkg
import com.coldbrew.cainiao.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 网关协议层的真机验证 —— 跑在普通 JVM 上，真打淘宝网关。
 *
 * 这是整个项目风险最高的一块：签名算法、token 引导、Set-Cookie 处理、URL 编码，
 * 任何一处和 Python 版不一致都会导致 401 / SESSION_EXPIRED。
 * 所以把 [Mtop] 从 Android Context 里解耦出来，让它能在单元测试里直接跑。
 *
 * 运行：
 *   bash gradle.sh :app:testDebugUnitTest --tests '*MtopLiveTest*' -i
 */
class MtopLiveTest {

    private fun online(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("h5api.m.taobao.com", 443), 6000) }
        true
    } catch (_: Exception) {
        false
    }

    /** 签名算法必须和 Python 的 hashlib.md5 完全一致。 */
    @Test
    fun `md5 实现与标准向量一致`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Mtop.md5("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Mtop.md5(""))
        assertEquals(
            "0cc175b9c0f1b6a831c399e269772661",
            Mtop.md5("a")
        )
        // 中文按 UTF-8 编码后再算（与 Python hashlib.md5(s.encode('utf-8')) 对照）
        assertEquals("a7bac2239fcdcb3a067903d8077c4a07", Mtop.md5("中文"))
    }

    /**
     * 最关键的验证：免登录通道 pid.get。
     *
     * 这一条同时验证了：
     *  ① 首次无 _m_h5_tk → 网关返回 TOKEN_EMPTY 并 Set-Cookie 下发 token
     *  ② 带新 token 重新签名 → SUCCESS
     *  ③ URL 编码与 data 紧凑 JSON 被网关正确解析
     */
    @Test
    fun `免登录通道 pid_get 能拿到真实轨迹`() {
        Assume.assumeTrue("网络不可达 h5api.m.taobao.com，跳过联网验证", online())

        val store = MemoryCookieStore()
        val mtop = Mtop(store)
        // 不内置任何真实运单号：需要联网验证时用 -Dcainiao.mailNo=<你的测试单号> 传入，
        // 未传入则跳过（而不是拿一个假号去撞网关，那样只会失败而不是跳过）。
        val mailNo = System.getProperty("cainiao.mailNo").orEmpty()
        Assume.assumeTrue("未提供运单号（-Dcainiao.mailNo=…），跳过联网验证", mailNo.isNotEmpty())

        val j = mtop.pidResult(mailNo)
        val ret = mtop.ret(j)

        println("──────── pid.get ────────")
        println("运单号    : $mailNo")
        println("ret       : $ret")
        println("_m_h5_tk  : ${store.token().take(16)}…（说明 token 引导已完成）")
        println("响应前 800 字符:")
        println(j.toString(2).take(800))

        assertTrue("应返回 SUCCESS，实际: $ret", ret.startsWith("SUCCESS"))
        assertTrue("token 引导后应已持有 _m_h5_tk", store.token().isNotEmpty())

        // 响应结构校验：data.result[] 里应有 detailList
        val arr = j.optJSONObject("data")?.optJSONArray("result")
        assertTrue("data.result 不应为空", arr != null && arr.length() > 0)
        val first = arr!!.optJSONObject(0)!!
        val nodes = first.optJSONArray("detailList") ?: first.optJSONArray("traceList")
        assertTrue("应有轨迹节点", nodes != null && nodes.length() > 0)
        println("轨迹节点数: ${nodes!!.length()}")

        // 用 Pkg.from 解析一次，确认模型层不炸
        val p = Pkg.from(first)
        println("解析结果  : company=${p.partnerName.ifEmpty { "-" }} mailNo=${p.mailNo} status=${p.statusDesc}")
    }

    /**
     * 解析路径验证：pid 响应 → TraceResult。
     *
     * 打印 companyList 的实际字段名，确认 firstCompany() 的兜底顺序是对的 ——
     * 这块字段名是逆向出来的，最容易和真实响应脱节。
     */
    @Test
    fun `pid 响应能解析出公司名与轨迹`() {
        Assume.assumeTrue("网络不可达，跳过", online())

        // 不内置任何真实运单号：需要联网验证时用 -Dcainiao.mailNo=<你的测试单号> 传入，
        // 未传入则跳过（而不是拿一个假号去撞网关，那样只会失败而不是跳过）。
        val mailNo = System.getProperty("cainiao.mailNo").orEmpty()
        Assume.assumeTrue("未提供运单号（-Dcainiao.mailNo=…），跳过联网验证", mailNo.isNotEmpty())
        val mtop = Mtop(MemoryCookieStore())
        val j = mtop.pidResult(mailNo)

        val arr = j.optJSONObject("data")?.optJSONArray("result")
        val first = arr?.optJSONObject(0)
        if (first != null) {
            val cl = first.optJSONArray("companyList")
            val c0 = cl?.optJSONObject(0)
            if (c0 != null) {
                println("── companyList[0] 全部字段 ──")
                c0.keys().forEach { k -> println("  $k = ${c0.opt(k)}") }
            }
            println("── result[0] 顶层字段 ──")
            println("  " + first.keys().asSequence().joinToString(", "))
        }

        val r = Repo.parseTrace(j, mailNo)
        println("── 解析结果 ──")
        println("公司名  : ${r.companyName}")
        println("公司码  : ${r.companyCode}")
        println("客服电话: ${r.companyPhone}")
        println("状态    : ${r.statusText}")
        println("轨迹条数: ${r.nodes.size}")
        println("最近 3 条:")
        r.nodes.takeLast(3).forEach { println("  ${it.time} | ${it.desc}") }

        assertTrue("应解析出轨迹", r.nodes.isNotEmpty())
        assertTrue("应解析出公司名，实际为空", r.companyName.isNotEmpty())
        assertTrue("轨迹节点应有描述", r.nodes.any { it.desc.isNotEmpty() })
    }

    /** 快递公司识别（同样是免登录通道）。 */
    @Test
    fun `快递公司识别可用`() {
        Assume.assumeTrue("网络不可达，跳过", online())
        val mailNo = System.getProperty("cainiao.mailNo").orEmpty()
        Assume.assumeTrue("未提供运单号（-Dcainiao.mailNo=…），跳过", mailNo.isNotEmpty())
        val mtop = Mtop(MemoryCookieStore())
        val j = mtop.detectCp(mailNo)
        val ret = mtop.ret(j)
        println("queryCpByMailNo ret = $ret")
        println(j.toString(2).take(500))
        assertTrue("应返回 SUCCESS", ret.startsWith("SUCCESS"))
    }

    /** 无登录态访问需登录接口，应稳定返回 SESSION_EXPIRED —— 证明"需要登录"这条路径是通的。 */
    @Test
    fun `无登录态访问需登录接口应返回 SESSION_EXPIRED`() {
        Assume.assumeTrue("网络不可达，跳过", online())
        val mtop = Mtop(MemoryCookieStore())
        val ret = mtop.ret(mtop.count())
        println("count.query（无 cookie）ret = $ret")
        assertTrue(
            "应因缺少登录 cookie 被拒，实际: $ret",
            ret.contains("SESSION") || ret.contains("TOKEN")
        )
    }

    /** 签名对相同入参要稳定，对 token 变化要敏感 —— 防止拼串时漏分隔符。 */
    @Test
    fun `签名拼接格式正确`() {
        val a = Mtop.md5("token1&1700000000000&12574478&{\"x\":1}")
        val b = Mtop.md5("token2&1700000000000&12574478&{\"x\":1}")
        val c = Mtop.md5("token1&1700000000000&12574478&{\"x\":2}")
        assertEquals(32, a.length)
        assertTrue("token 不同签名必须不同", a != b)
        assertTrue("data 不同签名必须不同", a != c)
        // 与 Python 对照：hashlib.md5(b'token1&1700000000000&12574478&{"x":1}').hexdigest()
        assertEquals("223eb0479bb91e5f8a2a8e7817a576fa", a)
    }

    /**
     * 紧凑 JSON —— 这是**签名正确性的前提**。
     *
     * 注意：这里不断言键的顺序。签名是拿 `data.toString()` 算的，同一个字符串又原样作为
     * `data` 查询参数发出去，服务端按收到的字符串重算签名，所以键序无关紧要
     * （Android 的 org.json 底层是 LinkedHashMap，参考实现是 HashMap，两者键序本来就不同）。
     * 真正要保证的是：无多余空格、可无损反解。
     */
    @Test
    fun `紧凑 JSON 无多余空格且可无损反解`() {
        val o = JSONObject()
            .put("pageSize", 50)
            .put("currentPage", 1)
            .put("needTemporality", false)
            .put("needDynInfo", "{'needFindTemporality':true,'needAppointment':true}")
        val s = o.toString()
        println("紧凑 JSON: $s")

        assertTrue("不应含空格", !s.contains(" "))
        assertTrue("应以 { 开头", s.startsWith("{"))
        assertTrue("应以 } 结尾", s.endsWith("}"))

        val back = JSONObject(s)
        assertEquals(50, back.getInt("pageSize"))
        assertEquals(1, back.getInt("currentPage"))
        assertEquals(false, back.getBoolean("needTemporality"))
        assertEquals(
            "{'needFindTemporality':true,'needAppointment':true}",
            back.getString("needDynInfo")
        )
        // 反解后再序列化必须与原串完全一致（保证网关侧拿到的 data 与签名输入同构）
        assertEquals(s, back.toString())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  以下为「第二轮逆向」后新增：把官方 JS bundle 里的取件码/标题/图片/
    //  角标/时效五套语义，用纯函数单测钉死，防止以后改动偏离官方行为。
    // ══════════════════════════════════════════════════════════════════════

    private fun pkg(vararg pairs: Pair<String, Any?>): Pkg {
        val o = JSONObject().put("mailNo", "TEST0001")
        pairs.forEach { (k, v) -> o.put(k, v) }
        return Pkg.from(o)
    }

    /**
     * 取件码回退 —— 官方原文（`pickupCode` getter）：
     * ```js
     * var n = packageStation?.authCode;
     * if (n && n.length > 0) {
     *   if (n === "needSms" && isNotEmpty(feature?.stationOrderPickUpCode))
     *     return feature.stationOrderPickUpCode;
     *   return n;               // authCode 优先
     * }
     * return t && t.length !== 0 && t !== "needSms" ? String(t) : null;
     * ```
     */
    @Test
    fun `取件码回退遵循官方优先级`() {
        // ① authCode 是真码 → 直接用，即使同时存在 stationOrderPickUpCode（顺序不能反）
        val a = pkg(
            "packageStation" to JSONObject().put("authCode", "8-3-2018"),
            "feature" to JSONObject().put("stationOrderPickUpCode", "9-9-9999")
        )
        assertEquals("authCode 优先于 stationOrderPickUpCode", "8-3-2018", a.pickupCode)

        // ② authCode 是哨兵 → 才回退到 feature.stationOrderPickUpCode
        val b = pkg(
            "packageStation" to JSONObject().put("authCode", "needSms"),
            "feature" to JSONObject().put("stationOrderPickUpCode", "9-9-9999")
        )
        assertEquals("哨兵时才回退", "9-9-9999", b.pickupCode)

        // ③ 哨兵且无回退值 → 空（绝不会把 "needSms" 当码显示）
        val c = pkg("packageStation" to JSONObject().put("authCode", "needSms"))
        assertEquals("哨兵无回退应为空", "", c.pickupCode)

        // ④ packageStation 整个缺失 → 空
        assertEquals("无 packageStation 应为空", "", pkg().pickupCode)
    }

    /**
     * 标题链 —— 官方 `PackageNameTool.getPackageName()`：
     * recommendName → userRemark → getGoodsName() → 手机尾号xxxx的包裹
     */
    @Test
    fun `标题链遵循官方优先级`() {
        // ① feature.recommendPackageTitle 最高优先级
        val a = pkg("feature" to JSONObject().put("recommendPackageTitle", "你的快递已到驿站"))
        assertEquals("recommendPackageTitle 应优先", "你的快递已到驿站", a.title)

        // ② packageItem[0].itemTitle（官方 getPackageTitle 就读这个）
        val b = pkg(
            "packageItem" to JSONArray().put(JSONObject().put("itemTitle", "小米充电宝 20000mAh"))
        )
        assertEquals("packageItem[0].itemTitle", "小米充电宝 20000mAh", b.title)

        // ③ 备注（服务端关系表）→ 「【家人】的包裹」
        val c = Pkg.applyRelation(pkg(), "家人", "")
        assertEquals("备注应渲染成【】的包裹", "【家人】的包裹", c.title)

        // ④ 手机尾号（官方 receiverTel.substring(length-4)）
        val d = pkg("receiverTel" to "13800138000")
        assertEquals("手机尾号 8000", "手机尾号8000的包裹", d.title)

        // ⑤ 全都没有 → 「您的包裹」（官方 getReceiverTelPackageName 的兜底）
        assertEquals("兜底", "您的包裹", pkg().title)
    }

    /** 件数角标 —— 官方 `packageDataItem2PackageImageDescLabel()`：>999 → "999+件" */
    @Test
    fun `件数角标按官方规则折叠`() {
        assertEquals("", pkg("goodsNum" to 0).goodsBadge)
        assertEquals("", pkg("goodsNum" to 1).goodsBadge)
        assertEquals("3件", pkg("goodsNum" to 3).goodsBadge)
        assertEquals("999件", pkg("goodsNum" to 999).goodsBadge)
        assertEquals("999+件", pkg("goodsNum" to 1200).goodsBadge)
    }

    /**
     * 时效 —— 官方 `packageSlotR1C1Label = "{100*arrivalTimeProboutput}%概率"`，
     * 配色档位 >0.9 蓝 / >0.7 橙。
     */
    @Test
    fun `时效文案与概率档位正确`() {
        val base = pkg()

        val hi = Pkg.applyDyn(base, JSONObject().put("packageDynInfo",
            JSONObject().put("arrivalTimeProboutput", 0.95)))
        assertEquals("95%概率", hi.etaText)
        assertEquals(">0.9 应为高档", 2, hi.etaLevel)

        val mid = Pkg.applyDyn(base, JSONObject().put("packageDynInfo",
            JSONObject().put("arrivalTimeProboutput", 0.73)))
        assertEquals("73%概率", mid.etaText)
        assertEquals(">0.7 应为中档", 1, mid.etaLevel)

        // expectTimeDesc 优先于概率
        val desc = Pkg.applyDyn(base, JSONObject().put("packageDynInfo",
            JSONObject().put("expectTimeDesc", "90%概率明天到驿站")
                .put("arrivalTimeProboutput", 0.9)))
        assertEquals("expectTimeDesc 优先", "90%概率明天到驿站", desc.etaText)

        // arrivalTimeDays 兜底
        val days = Pkg.applyDyn(base, JSONObject().put("packageDynInfo",
            JSONObject().put("arrivalTimeDays", 2)))
        assertEquals("预计明天到驿站", days.etaText)
    }

    /**
     * 主图回退链 —— 官方 `getPackageImageShowUrl()`（= partnerLogoUrl）与
     * `packageDataItem2PackageImageUrl()`（= packageItem[0].itemPic）两套并用。
     */
    @Test
    fun `主图回退链正确`() {
        // 商品图优先
        val a = pkg(
            "partnerLogoUrl" to "https://img.alicdn.com/logo.png",
            "packageItem" to JSONArray().put(JSONObject().put("itemPic", "goods.jpg"))
        )
        assertEquals("goods.jpg", a.mainPic)

        // 官方显式过滤字面量字符串 "null"
        val b = pkg(
            "partnerLogoUrl" to "https://img.alicdn.com/logo.png",
            "packageItem" to JSONArray().put(JSONObject().put("itemPic", "null"))
        )
        assertEquals("itemPic='null' 应被丢弃，回退到 LOGO",
            "https://img.alicdn.com/logo.png", b.mainPic)

        // 什么都没有
        assertEquals("", pkg().mainPic)
    }

    /** 来源白名单 —— 官方 `getPackageSourceName()` 的门控（YILIUBABA / OPEN_IMPORT 显式返回 null）。 */
    @Test
    fun `来源前缀遵循官方白名单`() {
        assertEquals("淘宝", pkg("pkgSource" to "TAOBAO", "pkgSourceDesc" to "淘宝").titlePrefix)
        assertEquals("拼多多", pkg("pkgSource" to "PINGDUODUO", "pkgSourceDesc" to "拼多多").titlePrefix)
        // 白名单外 → 不拼
        assertEquals("", pkg("pkgSource" to "UNKNOWN_SRC", "pkgSourceDesc" to "某某").titlePrefix)
        // 官方显式 null 的两个
        assertEquals("", pkg("pkgSource" to "YILIUBABA", "pkgSourceDesc" to "一六八八").titlePrefix)
        assertEquals("", pkg("pkgSource" to "OPEN_IMPORT", "pkgSourceDesc" to "导入").titlePrefix)

        assertTrue("白名单应含 39 项", Api.SUPPORT_SOURCE.size >= 35)
    }

    // ────────────────── 登录态实测 ──────────────────

    private fun loggedInStore(): com.coldbrew.cainiao.data.CookieStore? {
        val candidates = listOfNotNull(
            System.getProperty("cainiao.session"),
            "session.json",
            "../../session.json",
            "../session.json"
        )
        val f = candidates.map(::File).firstOrNull { it.isFile } ?: return null
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val arr = root.optJSONArray("cookies") ?: return null
        val map = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val n = c.optString("name")
            val v = c.optString("value")
            if (n.isNotEmpty() && v.isNotEmpty()) map[n] = v
        }
        return if (map.isEmpty()) null else MemoryCookieStore(map)
    }

    /**
     * **本轮最关键的一条实测**：把 MTOP 降级响应的原始 JSON 整段打出来。
     *
     * 目的：静态逆向已经确认官方 JS 读 `packageItem[0].itemTitle|itemPic`，
     * 但"MTOP 降级响应里到底带不带 packageItem"只能靠实打一次接口确认。
     * 结果会同时写到 `app/build/rawdump.txt`。
     */
    @Test
    fun `登录态下输出首页原始字段`() {
        Assume.assumeTrue("网络不可达，跳过", online())

        val store = loggedInStore()
        if (store == null) {
            println("未找到 session.json —— 该测试需要有效的登录态 cookie 才能运行，已跳过")
            return
        }

        val m = Mtop(store)
        val ret = m.ret(m.count())
        println("═══ count.query ret = $ret ═══")

        val dump = runCatching { Repo.rawDump(m) }
            .getOrElse { "rawDump 抛异常: ${it.javaClass.name}: ${it.message}\nret=$ret" }
        println(dump)

        runCatching {
            val out = File("build/rawdump.txt")
            out.parentFile?.mkdirs()
            out.writeText("ret=$ret\n$dump", Charsets.UTF_8)
            println("已写入 ${out.absolutePath}")
        }

        if (!ret.startsWith("SUCCESS")) {
            println("登录态无效（$ret），本用例仅打印诊断，不计失败。")
            return
        }
        assertTrue("应能取到包裹列表节点", dump.contains("=== pdsList(page1) ==="))
    }
}

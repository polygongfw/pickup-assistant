package com.coldbrew.cainiao

import com.coldbrew.cainiao.data.Account
import com.coldbrew.cainiao.data.NickCodec
import com.coldbrew.cainiao.data.Pkg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号合并去重 + 昵称解码的纯函数测试。
 *
 * 全部不需要 Android 环境（Pkg 只依赖 org.json，测试依赖里挂的是真实实现）。
 * 合并规则是整个多账号功能的正确性关键，必须锁住。
 */
class AccountMergeTest {

    private fun pkg(
        mailNo: String,
        nick: String,
        code: String = "",
        item: String = "",
        arrivalAt: Long = 0L
    ) = Pkg(
        mailNo = mailNo,
        statusDesc = "已到站",
        statusCode = "ARRIVAL",
        partnerName = "中通快递",
        partnerCode = "ZTO",
        sourceDesc = "",
        stationName = "测试驿站",
        stationId = "S1",
        stationPhone = "",
        officeTime = "",
        authCode = code,
        codeUsable = code.isNotEmpty(),
        lastDetail = "",
        arrivalAt = arrivalAt,
        signedDate = "",
        itemTitle = item,
        stationOrderPickUpCode = "",
        accountId = nick,
        accountNick = nick,
        raw = JSONObject()
    )

    // ─────────────────── 合并去重 ───────────────────

    @Test
    fun `同一运单号在多个账号下只保留一条`() {
        val mine = listOf(pkg("SF123", "我的号", code = "8-1-1234"))
        val wife = listOf(pkg("SF123", "家人的号"))
        val out = Pkg.merge(listOf(mine, wife))
        assertEquals(1, out.size)
    }

    @Test
    fun `合并时保留信息更全的那条`() {
        val bare = listOf(pkg("SF999", "家人的号"))
        val rich = listOf(pkg("SF999", "我的号", code = "9-9-9999", item = "牛奶一箱"))
        val out = Pkg.merge(listOf(bare, rich))
        assertEquals(1, out.size)
        assertEquals("9-9-9999", out[0].pickupCode)
        assertEquals("牛奶一箱", out[0].itemTitle)
    }

    @Test
    fun `两个账号都看得到时记录全部来源`() {
        val a = listOf(pkg("YT777", "我的号", code = "1-1-1"))
        val b = listOf(pkg("YT777", "家人的号", code = "1-1-1"))
        val out = Pkg.merge(listOf(a, b))
        assertEquals(1, out.size)
        assertTrue(out[0].sharedByNick.contains("我的号"))
        assertTrue(out[0].sharedByNick.contains("家人的号"))
    }

    @Test
    fun `各自独有的件不标共见`() {
        val a = listOf(pkg("JD001", "我的号", code = "2-2-2"))
        val b = listOf(pkg("JD002", "家人的号", code = "3-3-3"))
        val out = Pkg.merge(listOf(a, b))
        assertEquals(2, out.size)
        assertTrue(out.all { it.sharedByNick.isEmpty() })
    }

    @Test
    fun `没有运单号也没有 packageId 的件不会被丢掉`() {
        // 曾经这里直接 continue，等于把拿不到单号的包裹凭空吞掉（列表少一件就是这么来的）
        val a = listOf(pkg("", "我的号"))
        val b = listOf(pkg("", "家人的号"))
        val out = Pkg.merge(listOf(a, b))
        assertEquals(2, out.size)
    }

    @Test
    fun `没有运单号但有 packageId 时按 packageId 去重`() {
        val a = listOf(pkg("", "我的号").copy(packageId = "P100"))
        val b = listOf(pkg("", "家人的号").copy(packageId = "P100"))
        val out = Pkg.merge(listOf(a, b))
        assertEquals(1, out.size)
    }

    @Test
    fun `运单号大小写不同视为同一件`() {
        val a = listOf(pkg("sf100", "我的号", code = "1-1-1"))
        val b = listOf(pkg("SF100", "家人的号", code = "1-1-1"))
        val out = Pkg.merge(listOf(a, b))
        assertEquals(1, out.size)
    }

    @Test
    fun `合并结果按 到站 运输中 已签收 排序`() {
        val signed = pkg("S1", "我的号")
            .copy(statusCode = "SIGN", signedDate = "2026-09-30")
        val arriving = pkg("S2", "我的号", code = "1-2-3")
        val out = Pkg.merge(listOf(listOf(signed), listOf(arriving)))
        assertEquals(2, out.size)
        assertEquals(0, out[0].group)
        assertEquals(2, out[1].group)
    }

    @Test
    fun `三个账号合并去重后条数正确`() {
        val a = listOf(pkg("M1", "我", code = "1"), pkg("M2", "我", code = "2"))
        val b = listOf(pkg("M2", "家人", code = "2"), pkg("M3", "家人", code = "3"))
        val c = listOf(pkg("M3", "妈", code = "3"), pkg("M4", "妈", code = "4"))
        val out = Pkg.merge(listOf(a, b, c))
        // M1..M4 去重后 4 条；M2 与 M3 各出现两次
        assertEquals(4, out.size)
        assertEquals(2, out.count { it.sharedByNick.isNotEmpty() })
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(Pkg.merge(emptyList()).isEmpty())
        assertTrue(Pkg.merge(listOf(emptyList())).isEmpty())
    }

    // ─────────────────── 待取 / 签收 判定 ───────────────────

    @Test
    fun `signedDate 为占位值 false 时不判为已签收`() {
        // 接口对未签收件可能回 false / 0；str() 会 toString 成 "false" / "0"，非空 →
        // 曾经因此把待取件误判成已签收，表现就是「明明有一件待取，列表说无待取」
        val p = pkg("SF1", "我的号", code = "1-1-1").copy(signedDate = "false")
        assertTrue(!p.signed)
        assertEquals(0, p.group)
    }

    @Test
    fun `signedDate 为 0 时不判为已签收`() {
        val p = pkg("SF2", "我的号", code = "2-2-2").copy(signedDate = "0")
        assertTrue(!p.signed)
        assertEquals(0, p.group)
    }

    @Test
    fun `真签收件 SIGN 且无取件码进签收组`() {
        val p = pkg("SF3", "我的号")
            .copy(statusCode = "SIGN", signedDate = "2026-09-30 12:00:00")
        assertTrue(p.signed)
        assertEquals(2, p.group)
    }

    @Test
    fun `驿站代收的件即使状态被标成 AGENT_SIGN 也进到站组`() {
        // 线上 BUG 的回归用例：快递把件交给驿站时 logisticsStatus 就是 AGENT_SIGN，
        // 但件其实在驿站货架上等取（还带着取件码）。
        // 官方 isSigned / isAgentSign 是两套判定，只有 isSigned 才叫「已签收」。
        val agent = pkg("SF5", "我的号", code = "P-1-12345").copy(statusCode = "AGENT_SIGN")
        assertTrue(agent.pending)
        assertEquals(0, agent.group)
    }

    @Test
    fun `带取件码的件即使状态是 SIGN 也进到站组`() {
        // pending 优先级必须高于 signed —— 这是本次修复的核心
        val p = pkg("SF6", "我的号", code = "U-2-12345").copy(statusCode = "SIGN")
        assertTrue(p.pending)
        assertEquals(0, p.group)
    }

    @Test
    fun `有取件码的件一定进到站分组`() {
        val p = pkg("SF4", "我的号", code = "U-2-12345")
        assertTrue(p.pending)
        assertEquals(0, p.group)
    }

    // ─────────────────── 昵称解码 ───────────────────

    @Test
    fun `昵称 UTF8 转义可解码`() {
        assertEquals("家人", NickCodec.decode("%E5%AE%B6%E4%BA%BA"))
    }

    @Test
    fun `昵称 GBK 转义可解码`() {
        // 阿里 _nk_ 历史遗留是 GBK，这里是最容易踩的坑
        assertEquals("家人", NickCodec.decode("%BC%D2%C8%CB"))
    }

    @Test
    fun `昵称明文直接返回`() {
        assertEquals("家人的号", NickCodec.decode("家人的号"))
    }

    @Test
    fun `加号按空格处理`() {
        assertEquals("a b", NickCodec.decode("a+b"))
    }

    @Test
    fun `空串解码为空串`() {
        assertEquals("", NickCodec.decode(""))
    }

    // ─────────────────── 账号展示 ───────────────────

    @Test
    fun `账号显示名优先用自定义备注`() {
        val a = Account(id = "a1", nick = "tb123456", customName = "家人的号")
        assertEquals("家人的号", a.displayName)
        assertEquals("家", a.initial)
    }

    @Test
    fun `账号没有昵称时显示兜底文案`() {
        val a = Account(id = "a1")
        assertEquals("淘宝账号", a.displayName)
        assertEquals("淘", a.initial)
    }
}

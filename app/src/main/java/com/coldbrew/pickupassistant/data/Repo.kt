package com.coldbrew.pickupassistant.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 业务门面：把 MTOP 原始响应整理成界面能直接吃的结构。所有方法都跑在 IO 线程。 */
object Repo {

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ─────────────── 登录态 ───────────────

    /** 用 count 接口自检登录态，返回原始 ret 字符串（SUCCESS 开头即有效）。 */
    suspend fun verify(context: Context): String =
        io { ret(AccountStore.primaryStore(context)) }

    /** 自检指定账号的登录态。 */
    suspend fun verify(context: Context, accountId: String): String =
        io { ret(AccountStore.store(context, accountId)) }

    private fun ret(store: CookieStore): String = Mtop(store).let { it.ret(it.count()) }

    // ─────────────── 我的包裹（单账号，主账号） ───────────────

    /**
     * 我的包裹 —— **翻页取全**（不是只拉第一页）。
     *
     * 曾经按"官方降级路径固定 pageSize=40 不翻页"来截断，结果总包裹数超过一页时，
     * 排在后面的件（包括待取件）会被静默丢掉 —— 表现就是「明明有一件待取，列表说无待取」。
     * 官方之所以敢截断，是因为它的正常路径读本地 Dorado 库（全量），MTOP 只是兜底；
     * 我们只有 MTOP 这一条路，必须自己翻页。
     */
    suspend fun packages(context: Context): List<Pkg> =
        io { Mtop(AccountStore.primaryStore(context)).allPackages().map { Pkg.from(it) } }

    /** 与 [packages] 等价（保留旧名，语义已统一为"全量"）。 */
    suspend fun allPackages(context: Context): List<Pkg> = packages(context)

    suspend fun pending(context: Context): List<Pkg> =
        packages(context).filter { it.pending }

    // ─────────────── 我的包裹（多账号聚合） ───────────────

    /**
     * 多账号聚合结果。
     *
     * @param pkgs      合并去重后的包裹列表（已排序）
     * @param okAccounts 成功返回的账号
     * @param failed    失败的账号 → 错误码（不影响其他账号）
     * @param rawCount  合并前各账号条数之和（用于展示「去掉了多少重复」）
     */
    data class Agg(
        val pkgs: List<Pkg>,
        val okAccounts: List<Account>,
        val failed: List<Pair<Account, String>>,
        val rawCount: Int
    ) {
        val dupRemoved: Int get() = (rawCount - pkgs.size).coerceAtLeast(0)
    }

    /**
     * 首页多账号一站式：并发拉取所有启用账号 → 合并去重。
     *
     * 为什么并发：账号之间 cookie 独立，服务端限流按会话维度算；
     * 串行拉 3 个账号要 3 倍时间，体验上完全是两回事。
     * 单个账号失败只记进 [Agg.failed]，绝不连坐。
     */
    suspend fun homeAll(context: Context): Agg = withContext(Dispatchers.IO) {
        val accounts = AccountStore.active(context)
        if (accounts.isEmpty()) {
            return@withContext Agg(emptyList(), emptyList(), emptyList(), 0)
        }

        val results = coroutineScope {
            accounts.map { acc ->
                async {
                    try {
                        val store = AccountStore.store(context, acc.id)
                        acc to Result.success(homeWith(store))
                    } catch (e: MtopException) {
                        acc to Result.failure(e)
                    } catch (e: Exception) {
                        acc to Result.failure(e)
                    }
                }
            }.map { it.await() }
        }

        val ok = ArrayList<Account>()
        val failed = ArrayList<Pair<Account, String>>()
        val groups = ArrayList<List<Pkg>>()
        var raw = 0

        for ((acc, r) in results) {
            val list = r.getOrNull()
            if (list == null) {
                val e = r.exceptionOrNull()
                failed.add(acc to ((e as? MtopException)?.ret ?: "NETWORK::${e?.javaClass?.simpleName}"))
            } else {
                ok.add(acc)
                raw += list.size
                groups.add(Pkg.tagAccount(list, acc.id, acc.displayName))
            }
        }

        Agg(Pkg.merge(groups), ok, failed, raw)
    }

    /**
     * 用 querypackagedynlist 批量补时效（「90%概率明天到驿站」）。
     *
     * 分批 20 个 id 一次，失败静默降级（时效拿不到不影响主列表）。
     */
    suspend fun attachDyn(context: Context, pkgs: List<Pkg>): List<Pkg> =
        io { attachDynWith(Mtop(AccountStore.primaryStore(context)), pkgs) }

    /**
     * 亲友备注表（【家人】的）。
     *
     * 逆向结论：备注**是服务端数据**（`guoguouser.br.relation.list` 的 result[]），
     * 官方读到本地 sharedTable 后按 `encryReceiverTel` 索引。我们直接读服务端。
     */
    suspend fun relations(context: Context): Map<String, Pair<String, String>> =
        io { relationsWith(Mtop(AccountStore.primaryStore(context))) }

    /** 一站式（单账号，主账号）：列表 + 时效 + 备注。 */
    suspend fun home(context: Context): List<Pkg> =
        io { homeWith(AccountStore.primaryStore(context)) }

    private fun homeWith(store: CookieStore): List<Pkg> {
        val m = Mtop(store)
        val list = m.allPackages().map { Pkg.from(it) }
        val withDyn = runCatching { attachDynWith(m, list) }.getOrElse { list }
        val rel = runCatching { relationsWith(m) }.getOrDefault(emptyMap())
        return applyRelations(withDyn, rel)
    }

    private fun attachDynWith(m: Mtop, pkgs: List<Pkg>): List<Pkg> {
        // 时效只对「运输中/到站」有意义，已签收的不用问，省掉一批请求
        val need = pkgs.filter { it.packageId.isNotEmpty() && it.group != 2 }
        if (need.isEmpty()) return pkgs

        val byId = HashMap<String, JSONObject>()
        need.chunked(20).forEach { chunk ->
            val dyn = m.dynList(chunk.map { it.packageId })
            dyn.forEach { o ->
                val id = Pkg.str(o, "packageId", "package_id", "id")
                if (id.isNotEmpty()) byId[id] = o
            }
        }
        if (byId.isEmpty()) return pkgs
        return pkgs.map { p ->
            val d = byId[p.packageId]
            if (d == null) p else Pkg.applyDyn(p, d)
        }
    }

    private fun relationsWith(m: Mtop): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        m.relationList().forEach { r ->
            val mobile = Pkg.str(r, "mobile", "relateMobile", "relate_mobile", "phone")
            val remark = Pkg.str(r, "remark", "relateRemark", "relate_remark", "alias")
            val tag = Pkg.str(r, "tag", "relateTag", "relate_tag")
            if (mobile.isNotEmpty()) out[mobile] = remark to tag
        }
        return out
    }

    /** 把关系表贴到包裹上（按明文手机号或加密手机号匹配）。 */
    fun applyRelations(
        pkgs: List<Pkg>,
        rel: Map<String, Pair<String, String>>
    ): List<Pkg> {
        if (rel.isEmpty()) return pkgs
        return pkgs.map { p ->
            val hit = rel[p.receiverTel] ?: rel[p.encryReceiverTel]
            if (hit == null) p else Pkg.applyRelation(p, hit.first, hit.second)
        }
    }

    /**
     * 诊断用：把原始响应整段吐出来。
     *
     * 存在的意义：`packageItem`（商品图/标题）是唯一没法静态确认的字段 ——
     * 官方 JS 明确读 `packageItem[0].itemTitle|itemPic`（`getPackageTitle`/`getPackagePic`），
     * 但只有实打一次接口才能看到 MTOP 降级响应里到底带没带。登录后跑一次就知道。
     */
    suspend fun rawDump(context: Context, accountId: String? = null): String = io {
        val store = if (accountId.isNullOrEmpty()) AccountStore.primaryStore(context)
        else AccountStore.store(context, accountId)
        rawDump(Mtop(store))
    }

    /** [rawDump] 的纯 JVM 版本（不依赖 Android Context），供单元测试直接注入 cookie 调用。 */
    internal fun rawDump(m: Mtop): String {
        val sb = StringBuilder()
        sb.append("=== pdsList(page1) ===\n")
        val (pkgs, meta) = m.pdsList(page = 1)
        sb.append("meta=").append(meta).append("\ncount=").append(pkgs.size).append("\n")
        if (pkgs.isNotEmpty()) {
            val p0 = pkgs[0]
            sb.append("首件字段清单: ").append(p0.keys().asSequence().toList()).append("\n")
            sb.append("packageItem 存在? ").append(p0.has("packageItem")).append("\n")
            sb.append("packageStation 存在? ").append(p0.has("packageStation")).append("\n")
            sb.append("feature 存在? ").append(p0.has("feature")).append("\n")
            sb.append("\n--- 首件原始 JSON ---\n").append(p0.toString(2)).append("\n")
        }

        val ids = pkgs.map { Pkg.str(it, "packageId", "package_id") }.filter { it.isNotEmpty() }
        sb.append("\n=== dynList(ids=").append(ids.size).append(") ===\n")
        val dyn = m.dynList(ids.take(20))
        sb.append("result count=").append(dyn.size).append("\n")
        if (dyn.isNotEmpty()) {
            sb.append("dyn 首件字段: ").append(dyn[0].keys().asSequence().toList()).append("\n")
            sb.append(dyn[0].toString(2)).append("\n")
        }

        sb.append("\n=== relationList ===\n")
        val rel = m.relationList()
        sb.append("result count=").append(rel.size).append("\n")
        if (rel.isNotEmpty()) {
            sb.append("relation 首件字段: ").append(rel[0].keys().asSequence().toList()).append("\n")
            sb.append(rel[0].toString(2)).append("\n")
        }
        return sb.toString()
    }

    // ─────────────── 驿站 ───────────────

    data class Station(
        val id: String,
        val name: String,
        val address: String,
        val brand: String,
        val servedCount: Int,
        val raw: JSONObject
    )

    suspend fun stations(context: Context): List<Station> = io {
        val j = Mtop(AccountStore.primaryStore(context)).stations()
        val arr = j.optJSONObject("data")?.optJSONArray("result")
            ?: j.optJSONObject("data")?.optJSONArray("list")
            ?: JSONArray()
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Station(
                    id = Pkg.str(o, "id", "stationId"),
                    name = Pkg.str(o, "name", "stationName"),
                    address = Pkg.str(o, "address"),
                    brand = Pkg.str(o, "siteBrandName", "brandName"),
                    servedCount = o.optInt("pkgAmount", 0),
                    raw = o
                )
            )
        }
        out
    }

    // ─────────────── 查快递（免登录） ───────────────

    data class TraceNode(val time: String, val status: String, val desc: String)

    data class TraceResult(
        val mailNo: String,
        val companyName: String,
        val companyCode: String,
        val companyPhone: String,
        val statusText: String,
        val nodes: List<TraceNode>
    )

    suspend fun queryExpress(context: Context, mailNo: String): TraceResult = io {
        val m = Mtop(AccountStore.primaryStore(context))

        // 主通道 pid.get：免登录、不触发滑块
        val j = m.pidResult(mailNo)
        var ret = m.ret(j)
        if (!ret.startsWith("SUCCESS")) {
            // 降级：先识别公司，再走 trace
            var cp = ""
            try {
                val cj = m.detectCp(mailNo)
                val r0 = cj.optJSONObject("data")?.optJSONArray("result")?.optJSONObject(0)
                cp = if (r0 != null) Pkg.str(r0, "tpCode") else ""
            } catch (_: Exception) {
                // 识别失败就用空 cp 再试一次
            }
            val t = m.trace(mailNo, cp.ifEmpty { null })
            ret = m.ret(t)
            if (!ret.startsWith("SUCCESS")) throw MtopException(ret)
            return@io parseTrace(t, mailNo)
        }
        parseTrace(j, mailNo)
    }

    /** 可见性放宽到 internal 是为了让单元测试能直接验证解析路径（见 MtopLiveTest）。 */
    internal fun parseTrace(j: JSONObject, mailNo: String): TraceResult {
        val data = j.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("result")
        val pkg = if (arr != null && arr.length() > 0) arr.optJSONObject(0) ?: JSONObject() else data

        val company = firstCompany(pkg)
        val nodes = ArrayList<TraceNode>()
        val list = pkg.optJSONArray("detailList")
            ?: pkg.optJSONArray("traceList")
            ?: pkg.optJSONArray("transitList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                nodes.add(
                    TraceNode(
                        time = Pkg.str(o, "time", "ocurrTimeStr", "occurTimeStr", "gmtCreate", "statusTime"),
                        status = Pkg.str(o, "status", "action", "statusCode"),
                        desc = Pkg.str(o, "standerdDesc", "statusDesc", "desc", "content", "action")
                    )
                )
            }
        }
        // pid 的 status 是嵌套对象；trace 的 statusDesc 是平铺字符串。两套都兜住，
        // 再退到"最后一条轨迹的描述"。
        val statusText = Pkg.str(pkg, "statusDesc", "logisticsStatusDesc")
            .ifEmpty { pkg.optJSONObject("status")?.let { Pkg.str(it, "statusDesc", "statusCode") } ?: "" }
            .ifEmpty { nodes.lastOrNull()?.desc ?: "" }

        return TraceResult(
            mailNo = Pkg.str(pkg, "mailNo").ifEmpty { mailNo },
            companyName = company.first,
            companyCode = company.second,
            companyPhone = company.third,
            statusText = statusText,
            nodes = nodes
        )
    }

    /** 兼容 trace 的 cp/cpList 与 pid 的 companyList 两套公司字段。 */
    internal fun firstCompany(pkg: JSONObject): Triple<String, String, String> {
        val cp = pkg.optJSONObject("cp")
        if (cp != null) {
            val n = Pkg.str(cp, "tpName", "brandCode")
            if (n.isNotEmpty()) {
                return Triple(n, Pkg.str(cp, "tpCode", "brandCode"), Pkg.str(cp, "tpContact"))
            }
        }
        val cl = pkg.optJSONArray("companyList")
        if (cl != null && cl.length() > 0) {
            val c = cl.optJSONObject(0)
            if (c != null) {
                var phone = Pkg.str(c, "companyContact", "phone", "cpPhone", "tel")
                if (phone.isEmpty()) {
                    val ccl = c.optJSONArray("companyContactList")
                    if (ccl != null && ccl.length() > 0) {
                        phone = Pkg.str(ccl.optJSONObject(0), "phone")
                    }
                }
                return Triple(
                    Pkg.str(c, "companyName", "cpName", "tpName"),
                    Pkg.str(c, "resCode", "brandCode", "cpCode", "tpCode"),
                    phone
                )
            }
        }
        val code = Pkg.str(pkg, "brandCodeOrResCode")
        return Triple(code, code, "")
    }

    /** 把 MTOP 的错误码翻译成人话。 */
    fun humanError(ret: String): String {
        val code = ret.substringBefore("::")
        return when {
            code.contains("SESSION_EXPIRED") || code.contains("SESSION") ->
                "登录态已失效，需要重新登录"
            code.contains("TOKEN_EMPTY") || code.contains("TOKEN_EX") || code.contains("TOKEN_EXO") ->
                "网关握手失败，请重试"
            code.contains("TRAFFIC_LIMIT") ->
                "操作太频繁，稍后再试"
            code.contains("USER_VALIDATE") ->
                "触发了风控校验，请稍后再试"
            code.contains("UNAUTHORIZED_ENTRANCE") ->
                "接口通道未注册"
            code.contains("BIZPARAM_MISSED") ->
                "请求参数不完整"
            code.contains("NETWORK") ->
                "网络异常，请检查连接"
            else -> "请求失败：$ret"
        }
    }
}

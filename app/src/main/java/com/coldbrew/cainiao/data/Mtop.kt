package com.coldbrew.cainiao.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 网关返回非 SUCCESS 时抛出，携带原始 ret 便于界面给出针对性提示。 */
class MtopException(val ret: String) : Exception(ret)

/**
 * MTOP H5 网关客户端（纯 JDK，无第三方依赖）。
 *
 * 移植自 `cainiao_h5_client.py` 的 MtopH5，行为逐条对齐：
 *  · token 引导：无 _m_h5_tk 时首包必然 TOKEN_EMPTY，网关 Set-Cookie 下发后重试即通
 *  · 签名：md5(token & t & appKey & data)，data 用紧凑 JSON
 *  · token 过期两种拼写都要覆盖（网关实际是 TOKEN_EXOIRED，漏了 P）
 *  · TRAFFIC_LIMIT 退避
 *  · 每次成功调用后把刷新过的 _m_h5_tk 落盘
 */
class Mtop(private val store: CookieStore) {

    // ────────────────────────── 底层 ──────────────────────────

    fun ret(j: JSONObject): String {
        val arr: JSONArray? = j.optJSONArray("ret")
        return arr?.optString(0) ?: ""
    }

    fun isSuccess(j: JSONObject): Boolean = ret(j).startsWith("SUCCESS")

    /**
     * 调用一个 MTOP 接口。
     * @param api 完整 api 名（h5 通道用不带 .cn 后缀的形式）
     * @return 原始响应 JSON；重试耗尽时返回最后一次响应
     */
    fun call(api: String, data: JSONObject, version: String = "1.0", retries: Int = 5): JSONObject {
        val ds = data.toString()
        var last: JSONObject? = null

        for (i in 0 until retries) {
            val t = System.currentTimeMillis().toString()
            val sign = md5(store.token() + "&" + t + "&" + Api.APP_KEY + "&" + ds)
            val query = buildString {
                append("jsv=2.7.4")
                append("&appKey=").append(Api.APP_KEY)
                append("&t=").append(t)
                append("&sign=").append(sign)
                append("&api=").append(URLEncoder.encode(api, "UTF-8"))
                append("&v=").append(URLEncoder.encode(version, "UTF-8"))
                append("&type=originaljson")
                append("&dataType=json")
                append("&data=").append(URLEncoder.encode(ds, "UTF-8"))
            }
            val url = "${Api.HOST}/h5/${api.lowercase()}/$version/?$query"

            val body = try {
                httpGet(url)
            } catch (_: Exception) {
                sleep(1000L * (i + 1))
                continue
            }

            val j = try {
                JSONObject(body)
            } catch (_: Exception) {
                sleep(1000L * (i + 1))
                continue
            }
            last = j

            val r = ret(j)
            val upper = r.uppercase()

            if (Api.RETRY_MARKERS.any { upper.contains(it) }) {
                // cookie 已被网关刷新，稍等后带新 token 重试
                sleep(400L + (Math.random() * 600).toLong())
                continue
            }
            if (upper.contains("TRAFFIC_LIMIT") || body.take(300).contains("punish")) {
                sleep(5000L + i * 5000L)
                continue
            }
            store.persist()
            return j
        }
        return last ?: JSONObject().put("ret", JSONArray().put("CLIENT::RETRY_EXHAUSTED"))
    }

    /** 调用并要求 SUCCESS，否则抛 MtopException。 */
    fun callOrThrow(api: String, data: JSONObject, version: String = "1.0"): JSONObject {
        val j = call(api, data, version)
        if (!isSuccess(j)) throw MtopException(ret(j))
        return j
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Api.UA)
            conn.setRequestProperty("Referer", Api.REFERER)
            conn.setRequestProperty("Accept", "application/json")

            val ck = store.cookieHeader()
            if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            conn.headerFields.forEach { (key, values) ->
                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    values?.forEach { absorbSetCookie(it) }
                }
            }
            return body
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 解析单条 Set-Cookie 并存入会话。
     *
     * 规则统一在 [Cookies.absorb] —— 扫码登录那条链路也用同一套，
     * 两处各写一份的话「扫码建好了会话、MTOP 却读不到」会很难查。
     */
    private fun absorbSetCookie(raw: String) = Cookies.absorb(store, raw)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ────────────────────── 业务封装 ──────────────────────

    /** 登录态自检：各状态包裹计数。SUCCESS 即登录态有效。 */
    fun count(): JSONObject = call(Api.COUNT, JSONObject().put("appName", "GUOGUO"))

    /** 我的包裹列表（含取件码）。返回 (packages, 分页信息)。 */
    fun pdsList(page: Int = 1, size: Int = Api.PAGE_SIZE): Pair<List<JSONObject>, JSONObject> {
        val d = JSONObject()
            .put("pageSize", size)
            .put("currentPage", page)
            .put("needTemporality", false)
            .put("needDynInfo", Api.NEED_DYN_INFO_LIST)

        val j = call(Api.PDS_LIST, d)
        val r = ret(j)
        if (!r.startsWith("SUCCESS")) throw MtopException(r)

        val data = j.optJSONObject("data") ?: JSONObject()
        val inner = data.optJSONObject("data") ?: data
        val arr = inner.optJSONArray("packages") ?: JSONArray()
        val list = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }

        val meta = JSONObject()
            .put("currentPage", inner.optInt("currentPage", 1))
            .put("pageSize", inner.optInt("pageSize", size))
            .put("totalPage", inner.optInt("totalPage", 1))
        return list to meta
    }

    /**
     * 只拉第 1 页（诊断 / 快速探测用）。
     *
     * ⚠ **不要**用它当首页数据源。曾经误以为"官方降级路径就是只拉 40 条"而在这里截断，
     * 结果包裹总数超过一页时后面的件（含待取件）会被静默丢掉 —— 见 `Api.PAGE_SIZE` 注释。
     * 首页请用 [allPackages]。
     */
    fun firstPage(size: Int = Api.PAGE_SIZE): List<JSONObject> = pdsList(page = 1, size = size).first

    /**
     * 翻页取回**全部**包裹。
     *
     * 终止条件靠响应里的 `totalPage`（和 Python 版 `all_packages` 一致），
     * 单页失败重试 3 次而不是静默丢数据 —— 少一页就等于少一批取件码。
     */
    fun allPackages(size: Int = Api.PAGE_SIZE, maxPages: Int = Api.MAX_PAGES): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = 1
        while (page <= maxPages) {
            var pkgs: List<JSONObject>? = null
            var meta = JSONObject()
            var lastErr = ""
            for (attempt in 0 until 3) {
                try {
                    val (p, m) = pdsList(page = page, size = size)
                    pkgs = p
                    meta = m
                    break
                } catch (e: MtopException) {
                    lastErr = e.ret
                    // 登录态问题重试没意义，立刻冒泡
                    if (e.ret.contains("SESSION")) throw e
                    sleep(3000L + attempt * 4000L)
                }
            }
            if (pkgs == null) throw MtopException(lastErr.ifEmpty { "CLIENT::PAGE_FAILED" })

            out.addAll(pkgs)
            val total = meta.optInt("totalPage", 1)
            if (page >= total || pkgs.isEmpty()) break
            page++
            sleep(1200)
        }
        return out
    }

    /**
     * 时效/预计到达：按 packageIdList 批量补 packageDyn。
     *
     * 逐字对齐官方调用（本地 JS bundle 实证）：
     *   url   mtop.cainiao.lpc.packageservice.querypackagedynlist.cn
     *   param { querySourceId, packageIdList: JSON.stringify(ids),
     *           needTemporality: "true", needDynInfo: JSON.stringify({...}) }
     * 响应取 `data.result`（数组）。
     */
    fun dynList(
        packageIds: List<String>,
        querySourceId: String = Api.SRC_PICKUP_LIST
    ): List<JSONObject> {
        if (packageIds.isEmpty()) return emptyList()
        val arr = JSONArray()
        packageIds.forEach { arr.put(it) }

        val d = JSONObject()
            .put("querySourceId", querySourceId)
            .put("packageIdList", arr.toString())
            .put("needTemporality", "true")
            .put("needDynInfo", Api.NEED_DYN_INFO)

        val j = call(Api.DYNLIST, d)
        if (!isSuccess(j)) return emptyList()   // 时效是锦上添花，失败不连累主流程
        return extractArray(j)
    }

    /** 亲友关系列表：response.result[]，含 remark / tag / mobile / relateUserId。 */
    fun relationList(): List<JSONObject> {
        val j = call(Api.RELATION_LIST, JSONObject())
        if (!isSuccess(j)) return emptyList()
        return extractArray(j)
    }

    /** 备注可选标签。 */
    fun relationTagList(): JSONObject {
        val j = call(Api.RELATION_TAG_LIST, JSONObject())
        return j.optJSONObject("data") ?: j
    }

    /** 给某个关系写备注。 */
    fun saveRelationRemark(
        mobile: String,
        relateUserId: String,
        remark: String,
        relationType: String = "1"
    ): JSONObject = call(
        Api.RELATION_SAVE_REMARK,
        JSONObject()
            .put("remark", remark)
            .put("mobile", mobile)
            .put("relateUserId", relateUserId)
            .put("relationType", relationType)
    )

    /**
     * 容错地从响应里取数组：依次尝试 data.result / data.data.result /
     * data.data.data.result / data.packages / data.list（官方各处写法不一）。
     */
    private fun extractArray(j: JSONObject): List<JSONObject> {
        val d1 = j.optJSONObject("data")
        val d2 = d1?.optJSONObject("data")
        val d3 = d2?.optJSONObject("data")
        val arr = d1?.optJSONArray("result")
            ?: d2?.optJSONArray("result")
            ?: d3?.optJSONArray("result")
            ?: j.optJSONArray("result")
            ?: d1?.optJSONArray("list")
            ?: d1?.optJSONArray("packages")
        val out = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    /** 我的驿站列表。注意 pkgAmount = 历史累计服务次数，不是待取件数。 */
    fun stations(): JSONObject = call(Api.STATIONS, JSONObject())

    /** 取件身份码（驿站自助取件，需已开通）。 */
    fun identityCode(): JSONObject = call(
        Api.IDENTITY_CODE,
        JSONObject().put("appName", "GUOGUO").put("verifyVersion", "V2")
    )

    /** 按运单号批量生成取件码（需登录 + stationId）。 */
    fun batchCode(mailNos: List<String>, stationId: String, type: Int = 1): JSONObject {
        val arr = JSONArray()
        mailNos.forEach { arr.put(it) }
        return call(
            Api.BATCH_CODE,
            JSONObject()
                .put("mailNoList", arr)
                .put("stationId", stationId)
                .put("type", type)
                .put("extendParam", Api.BATCH_CODE_EXT)
        )
    }

    // ── 免登录查快递 ──

    fun pidResult(mailNo: String, orderCode: String? = null): JSONObject {
        val d = JSONObject().put("mailNo", mailNo)
        if (!orderCode.isNullOrEmpty()) d.put("orderCode", orderCode)
        return call(Api.PID, d)
    }

    fun detectCp(mailNo: String): JSONObject =
        call(Api.CP_BY_MAILNO, JSONObject().put("mailNo", mailNo))

    fun trace(mailNo: String, cpCode: String? = null): JSONObject {
        val d = JSONObject()
            .put("mailNo", mailNo)
            .put("actor", "RECEIVER")
            .put("isShowAllDetail", true)
            .put("isShowExceptionDetail", true)
            .put("isShowExpressMan", true)
            .put("isShowConsignDetail", true)
            .put("isShowDeliveryProgress", true)
            .put("isShowStationProxyOrder", true)
            .put("isShowServiceProvider", true)
            .put("isStandard", true)
            .put("isUnique", true)
            .put("ignoreInvalidNode", true)
        if (!cpCode.isNullOrEmpty()) d.put("cpCode", cpCode)
        return call(Api.TRACE, d)
    }

    companion object {
        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (b in d) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }
    }
}

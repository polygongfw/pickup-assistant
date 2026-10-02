package com.coldbrew.pickupassistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 包裹视图模型。
 *
 * 字段映射与 Python 版 `print_pds` / `_pkg_is_pending` 保持一致：
 *  待取判定 = 取件码可用（非占位值） || (有 stationId 且 状态不在终态集合)
 */
data class Pkg(
    val mailNo: String,
    val statusDesc: String,
    val statusCode: String,
    val partnerName: String,
    val partnerCode: String,
    val sourceDesc: String,
    val stationName: String,
    val stationId: String,
    val stationPhone: String,
    val officeTime: String,
    val authCode: String,
    val codeUsable: Boolean,
    val lastDetail: String,
    val arrivalAt: Long,
    val signedDate: String,
    // ── 以下为逆向官方 APK 后新增（字段名全部来自本地 JS bundle 实证）──
    /** 包裹服务端主键，querypackagedynlist 要用 */
    val packageId: String = "",
    /** packageItem[0].itemTitle —— 商品标题，官方首页卡片直接展示 */
    val itemTitle: String = "",
    /** packageItem[0].itemPic —— 商品缩略图 */
    val itemPic: String = "",
    /** 商品件数 */
    val goodsNum: Int = 0,
    /** 收件人手机号（明文，末 4 位用于「手机尾号XXXX的包裹」） */
    val receiverTel: String = "",
    /** 加密手机号，本地关系表按它索引 */
    val encryReceiverTel: String = "",
    /** 代收类型，官方枚举：0=菜鸟驿站 / 4=溪鸟 */
    val stationDaishouType: String = "",
    /** feature.stationOrderPickUpCode：authCode 为哨兵值时的真实取件码 */
    val stationOrderPickUpCode: String = "",
    /** packageDyn.packageDynInfo.expectTimeDesc —— 「90%概率明天到驿站」 */
    val expectTimeDesc: String = "",
    /** packageDyn.packageDynInfo.arrivalTimeDays —— 1=今天 2=明天 */
    val arrivalTimeDays: Int = 0,
    /** packageDyn.packageDynInfo.predictDateProd */
    val predictDateProd: String = "",
    /** 关系表：备注（「家人」类关系备注用的是这个） */
    val relationRemark: String = "",
    /** 关系表：标签 */
    val relationTag: String = "",
    // ── 第二轮逆向补充：官方 PackageNameTool / PackageIconTool 真正读的字段 ──
    /** 快递公司 LOGO —— 官方 `getPackageImageShowUrl()` 就是它，首页卡片主图 */
    val partnerLogoUrl: String = "",
    /** `feature.recommendPackageTitle` —— 服务端推荐文案，官方标题链最高优先级 */
    val recommendTitle: String = "",
    /** `feature.recommendPackagePic` */
    val recommendPic: String = "",
    /** `feature.waybillItemName` —— 转运/导入件的运单品名 */
    val waybillItemName: String = "",
    /** `feature.waybillItemPic` */
    val waybillItemPic: String = "",
    /** `packageItem[0].composeImageTitle` —— 无商品名时的合成图标题 */
    val composeImageTitle: String = "",
    /** `packageDyn.packageDynInfo.arrivalTimeProboutput` —— 0~1 到达概率，官方渲染成「95%概率」 */
    val arrivalTimeProb: Double = 0.0,
    /** 包裹来源码 / 来源描述（`pkgSource` / `pkgSourceDesc`），标题前缀用 */
    val pkgSource: String = "",
    // ── 多账号来源标记（由 Repo.homeAll 在合并阶段注入）──
    /** 这条记录来自哪个账号（[AccountStore] 的 id） */
    val accountId: String = "",
    /** 来源账号显示名 */
    val accountNick: String = "",
    /**
     * 被多个账号同时看到时记录全部来源名（顿号分隔）。
     * 典型场景：家人给我授权后，她的包裹在我的号里也看得到，两边列表重叠 ——
     * 去重后仍保留「这其实是两个人的号都能看到」这个信息。
     */
    val sharedByNick: String = "",
    val raw: JSONObject
) {
    /** 是否驿站待取件 */
    val pending: Boolean
        get() = codeUsable ||
                (stationId.isNotEmpty() && statusCode.uppercase() !in Api.DONE_STATUS)

    /**
     * 已签收。
     *
     * ⚠ 不能写成 `signedDate.isNotEmpty()`：接口对**未签收件**也可能回 `false` / `0`
     * 这类占位值，`str()` 会把它 `toString()` 成 "false" / "0"，非空 → 待取件被误判成已签收
     * （表现：待取列表空、「无待取」，件却出现在"近日签收"里）。必须要求它像一个真实日期。
     */
    val signed: Boolean
        get() = statusCode.uppercase() in setOf("SIGN", "AGENT_SIGN") ||
                DATE_LIKE.matcher(signedDate).find()

    /**
     * 官方 `pickupCode` getter 的等价实现。原文（本地 bundle 实证）：
     * ```js
     * var n = this.packageStation?.authCode;
     * if (n && n.length > 0) {
     *   if (n === "needSms" && isNotEmpty(this.feature?.stationOrderPickUpCode))
     *     return this.feature?.stationOrderPickUpCode;
     *   return n;                      // ← authCode 优先，这条不能反
     * }
     * ...
     * return t && t.length !== 0 && t !== "needSms" ? String(t) : null;   // 终态必须不是哨兵
     * ```
     * 注意：**不是**"有 stationOrderPickUpCode 就用它"—— 那只在 authCode 等于哨兵时成立。
     */
    val pickupCode: String
        get() {
            val pk = if (authCode.isNotEmpty()) {
                if (isSentinel(authCode) && stationOrderPickUpCode.isNotEmpty())
                    stationOrderPickUpCode
                else authCode
            } else ""
            // 终态兜底：结果不得是哨兵值
            return if (pk.isNotEmpty() && !isSentinel(pk)) pk else ""
        }

    /** 到站至今的天数；拿不到到站时间则为 0 */
    val dwellDays: Int
        get() {
            if (arrivalAt <= 0L) return 0
            val diff = System.currentTimeMillis() - arrivalAt
            if (diff <= 0) return 0
            return (diff / 86_400_000L).toInt()
        }

    /** 滞留告警：≥1 天"注意"，≥3 天"危险"（驿站普遍保管 3~7 天） */
    val dwellLevel: Int
        get() = when {
            dwellDays >= 3 -> 2
            dwellDays >= 1 -> 1
            else -> 0
        }

    /** 取件码展示文本；占位值一律折叠成提示，避免把 "needSms" 当码给人看 */
    val codeText: String
        get() = if (pickupCode.isNotEmpty()) pickupCode else "需短信验证"

    /**
     * 官方 `PackageNameTool.getPackageName()` 的等价简化：
     *  1. `feature.recommendPackageTitle`（recommendName）—— 服务端推荐文案，最高优先级
     *  2. `userRemark` —— 备注，官方渲染成「【家人】的」
     *  3. `getGoodsName()` —— packageItem[0].itemTitle → composeImageTitle → 「包裹」
     *  4. 「手机尾号xxxx的包裹」/「您的包裹」(getReceiverTelPackageName)
     *  官方还会在 3/4 前面拼 `pkgSourceDesc + " | "`（仅"别人寄给我的"包裹才拼），此处保留 [titlePrefix]。
     */
    val title: String
        get() {
            val core = when {
                recommendTitle.isNotEmpty() -> recommendTitle
                relationRemark.isNotEmpty() -> "【$relationRemark】的包裹"
                goodsName.isNotEmpty() -> goodsName
                phoneTail.isNotEmpty() -> "手机尾号${phoneTail}的包裹"
                else -> "您的包裹"
            }
            return if (titlePrefix.isEmpty()) core else "$titlePrefix | $core"
        }

    /**
     * 官方 `getPackageSourceName()` 的门控简化版：
     *  `pkgSource` 命中白名单，且不是官方显式返回 null 的两个来源，才展示 `pkgSourceDesc`。
     */
    val titlePrefix: String
        get() {
            if (pkgSource.isEmpty() || pkgSource !in Api.SUPPORT_SOURCE) return ""
            if (pkgSource == Api.SRC_OPEN_IMPORT || pkgSource == Api.SRC_YILIUBABA) return ""
            return sourceDesc
        }

    /** 官方 `getGoodsName()`：packageItem[0].itemTitle → composeImageTitle → waybillItemName → "" */
    val goodsName: String
        get() = itemTitle.ifEmpty { composeImageTitle.ifEmpty { waybillItemName } }

    /**
     * 卡片主图。官方两套并用：
     *  · `getPackageImageShowUrl()` = `partnerLogoUrl`（老卡片 / 首页主图）
     *  · `packageDataItem2PackageImageUrl()` ≈ `packageItem[0].itemPic`（新 dataItem 卡片）
     * 这里按信息量排序：商品图 → 推荐图 → 运单图 → 快递 LOGO，保证永远有图。
     */
    val mainPic: String
        get() = listOf(itemPic, recommendPic, waybillItemPic, partnerLogoUrl)
            .firstOrNull { it.isNotEmpty() } ?: ""

    /** 官方 `packageDataItem2PackageImageDescLabel()`：件数角标，>999 折叠 */
    val goodsBadge: String
        get() = when {
            goodsNum > 999 -> "999+件"
            goodsNum > 1 -> "${goodsNum}件"
            else -> ""
        }

    /** 官方 `receiverTel.substring(7)` —— 取末 4 位 */
    val phoneTail: String
        get() = if (receiverTel.length >= 11) receiverTel.substring(7) else ""

    /**
     * 时效文案。官方两处：
     *  · `packageSlotR1C1Label = "{100*arrivalTimeProboutput}%概率"`（来自 packageDyn）
     *  · `expectTimeDesc`（来自 packageList 的 needDynInfo）
     */
    val etaText: String
        get() {
            if (expectTimeDesc.isNotEmpty()) return expectTimeDesc
            if (arrivalTimeProb > 0) return "${(arrivalTimeProb * 100).toInt()}%概率"
            return when (arrivalTimeDays) {
                1 -> "预计今天到驿站"
                2 -> "预计明天到驿站"
                else -> ""
            }
        }

    /** 时效标签配色档位：官方 >0.9 蓝、>0.7 橙，其余灰。0=无 */
    val etaLevel: Int
        get() = when {
            arrivalTimeProb > 0.9 -> 2
            arrivalTimeProb > 0.7 -> 1
            else -> 0
        }

    /**
     * 首页分组：0=到站待取 1=运输中 2=近日签收
     *
     * ⚠ **`pending` 必须排在 `signed` 前面**，这是踩过的坑：
     * 快递把件交给驿站代收时，`logisticsStatus` 直接就是 `SIGN` / `AGENT_SIGN`，
     * 但这件明明还躺在驿站货架上等取（还带着取件码）。
     * 官方对此有明确区分 —— `isSigned`（真签收）与 `isAgentSign`（代收）是两套判定，
     * `isAgentSign + 驿站` 的文案是「驿站暂存」，只有 `isSigned` 才叫「已签收」。
     * 之前 `signed` 在前，待取件会被顶到「近日签收」组，首页就显示成「无待取」。
     */
    val group: Int
        get() = when {
            pending -> 0
            signed -> 2
            else -> 1
        }

    /**
     * 去重键 —— 判定「是不是同一个物理包裹」。
     *
     * 用运单号而不是 packageId：同一件包裹在家人的号和我的号里，
     * `packageId` 是各自的记录主键（不同），但 **`mailNo` 一定相同**。
     * 没有单号时才退到 packageId（极少数历史件）。
     */
    val dedupeKey: String
        get() = mailNo.trim().uppercase().ifEmpty { packageId }

    /**
     * 信息完整度评分。多个账号都看到同一件时，保留分最高的那条 ——
     * 避免「家人的号里取件码带着、我的号里只有个空壳」时丢掉有用信息。
     */
    val infoScore: Int
        get() = INFO_FIELDS.count { it(this) }

    /** 列表上的来源账号标签；单账号场景由 UI 决定不显示。 */
    val accountLabel: String
        get() = sharedByNick.ifEmpty { accountNick }

    companion object {
        /** [Pkg.infoScore] 打分字段清单（命中即 +1）。 */
        private val INFO_FIELDS: List<(Pkg) -> Boolean> = listOf(
            { it.pickupCode.isNotEmpty() },
            { it.itemTitle.isNotEmpty() },
            { it.mainPic.isNotEmpty() },
            { it.relationRemark.isNotEmpty() },
            { it.etaText.isNotEmpty() },
            { it.stationName.isNotEmpty() },
            { it.receiverTel.isNotEmpty() },
            { it.officeTime.isNotEmpty() },
            { it.goodsNum > 0 }
        )

        /** 取件码哨兵值：官方 `A.j` / `"needSms"` */
        private const val SENTINEL_NEED_SMS = "needsms"

        private fun isSentinel(s: String): Boolean =
            s.trim().lowercase() in setOf(SENTINEL_NEED_SMS, "need_sms")

        private val TIME_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss"
        )

        /** 到站时间候选字段名（自上而下找，命中即用） */
        private val ARRIVAL_KEYS = listOf(
            "arrivalTime", "arrival_time", "arriveTime", "arrive_time",
            "inTime", "in_time", "inStationTime", "in_station_time",
            "stationArrivalTime", "station_arrival_time",
            "toStationTime", "to_station_time", "dynArrivalTime",
            "gmtArrive", "arriveGmt", "bizTime", "biz_time"
        )

        private val DATE_IN_TEXT = Pattern.compile(
            "(20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)"
        )

        /**
         * 「看起来像日期」的宽松判据，用于 [Pkg.signed]。
         * 关键是能挡掉 `false` / `0` / `-1` / `null` 这类接口占位值。
         */
        private val DATE_LIKE = Pattern.compile(
            "20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}|20\\d{2}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}"
        )

        fun from(o: JSONObject): Pkg {
            val st = firstObject(o, "packageStation", "package_station", "station", "pkgStation")

            val statusDesc = str(
                o, "logisticsStatusDesc", "logistics_status_desc",
                "packageStatusDesc", "package_status_desc"
            )
            var statusCode = str(o, "logisticsStatus", "logistics_status")
            if (statusCode.isEmpty() && statusDesc.isNotEmpty()) statusCode = statusDesc

            var code = str(st, "authCode", "auth_code")
            if (isPlaceholder(code)) {
                // V2 变体把码放在 showAuthCode/showPickupCode，且是字符串而非布尔标志
                val alt = str(st, "showPickupCode", "show_pickup_code", "pickUpCode", "pickupCode")
                if (alt.isNotEmpty() && !isPlaceholder(alt) && !isBoolLike(alt)) code = alt
            }

            // packageItem[]：商品图/标题。官方 getPackageTitle/getPackagePic 读的是 [0]
            val feature = firstObject(o, "feature")
            val items = firstArray(o, "packageItem", "package_item")
            val item0 = if (items != null && items.length() > 0) items.optJSONObject(0) else null

            return Pkg(
                mailNo = str(o, "mailNo", "mail_no"),
                statusDesc = if (statusDesc.isNotEmpty()) statusDesc else str(o, "logisticsStatus"),
                statusCode = statusCode,
                partnerName = str(o, "partnerName", "partner_name"),
                partnerCode = str(o, "partnerCode", "partner_code"),
                sourceDesc = str(o, "pkgSourceDesc", "pkg_source_desc"),
                stationName = str(st, "stationName", "station_name"),
                stationId = str(st, "stationId", "station_id"),
                stationPhone = str(st, "mobile", "phone", "stationPhone", "telephone"),
                officeTime = str(st, "officeTime", "office_time", "businessHours"),
                authCode = code,
                codeUsable = !isPlaceholder(code),
                lastDetail = str(o, "lastLogisticDetail", "last_logistic_detail"),
                arrivalAt = parseArrival(o, st),
                signedDate = str(o, "signedDate", "signed_date", "signTime"),
                packageId = str(o, "packageId", "package_id", "id"),
                itemTitle = str(item0, "itemTitle", "item_title"),
                // 官方显式过滤字面量字符串 "null"
                itemPic = str(item0, "itemPic", "item_pic").takeIf { it != "null" } ?: "",
                goodsNum = str(o, "goodsNum", "goods_num").toIntOrNull() ?: 0,
                receiverTel = str(o, "receiverTel", "receiver_tel", "receiverPhone"),
                encryReceiverTel = str(o, "encryReceiverTel", "encry_receiver_tel"),
                stationDaishouType = str(st, "stationDaishouType", "station_daishou_type"),
                stationOrderPickUpCode = str(feature, "stationOrderPickUpCode", "station_order_pick_up_code"),
                // packageDyn 由 querypackagedynlist 二次补齐；这里先吃响应里已经带的
                expectTimeDesc = str(o, "expectTimeDesc", "expect_time_desc"),
                // ── 官方 PackageNameTool / PackageIconTool 真正读的字段 ──
                partnerLogoUrl = str(o, "partnerLogoUrl", "partner_logo_url"),
                recommendTitle = str(feature, "recommendPackageTitle", "recommend_package_title"),
                recommendPic = str(feature, "recommendPackagePic", "recommend_package_pic"),
                waybillItemName = str(feature, "waybillItemName", "waybill_item_name"),
                waybillItemPic = str(feature, "waybillItemPic", "waybill_item_pic"),
                composeImageTitle = str(item0, "composeImageTitle", "compose_image_title"),
                arrivalTimeProb = str(o, "arrivalTimeProboutput", "arrival_time_proboutput")
                    .toDoubleOrNull() ?: 0.0,
                pkgSource = str(o, "pkgSource", "pkg_source"),
                raw = o
            )
        }

        /** 从 packageDyn 结构里抠时效字段（官方 packageDyn.packageDynInfo.*）。 */
        fun applyDyn(p: Pkg, dyn: JSONObject): Pkg {
            val info = firstObject(dyn, "packageDynInfo", "package_dyn_info")
                ?: firstObject(dyn, "packageDyn", "package_dyn")
                ?: dyn
            val desc = str(info, "expectTimeDesc", "expect_time_desc")
            val days = str(info, "arrivalTimeDays", "arrival_time_days").toIntOrNull() ?: 0
            val prod = str(info, "predictDateProd", "predict_date_prod")
            // 官方 packageSlotR1C1Label 用的是这个 0~1 的概率
            val prob = str(info, "arrivalTimeProboutput", "arrival_time_proboutput")
                .toDoubleOrNull() ?: 0.0
            if (desc.isEmpty() && days == 0 && prod.isEmpty() && prob == 0.0) return p
            return p.copy(
                expectTimeDesc = desc.ifEmpty { p.expectTimeDesc },
                arrivalTimeDays = if (days != 0) days else p.arrivalTimeDays,
                predictDateProd = prod.ifEmpty { p.predictDateProd },
                arrivalTimeProb = if (prob > 0) prob else p.arrivalTimeProb
            )
        }

        /** 挂关系表信息（备注/标签）。 */
        fun applyRelation(p: Pkg, remark: String, tag: String): Pkg {
            if (remark.isEmpty() && tag.isEmpty()) return p
            return p.copy(
                relationRemark = remark.ifEmpty { p.relationRemark },
                relationTag = tag.ifEmpty { p.relationTag }
            )
        }

        /** 给某个账号拉回来的整批包裹打上来源标记。 */
        fun tagAccount(list: List<Pkg>, accountId: String, nick: String): List<Pkg> {
            if (accountId.isEmpty()) return list
            return list.map { it.copy(accountId = accountId, accountNick = nick) }
        }

        /**
         * **多账号合并去重** —— 本功能的核心。
         *
         * 合并规则：
         *  1. 按 [dedupeKey]（运单号）归并；同一件包裹只保留一条。
         *  2. 保留 [infoScore] 最高的那条 —— 谁的取件码/商品图/备注更全就用谁的。
         *  3. 记录全部来源账号到 [sharedByNick]，所以"我家人授权给我的件"
         *     既不会被显示两次，也不会丢掉"这是她的号"这个信息。
         *  4. 结果按 到站 → 运输中 → 已签收 排序，组内新到的在前。
         */
        fun merge(groups: List<List<Pkg>>): List<Pkg> {
            val best = LinkedHashMap<String, Pkg>()
            val sources = LinkedHashMap<String, LinkedHashSet<String>>()

            for ((gi, g) in groups.withIndex()) {
                for ((pi, p) in g.withIndex()) {
                    // 既没运单号也没 packageId 的极少数记录**不能丢** —— 给个占位键单独保留
                    // （曾经 `continue` 掉，等于把拿不到单号的件凭空吞了）
                    val k = p.dedupeKey.ifEmpty { "\u0000$gi:$pi" }
                    val names = sources.getOrPut(k) { LinkedHashSet() }
                    if (p.accountNick.isNotEmpty()) names.add(p.accountNick)
                    val cur = best[k]
                    if (cur == null || p.infoScore > cur.infoScore) best[k] = p
                }
            }

            return best.entries
                .map { (k, p) ->
                    val names = sources[k].orEmpty()
                    p.copy(
                        sharedByNick = if (names.size > 1) names.joinToString("、") else "",
                        accountNick = p.accountNick.ifEmpty { names.firstOrNull() ?: "" }
                    )
                }
                .sortedWith(compareBy({ it.group }, { -it.arrivalAt }))
        }

        private fun parseArrival(o: JSONObject, st: JSONObject?): Long {
            for (k in ARRIVAL_KEYS) {
                parseTime(str(o, k))?.let { return it }
                parseTime(str(st, k))?.let { return it }
            }
            // 兜底：从最新物流描述里抠时间
            parseTimeFromText(str(o, "lastLogisticDetail", "last_logistic_detail"))?.let { return it }
            // 再兜底：从轨迹数组最后一条里抠
            val list = o.optJSONArray("detailList") ?: o.optJSONArray("traceList")
            if (list != null && list.length() > 0) {
                val last = list.optJSONObject(list.length() - 1)
                if (last != null) {
                    for (k in listOf("time", "ocurrTimeStr", "occurTimeStr", "statusTime")) {
                        parseTime(str(last, k))?.let { return it }
                    }
                }
            }
            return 0L
        }

        fun parseTime(s: String): Long? {
            if (s.isEmpty()) return null
            for (p in TIME_PATTERNS) {
                try {
                    val f = SimpleDateFormat(p, Locale.CHINA)
                    f.isLenient = true
                    val d = f.parse(s) ?: continue
                    return d.time
                } catch (_: Exception) {
                    // 换下一个格式
                }
            }
            return null
        }

        fun parseTimeFromText(text: String): Long? {
            if (text.isEmpty()) return null
            val m = DATE_IN_TEXT.matcher(text)
            if (!m.find()) return null
            return parseTime(m.group(1) ?: return null)
        }

        private fun isPlaceholder(code: String): Boolean =
            code.trim().lowercase() in Api.CODE_PLACEHOLDER

        private fun isBoolLike(s: String): Boolean =
            s.equals("true", true) || s.equals("false", true)

        fun firstObject(o: JSONObject, vararg keys: String): JSONObject? {
            for (k in keys) {
                val v = o.optJSONObject(k)
                if (v != null) return v
            }
            val it = o.keys()
            while (it.hasNext()) {
                val real = it.next()
                if (keys.any { it.equals(real, true) }) return o.optJSONObject(real)
            }
            return null
        }

        /** 大小写不敏感地取第一个命中的数组（packageItem 等）。 */
        fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
            for (k in keys) {
                val v = o.optJSONArray(k)
                if (v != null) return v
            }
            val it = o.keys()
            while (it.hasNext()) {
                val real = it.next()
                if (keys.any { it.equals(real, ignoreCase = true) }) return o.optJSONArray(real)
            }
            return null
        }

        /** 大小写/下划线不敏感地取字符串值。 */
        fun str(o: JSONObject?, vararg keys: String): String {
            if (o == null) return ""
            for (k in keys) {
                if (o.has(k) && !o.isNull(k)) {
                    val v = o.opt(k)
                    when (v) {
                        is String -> if (v.isNotBlank() && v != "null") return v.trim()
                        is Number, is Boolean -> return v.toString()
                    }
                }
            }
            val it = o.keys()
            while (it.hasNext()) {
                val real = it.next()
                if (o.isNull(real)) continue
                if (keys.any { it.equals(real, ignoreCase = true) }) {
                    val v = o.opt(real)
                    when (v) {
                        is String -> if (v.isNotBlank() && v != "null") return v.trim()
                        is Number, is Boolean -> return v.toString()
                    }
                }
            }
            return ""
        }
    }
}

/** 驿站营业时间解析与打烊倒计时。 */
object OfficeHours {

    private val RANGE = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[-~—－至]\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

    data class Slot(val openMin: Int, val closeMin: Int)

    fun parse(text: String): Slot? {
        val m = RANGE.matcher(text) ?: return null
        if (!m.find()) return null
        return try {
            val h1 = m.group(1)!!.toInt(); val m1 = m.group(2)!!.toInt()
            val h2 = m.group(3)!!.toInt(); val m2 = m.group(4)!!.toInt()
            Slot(h1 * 60 + m1, h2 * 60 + m2)
        } catch (_: Exception) {
            null
        }
    }

    /** 距离打烊还有多少分钟；已打烊或无法解析返回 -1。跨零点营业（如 08:00-00:30）按 24h 处理。 */
    fun minutesToClose(text: String, now: Long = System.currentTimeMillis()): Int {
        val slot = parse(text) ?: return -1
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        var close = slot.closeMin
        if (close <= slot.openMin) close += 24 * 60          // 跨零点
        if (nowMin < slot.openMin) return -1                  // 还没开门
        val d = close - nowMin
        return if (d < 0) -1 else d
    }

    fun isOpen(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val slot = parse(text) ?: return true
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        var close = slot.closeMin
        if (close <= slot.openMin) close += 24 * 60
        if (nowMin >= slot.openMin && nowMin <= close) return true
        // 跨零点区间的前半段（00:00 到 close）
        if (slot.closeMin <= slot.openMin && nowMin <= slot.closeMin) return true
        return false
    }

    fun humanLeft(minutes: Int): String {
        if (minutes < 0) return ""
        if (minutes < 60) return "还有 ${minutes} 分钟打烊"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "还有 ${h} 小时打烊" else "还有 ${h} 小时 ${m} 分打烊"
    }

    fun todayLabel(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MM月dd日 EEEE", Locale.CHINA).format(Date(now))
}

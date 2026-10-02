package com.coldbrew.cainiao.data

/**
 * MTOP H5 网关常量。
 *
 * 与 Python 版 `cainiao_h5_client.py` 保持逐字一致：
 *   签名 sign = md5("{_m_h5_tk 的 token 段}&{t}&{appKey}&{data}")
 *   通道 https://h5api.m.taobao.com/h5/<api 小写>/<version>/?jsv=2.7.4&appKey&t&sign&api&v&type&dataType&data
 * 首次无 token 会返回 FAIL_SYS_TOKEN_EMPTY 并由网关下发 _m_h5_tk，带签重试即通。
 */
object Api {
    const val HOST = "https://h5api.m.taobao.com"
    const val APP_KEY = "12574478"
    const val REFERER = "https://h5.m.taobao.com/"

    /** 安卓 Chrome 移动版 UA；不要带 "; wv"，否则部分淘系页面会走降级分支 */
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    // ── 淘宝「扫码登录」（免密码）──
    //
    // 地址逐字取自官方登录页 `https://login.taobao.com/member/login.jhtml` 内联配置里
    // 的 `getQRCodeApi` / `checkQRCodeApi`（页面自己就是这么调的，实测可用）。
    // 细节与状态机见 `QrLogin.kt`。

    /** 申请二维码。响应含 t / ck / codeContent，并 Set-Cookie 下发 cookie2 / t / _tb_token_ */
    const val QR_GENERATE = "https://login.taobao.com/havanaone/loginLegacy/qrCode/generate.do"

    /** 查询扫码状态。**只认 POST + form-urlencoded**，用 GET 会直接返回 EXPIRED */
    const val QR_QUERY = "https://login.taobao.com/havanaone/loginLegacy/qrCode/query.do"

    /**
     * 扫码接口的固定业务参数。
     *
     * `taobao_h5` 是移动端入口（PC 页用的是 `taobao_pc`）。两边都能出码，
     * 但 `taobao_h5` 出的是移动端确认页，手机淘宝扫完直接进确认界面，体验更顺。
     */
    const val QR_BIZ = "bizEntrance=taobao_h5&bizName=taobao"

    /** 登录相关请求的 Referer —— 不带会被风控拦（官方页面就是这个）。 */
    const val LOGIN_REFERER = "https://login.taobao.com/member/login.jhtml"

    // ── 免登录通道（pid 一族不需要 session） ──
    const val CP_BY_MAILNO = "mtop.taobao.logisticsDetailTraceQueryService.queryCpByMailNo"
    const val TRACE = "mtop.taobao.logisticstracedetailservice.queryalltrace"
    const val PID = "mtop.cainiao.logisticsdetail.packages.pid.orderCode.mailNo.get"
    const val LD_DETAIL = "mtop.cainiao.ld.detail.tradeId.orderCode.mailNo.resCode.get"

    // ── 需登录态 ──
    /** 我的包裹列表（含取件码）—— 真身接口，来自 APK 内本地 JS bundle */
    const val PDS_LIST = "mtop.cainiao.lpc.packageservice.queryPdsPackageDataList"
    /** 同一套数据的 pcs 实现，官方两路互为备份（getMtopAPINameForPackageListDowngrade / 新路径） */
    const val PCS_LIST = "mtop.cainiao.pcs.packageservice.queryPackageDataList"
    /** 各状态包裹计数，用来快速判断登录态是否有效 */
    const val COUNT = "mtop.cainiao.lpc.packageservice.user.package.count.query"
    /** 时效/预计到达：按 packageIdList 批量补 packageDyn */
    const val DYNLIST = "mtop.cainiao.lpc.packageservice.querypackagedynlist"
    const val DYNLIST_PCS = "mtop.cainiao.pcs.packageservice.querypackagedynlist"
    const val IDENTITY_CODE = "mtop.cainiao.nbpickup.identitycode.get"
    const val STATIONS = "mtop.cainiao.nbpickup.recommend.point.list"
    const val BATCH_CODE = "mtop.cainiao.nbpickup.batch.code.generate"

    // ── 亲友关系 / 备注（「家人」那套关系备注走这里）──
    /** 关系列表：response.result[] ，含 remark / tag / mobile / relateUserId */
    const val RELATION_LIST = "mtop.cainiao.guoguouser.br.relation.list"
    /** 备注可选标签表 */
    const val RELATION_TAG_LIST = "mtop.cainiao.guoguouser.relation.query.reluser.tag.list"
    /** 写备注：{ remark, mobile, relateUserId, relationType } */
    const val RELATION_SAVE_REMARK = "mtop.cainiao.guoguouser.relation.modify.reluser.remark"

    /** 删除包裹后通知驿站重算待取列表（官方在删除「驿站已签收」件时调用） */
    const val WAIT_PICKUP_REFRESH = "mtop.cainiao.nbpickup.waitpickup.getlist"

    /**
     * querypackagedynlist 的 querySourceId。
     * 取自 APK 内 `Enum: PACKAGE_LIST="68719476741", PICKUP_LIST="68719476752"`
     * （4 个 bundle 交叉一致）。
     */
    const val SRC_PACKAGE_LIST = "68719476741"
    const val SRC_PICKUP_LIST = "68719476752"

    /**
     * 每页条数。实测 50 可正常返回（历史运行输出：`pageSize=50`，一次拿到全部 50 件）。
     *
     * ⚠ 注意：官方 `packageListDowngradeCount = 40` 是**降级路径**的截断值 ——
     * 官方正常路径走本地 Dorado 库（全量），MTOP 只是兜底，所以它允许丢数据。
     * 我们只有 MTOP 这条路，**必须翻页取全，不能照抄 40 这个截断值**。
     */
    const val PAGE_SIZE = 50

    /** 翻页上限（50×20 = 1000 件；正常用户远达不到，纯粹防死循环） */
    const val MAX_PAGES = 20

    /**
     * querypackagedynlist 的 needDynInfo。
     * 逐字取自 APK：needCommunityHomeDeliveryExtendInfo / needFindTemporality /
     * needAppointment / needStationDlivery / needConfirmService / isSupportOneClickOrder / needStationInfo
     */
    const val NEED_DYN_INFO =
        """{"needCommunityHomeDeliveryExtendInfo":true,"needFindTemporality":true,""" +
                """"needAppointment":true,"needStationDlivery":true,"needConfirmService":true,""" +
                """"isSupportOneClickOrder":false,"needStationInfo":false}"""

    /** 包裹列表接口的 needDynInfo（官方逐字：单引号 JSON 串，注意不是标准 JSON） */
    const val NEED_DYN_INFO_LIST = "{'needFindTemporality':true,'needAppointment':true}"

    /** App 内置默认 extendParam（取自 GuoguoBatchCodeRequest） */
    const val BATCH_CODE_EXT = """{"pickupChannel":"2_22_1","pickupWay":"-1"}"""

    /** 触发重试（带新 token）的返回码。注意网关实际拼写漏了 P：TOKEN_EXOIRED */
    val RETRY_MARKERS = listOf("TOKEN_EMPTY", "TOKEN_EXPIRED", "TOKEN_EXOIRED", "ILLEGAL_ACCESS")

    /** 终态：不算待取件 */
    val DONE_STATUS = setOf("SIGN", "CANCEL", "REJECT", "RETURN", "LOST")

    /** 取件码占位值：表示"需短信验证才可见"，不是真码 */
    val CODE_PLACEHOLDER = setOf("needsms", "need_sms", "-1", "0", "", "null", "none")

    // ── PackageNameTool 的来源表（官方 bundle 逐字，4 处交叉一致）──

    /**
     * `supportSourceArray` —— 只有这些来源的包裹才在标题前拼「来源 | 」。
     * 官方 `getPackageSourceName()` 的门控之一。
     */
    val SUPPORT_SOURCE = setOf(
        "OPEN_IMPORT", "CUNTAO", "XIAOHONGSHU", "MOGUJIE", "GUOMEI", "DANGDANG",
        "WEIDIAN", "BEIBEI", "HUAWEISHANGCHENG", "KAOLA", "TMALL_RETURN", "YHD",
        "MEILISHUO", "JHS", "TAOBAO_RETURN_SEND", "TMALL_RETURN_SEND", "YUNJIWANG",
        "VIPS", "YILIUBABA", "TAOBAO", "SUNING", "MIA", "BANG", "TAOBAO_RETURN",
        "TMALL", "XIANYU", "XIANYU_SEND", "XYZHONGBAO", "JD", "ZHUANZHUAN",
        "MEIZUYOUXUAN", "XIAOMISHANGCHENG", "PINGDUODUO", "JUMEI", "XIANYU_RETURN",
        "LITETAO", "ELEME"
    )

    /** 官方 `getPackageSourceName()` 里显式返回 null 的两个来源，即使命中白名单也不拼 */
    const val SRC_OPEN_IMPORT = "OPEN_IMPORT"
    const val SRC_YILIUBABA = "YILIUBABA"

    /** `localLifeSourceArray` —— 本地生活单（社区团购/驿站洗衣），标题直接用商品名 */
    val LOCAL_LIFE_SOURCE = setOf("TCC", "STATION_LAUNDRY")
}

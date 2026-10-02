package com.coldbrew.cainiao.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.Pkg
import com.coldbrew.cainiao.databinding.ItemGroupFooterBinding
import com.coldbrew.cainiao.databinding.ItemPackageBinding
import com.coldbrew.cainiao.databinding.ItemSectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待取件列表适配器。三种行拼出「分组容器卡」：
 *
 * ```
 * Row.GroupHead   卡头（彩色圆底图标 + 分组名 · N 件 + 说明 + 折叠箭头，上两角圆）
 * Row.Item        卡身行（无框，中间行用 bg_group_row / 末行用 bg_group_row_last）
 * Row.GroupFoot   卡尾（组级动作 + 展开收起，下两角圆）—— 只在需要时插一行
 * ```
 *
 * ## 为什么从「彩色小卡」改成「白卡容器」
 *
 * 用户实测反馈过两次「到站 / 运输中 / 近日签收 分界不明显」，第一次的修法是给三组换
 * 卡片底色 + 描边 + 角标 —— **方向错了**：那只是把三个小卡片涂成三种颜色，
 * 组和条目都还是「框」，层级打平，一屏里全是框，反而更花、更碎。
 *
 * 对比官方菜鸟首页后确认：分组分界靠的是**容器结构**，不是配色 ——
 * 官方每组是一张白卡把组内条目全包住，组与组之间是「白卡 ↔ 灰底空隙」的强对比；
 * 组标题在卡**内**顶部且带折叠箭头；组内条目无边框，只有一条缩进的分隔线。
 * 所以这里照做，配色收敛回「白底黑字」，身份色只落在卡头那个 26dp 圆底图标上。
 *
 * 分组数据与排序对齐官方首页（驿站分组 + 状态分层 handlePackageArrivalTimeGroup）。
 */
class PkgAdapter(
    private val onClick: (Pkg) -> Unit,
    private val onCopyAllCodes: (List<Pkg>) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class GroupHead(val group: Int, val count: Int, val folded: Boolean) : Row
        data class Item(val pkg: Pkg, val last: Boolean) : Row
        data class GroupFoot(val group: Int, val total: Int, val folded: Boolean, val copyAll: Boolean) : Row
    }

    private val rows = ArrayList<Row>()

    /**
     * 被折叠的分组。默认把「近日签收」收起来 —— 签收件动辄几十条（实测 42 件），
     * 全铺开会把整页撑成一屏三张卡，把「到站」挤到看不见。
     */
    private val folded = linkedSetOf(2)

    /** group → 该组条目（组级动作用的）。 */
    private val groupPkgs = LinkedHashMap<Int, List<Pkg>>()

    private var lastList: List<Pkg> = emptyList()
    private var lastFilter: Int = FILTER_ALL

    /** 多账号时才显示来源标签 —— 单账号场景没必要给每行都挂个名字。 */
    var showAccountLabel: Boolean = false

    /**
     * 提交列表。
     * @param filter [FILTER_ALL] 或某个 group id（0 到站 / 1 运输中 / 2 近日签收）
     */
    fun submitGrouped(list: List<Pkg>, filter: Int = FILTER_ALL) {
        lastList = list
        lastFilter = filter
        rows.clear()
        groupPkgs.clear()

        fun add(group: Int, items: List<Pkg>) {
            if (items.isEmpty()) return
            if (filter != FILTER_ALL && filter != group) return

            groupPkgs[group] = items
            val isFolded = group in folded
            val shown = if (isFolded) items.take(PREVIEW) else items
            val canCopyAll = group == GROUP_ARRIVAL &&
                    items.count { it.pickupCode.isNotEmpty() } >= 2
            val needFoot = canCopyAll || isFolded || items.size > FOLD_SUGGEST

            rows.add(Row.GroupHead(group, items.size, isFolded))
            shown.forEachIndexed { i, p ->
                rows.add(Row.Item(p, last = !needFoot && i == shown.lastIndex))
            }
            if (needFoot) rows.add(Row.GroupFoot(group, items.size, isFolded, canCopyAll))
        }

        add(GROUP_ARRIVAL, list.filter { it.group == GROUP_ARRIVAL }
            .sortedWith(compareByDescending<Pkg> { it.dwellDays }.thenByDescending { it.codeUsable }))
        add(GROUP_TRANSIT, list.filter { it.group == GROUP_TRANSIT })
        add(GROUP_DONE, list.filter { it.group == GROUP_DONE })

        notifyDataSetChanged()
    }

    /** 展开/收起某组。折叠状态存在适配器里，所以刷新数据不会把用户的手动展开吃掉。 */
    private fun toggleFold(group: Int) {
        if (!folded.remove(group)) folded.add(group)
        submitGrouped(lastList, lastFilter)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.GroupHead -> TYPE_HEAD
        is Row.Item -> TYPE_ITEM
        is Row.GroupFoot -> TYPE_FOOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEAD -> HeadVH(ItemSectionBinding.inflate(inf, parent, false))
            TYPE_FOOT -> FootVH(ItemGroupFooterBinding.inflate(inf, parent, false))
            else -> VH(ItemPackageBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.GroupHead -> (holder as HeadVH).bind(r)
            is Row.Item -> (holder as VH).bind(r.pkg, r.last)
            is Row.GroupFoot -> (holder as FootVH).bind(r)
        }
    }

    // ─────────────────────── 卡头 ───────────────────────

    inner class HeadVH(private val b: ItemSectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.GroupHead) {
            val s = styleOf(b.root.context, h.group)
            b.ivGroupIcon.setImageResource(s.icon)
            b.ivGroupIcon.imageTintList = ColorStateList.valueOf(s.accent)
            b.ivGroupIcon.backgroundTintList = ColorStateList.valueOf(s.soft)

            b.tvSectionTitle.text = s.name
            b.tvSectionCount.text = "${h.count} 件"
            b.tvSectionHint.text = s.hint
            b.tvSectionHint.visibility = if (s.hint.isEmpty()) View.GONE else View.VISIBLE

            // 折叠箭头：ic_chevron 天生朝右，转 90° 朝下（点开）、转 270° 朝上（收起）
            b.ivFold.rotation = if (h.folded) 90f else 270f

            b.root.setOnClickListener { toggleFold(h.group) }
        }
    }

    // ─────────────────────── 卡尾 ───────────────────────

    inner class FootVH(private val b: ItemGroupFooterBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(f: Row.GroupFoot) {
            b.tvGroupAction.visibility = if (f.copyAll) View.VISIBLE else View.GONE
            b.tvGroupAction.text = GROUP_ACTION_COPY
            b.tvGroupAction.setOnClickListener {
                onCopyAllCodes(groupPkgs[f.group].orEmpty())
            }
            b.tvFold.text = if (f.folded) "展开全部 ${f.total} 件" else "收起"
            b.tvFold.setOnClickListener { toggleFold(f.group) }
        }
    }

    // ─────────────────────── 卡身行 ───────────────────────

    inner class VH(private val b: ItemPackageBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(p: Pkg, last: Boolean) {
            val ctx = b.root.context

            // 位置决定圆角：末行收底，非末行留出 1dp 分隔线（线从文字左边起）
            b.root.setBackgroundResource(
                if (last) R.drawable.bg_group_row_last else R.drawable.bg_group_row
            )
            b.vDivider.visibility = if (last) View.GONE else View.VISIBLE

            // ── 缩略图：商品图 → 推荐图 → 运单图 → 快递 LOGO ──
            if (p.mainPic.isNotEmpty()) {
                b.ivGoods.visibility = View.VISIBLE
                RemoteImage.load(b.ivGoods, p.mainPic)
            } else {
                b.ivGoods.visibility = View.GONE
            }

            // ── 件数角标 ──
            if (p.goodsBadge.isNotEmpty()) {
                b.tvGoodsBadge.visibility = View.VISIBLE
                b.tvGoodsBadge.text = p.goodsBadge
            } else {
                b.tvGoodsBadge.visibility = View.GONE
            }

            // ── 来源账号标签（多账号合并时才有意义）──
            val label = p.accountLabel
            if (showAccountLabel && label.isNotEmpty()) {
                b.tvAccount.visibility = View.VISIBLE
                b.tvAccount.text = label
                if (p.sharedByNick.isNotEmpty()) {
                    // 两个号都能看到这一件：说明是共用/授权关系
                    b.tvAccount.setBackgroundResource(R.drawable.bg_pill_warn)
                    b.tvAccount.setTextColor(ContextCompat.getColor(ctx, R.color.warn))
                } else {
                    b.tvAccount.setBackgroundResource(R.drawable.bg_pill_info)
                    b.tvAccount.setTextColor(ContextCompat.getColor(ctx, R.color.info))
                }
            } else {
                b.tvAccount.visibility = View.GONE
            }

            // ── 滞留标签（只有待取的件会滞留）──
            if (p.pending && p.dwellDays >= 1) {
                b.tvDwell.visibility = View.VISIBLE
                b.tvDwell.text = "滞留 ${p.dwellDays} 天"
                if (p.dwellLevel >= 2) {
                    b.tvDwell.setBackgroundResource(R.drawable.bg_pill_danger)
                    b.tvDwell.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
                } else {
                    b.tvDwell.setBackgroundResource(R.drawable.bg_pill_warn)
                    b.tvDwell.setTextColor(ContextCompat.getColor(ctx, R.color.warn))
                }
            } else {
                b.tvDwell.visibility = View.GONE
            }

            when (p.group) {
                GROUP_TRANSIT -> bindTransit(p, ctx)
                GROUP_DONE -> bindDone(p, ctx)
                else -> bindArrival(p, ctx)
            }

            b.root.setOnClickListener { onClick(p) }
        }

        /** 到站：L1 包裹名，L2 大号取件码，L3 快递·来源·单号。 */
        private fun bindArrival(p: Pkg, ctx: Context) {
            b.tvStatus.visibility = View.GONE
            b.rowCode.visibility = View.VISIBLE
            b.tvEta.visibility = View.GONE

            b.tvTitle.visibility = View.VISIBLE
            b.tvTitle.text = p.title
            b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text))

            val code = p.pickupCode
            if (code.isNotEmpty()) {
                b.tvCode.text = code
                b.tvCode.setTextColor(ContextCompat.getColor(ctx, R.color.brand_dark))
                b.tvCodeHint.visibility = View.GONE
            } else {
                // 有件、但码要短信验证：不要假装有码，也别把哨兵值印出来
                b.tvCode.text = "— — —"
                b.tvCode.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                b.tvCodeHint.visibility = if (p.pending) View.VISIBLE else View.GONE
            }

            b.tvLine2.visibility = View.VISIBLE
            b.tvLine2.setTextColor(ContextCompat.getColor(ctx, R.color.text_sub))
            b.tvLine2.text = meta(p)
            b.tvLine3.visibility = View.GONE
        }

        /** 运输中：L1 「运输中 + 时效」，L2 来源|商品名，L3 快递·最新轨迹。 */
        private fun bindTransit(p: Pkg, ctx: Context) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.text = p.statusDesc.ifEmpty { "运输中" }
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text))

            b.tvTitle.visibility = View.GONE
            b.rowCode.visibility = View.GONE
            b.tvCodeHint.visibility = View.GONE

            // 时效跟状态同排内联（官方就是「运输中 90%概率10.5送达」一行），
            // 不再做成独立药丸 —— 旧版标题挂「在途」角标、中间一颗时效药丸、
            // 下面再来一行 22sp「运输中」，同一件事说了三遍。
            if (p.etaText.isNotEmpty()) {
                b.tvEta.visibility = View.VISIBLE
                b.tvEta.text = p.etaText
                b.tvEta.setTextColor(
                    ContextCompat.getColor(
                        ctx, when (p.etaLevel) {
                            2 -> R.color.info
                            1 -> R.color.warn
                            else -> R.color.brand_dark
                        }
                    )
                )
            } else {
                b.tvEta.visibility = View.GONE
            }

            b.tvLine2.visibility = View.VISIBLE
            b.tvLine2.setTextColor(ContextCompat.getColor(ctx, R.color.text_sub))
            b.tvLine2.text = p.title
            b.tvLine3.visibility = if (p.lastDetail.isNotEmpty()) View.VISIBLE else View.GONE
            b.tvLine3.text = withTrail(p, p.lastDetail)
        }

        /** 近日签收：L1 「已签收 09-30 21:38」，L2 包裹名，L3 快递·最新轨迹。整体压暗。 */
        private fun bindDone(p: Pkg, ctx: Context) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.text = "已签收 ${signedLabel(p)}"
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))

            b.tvTitle.visibility = View.GONE
            b.rowCode.visibility = View.GONE
            b.tvCodeHint.visibility = View.GONE
            b.tvEta.visibility = View.GONE

            b.tvLine2.visibility = View.VISIBLE
            b.tvLine2.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            b.tvLine2.text = p.title
            b.tvLine3.visibility = if (p.lastDetail.isNotEmpty()) View.VISIBLE else View.GONE
            b.tvLine3.text = withTrail(p, p.lastDetail)
        }
    }

    // ─────────────────── 文案 / 配色 ───────────────────

    /** 一个分组的身份：名字、说明、强调色、浅色底、图标。 */
    private class GroupStyle(
        val name: String,
        val hint: String,
        val accent: Int,
        val soft: Int,
        val icon: Int
    )

    /**
     * 颜色语义（照官方收敛到白底黑字，颜色只出现在卡头那个圆底图标上）：
     *   0 到站     橙 = 要行动
     *   1 运输中   蓝 = 在等
     *   2 近日签收 灰 = 已完结
     */
    private fun styleOf(ctx: Context, group: Int): GroupStyle = when (group) {
        GROUP_TRANSIT -> GroupStyle(
            name = "运输中",
            hint = "还没到驿站",
            accent = ContextCompat.getColor(ctx, R.color.info),
            soft = ContextCompat.getColor(ctx, R.color.info_soft),
            icon = R.drawable.ic_sync
        )
        GROUP_DONE -> GroupStyle(
            name = "近日签收",
            hint = "",
            accent = ContextCompat.getColor(ctx, R.color.text_muted),
            soft = ContextCompat.getColor(ctx, R.color.bg),
            icon = R.drawable.ic_check
        )
        else -> GroupStyle(
            name = "到站",
            hint = "凭取件码到驿站自取",
            accent = ContextCompat.getColor(ctx, R.color.brand),
            soft = ContextCompat.getColor(ctx, R.color.brand_soft),
            icon = R.drawable.ic_box
        )
    }

    /** 「圆通速递 · 末端非淘包裹 · YT1234567890123」 */
    private fun meta(p: Pkg): String = listOf(p.partnerName.ifEmpty { "快递" }, p.sourceDesc, p.mailNo)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

    /** 「邮政快递 · 快件到达【三门峡市城区集散点】」 */
    private fun withTrail(p: Pkg, trail: String): String =
        p.partnerName.ifEmpty { "快递" } + if (trail.isEmpty()) "" else " · $trail"

    private fun signedLabel(p: Pkg): String {
        val t = Pkg.parseTime(p.signedDate) ?: return p.signedDate
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t))
    }

    companion object {
        const val FILTER_ALL = -1

        const val GROUP_ARRIVAL = 0
        const val GROUP_TRANSIT = 1
        const val GROUP_DONE = 2

        /** 折叠时每组露出几条 */
        private const val PREVIEW = 1

        /** 超过这个条数就补一个「收起」入口 */
        private const val FOLD_SUGGEST = 5

        private const val GROUP_ACTION_COPY = "复制全部取件码"

        private const val TYPE_HEAD = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_FOOT = 2
    }
}

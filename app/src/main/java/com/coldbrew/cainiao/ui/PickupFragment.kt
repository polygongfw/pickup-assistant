package com.coldbrew.cainiao.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.AccountStore
import com.coldbrew.cainiao.data.MtopException
import com.coldbrew.cainiao.data.OfficeHours
import com.coldbrew.cainiao.data.Pkg
import com.coldbrew.cainiao.data.Repo
import com.coldbrew.cainiao.databinding.FragmentPickupBinding
import kotlinx.coroutines.launch

class PickupFragment : Fragment() {

    private var _b: FragmentPickupBinding? = null
    private val b get() = _b!!
    private val adapter = PkgAdapter(
        onClick = { onPkgClick(it) },
        onCopyAllCodes = { copyAllCodes(it) }
    )
    private var loading = false

    /** 当前列表里的驿站营业时间 / 电话（由 [render] 写，供倒计时与拨号用）。 */
    private var officeText = ""
    private var stationPhone = ""

    /** 全量列表（未筛选）。chip 的件数、筛选后的提交都基于它。 */
    private var all: List<Pkg> = emptyList()

    /** 当前状态筛选：PkgAdapter.FILTER_ALL 或某个分组 id */
    private var filter = PkgAdapter.FILTER_ALL

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) load() else refreshHeaderOnly()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPickupBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tvDate.text = OfficeHours.todayLabel()
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener { load() }
        b.btnNoticeAction.setOnClickListener { noticeAction?.invoke() }
        b.rowPhone.setOnClickListener { dial(stationPhone) }
        load()
    }

    override fun onResume() {
        super.onResume()
        // 从登录页/取件码页回来时刷新
        if (!loading && isVisible) refreshHeaderOnly()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ─────────────────────── 数据 ───────────────────────

    private var noticeAction: (() -> Unit)? = null

    private fun load() {
        val ctx = context ?: return
        AccountStore.ensureMigrated(ctx)

        val accounts = AccountStore.active(ctx)
        if (accounts.isEmpty()) {
            val anyLogged = AccountStore.list(ctx).any { AccountStore.isLoggedIn(ctx, it.id) }
            showNotice(
                if (anyLogged) "账号都被停用了。去「我的」里把要用的账号开关打开。"
                else "还没有登录淘宝账号。登录一次之后就能自动同步你名下的待取件。",
                R.string.action_login,
                R.color.danger
            ) { openLogin() }
            loading = false
            render(emptyList(), 0)
            b.swipe.isRefreshing = false
            return
        }

        loading = true
        if (!b.swipe.isRefreshing) showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // homeAll() = 每个启用账号并发**翻页取全** + 补时效 + 贴备注表，
                // 再按运单号合并去重。
                // （不要退回「只拉第一页」：总数超过一页时待取件会被截掉。）
                val agg = Repo.homeAll(ctx)

                // ⚠ 网络请求期间视图可能已经被销毁（按返回键退出 / Activity 重建）。
                //   不检查就碰 `b` 会 NPE —— 而且下面 finally 里的那次崩溃在真机上极难复现。
                if (_b == null) return@launch

                adapter.showAccountLabel = accounts.size > 1

                if (agg.okAccounts.isEmpty()) {
                    handleError(agg.failed.firstOrNull()?.second ?: "NETWORK::UNKNOWN")
                    return@launch
                }

                hideNotice()
                if (agg.failed.isNotEmpty()) {
                    showNotice(
                        agg.failed.joinToString("、") { it.first.displayName } +
                                " 同步失败，其余 " + agg.okAccounts.size + " 个账号正常。",
                        R.string.action_retry,
                        R.color.warn
                    ) { load() }
                }
                render(agg.pkgs, accounts.size)
            } catch (e: MtopException) {
                if (_b != null) handleError(e.ret)
            } catch (e: Exception) {
                if (_b != null) {
                    showNotice(
                        "网络异常：${e.javaClass.simpleName}，请检查连接后重试。",
                        R.string.action_retry,
                        R.color.danger
                    ) { load() }
                    render(emptyList(), 0)
                }
            } finally {
                loading = false
                _b?.let { vb ->
                    vb.swipe.isRefreshing = false
                    vb.boxLoading.visibility = View.GONE
                    // render() 是在 loading==true 时跑的，那一刻空态被压住了；
                    // 这里补一次，否则「一件包裹都没有」时整页什么都不显示。
                    vb.boxEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** 只刷新顶部信息，不重新打网络（省流量，也避免频繁请求触发风控）。 */
    private fun refreshHeaderOnly() {
        val ctx = context ?: return
        if (AccountStore.active(ctx).isEmpty()) load() else tickCountdown()
    }

    private fun handleError(ret: String) {
        val expired = ret.contains("SESSION")
        if (expired) {
            showNotice(
                "登录态已失效（${ret.substringBefore("::")}）。点下面按钮重新登录即可，其他都不用动。",
                R.string.action_login,
                R.color.danger
            ) { openLogin() }
        } else {
            showNotice(
                Repo.humanError(ret),
                R.string.action_retry,
                R.color.danger
            ) { load() }
        }
        render(emptyList(), 0)
    }

    private fun openLogin() {
        loginLauncher.launch(Intent(requireContext(), LoginActivity::class.java))
    }

    // ─────────────────────── 渲染 ───────────────────────

    private fun render(list: List<Pkg>, accountCount: Int) {
        all = list

        // 顶部计数：到站待取为主数，其余状态做副标题
        val arrivalCount = list.count { it.group == 0 }
        val onWayCount = list.count { it.group == 1 }
        val doneCount = list.count { it.group == 2 }
        b.tvPendingCount.text = when {
            list.isEmpty() -> "暂无包裹"
            arrivalCount == 0 -> "无待取 · 在途 $onWayCount"
            else -> "$arrivalCount 件待取"
        }

        // 单账号时不必说明来源；多账号才提示这是合并后的结果
        b.tvPendingCount.contentDescription = if (accountCount > 1)
            "$accountCount 个账号共 ${list.size} 件（到站 $arrivalCount · 在途 $onWayCount · 签收 $doneCount）"
        else ""

        // 驿站：待取件里出现次数最多的那个
        val station = list.filter { it.group == 0 }
            .groupingBy { it.stationName }.eachCount()
            .filterKeys { it.isNotEmpty() }
            .maxByOrNull { it.value }?.key ?: ""

        if (station.isEmpty()) {
            b.rowStation.visibility = View.GONE
        } else {
            b.rowStation.visibility = View.VISIBLE
            b.tvStation.text = station
        }

        // 营业时间：存进字段供倒计时用。**不要**让 tickCountdown 去反解 TextView 的文案 ——
        // 这一组的营业时间为空时那行只是被 GONE，文案还留在控件里，反解就会拿到上一次的值。
        officeText = list.firstOrNull { it.officeTime.isNotEmpty() }?.officeTime ?: ""
        if (officeText.isEmpty()) {
            b.rowOffice.visibility = View.GONE
            b.tvOffice.text = ""
        } else {
            b.rowOffice.visibility = View.VISIBLE
            b.tvOffice.text = "营业时间 $officeText"
        }
        tickCountdown()

        stationPhone = list.firstOrNull { it.stationPhone.isNotEmpty() }?.stationPhone ?: ""
        if (stationPhone.isEmpty()) {
            b.rowPhone.visibility = View.GONE
        } else {
            b.rowPhone.visibility = View.VISIBLE
            b.tvPhone.text = "驿站电话 $stationPhone · 点击拨打"
        }

        b.boxEmpty.visibility = if (list.isEmpty() && !loading) View.VISIBLE else View.GONE

        bindChips()
        adapter.submitGrouped(list, filter)
    }

    // ─────────────────────── 状态筛选 chips ───────────────────────

    private fun selectFilter(group: Int) {
        filter = group
        bindChips()
        adapter.submitGrouped(all, filter)
    }

    /**
     * chip 文案 + 选中态。
     *
     * 件数直接印在 chip 上（「到站 3」），这样既做了筛选入口，又顶掉了原来那行
     * 「共 50 件（到站 3 · 在途 5 · 签收 42）」—— 同一份数字不印两遍。
     * 空分组直接藏掉：留着「到站 0」点进去是空页，不如不给。
     */
    private fun bindChips() {
        val counts = intArrayOf(
            all.count { it.group == PkgAdapter.GROUP_ARRIVAL },
            all.count { it.group == PkgAdapter.GROUP_TRANSIT },
            all.count { it.group == PkgAdapter.GROUP_DONE }
        )

        // 选中的那组变空了（件被取走了）：退回「全部」，否则用户看到空列表会以为坏了
        if (filter != PkgAdapter.FILTER_ALL && counts[filter] == 0) filter = PkgAdapter.FILTER_ALL

        val chips = listOf(
            b.chipAll to (PkgAdapter.FILTER_ALL to "全部 ${all.size}"),
            b.chipArrival to (PkgAdapter.GROUP_ARRIVAL to "到站 ${counts[0]}"),
            b.chipTransit to (PkgAdapter.GROUP_TRANSIT to "运输中 ${counts[1]}"),
            b.chipDone to (PkgAdapter.GROUP_DONE to "已签收 ${counts[2]}")
        )
        chips.forEach { (view, spec) ->
            val (group, text) = spec
            view.text = text
            view.visibility =
                if (group != PkgAdapter.FILTER_ALL && counts[group] == 0) View.GONE else View.VISIBLE

            val on = group == filter
            view.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            view.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.surface else R.color.text_sub
                )
            )
            view.setOnClickListener { selectFilter(group) }
        }
    }

    /** 一次性把待取件的码全复制走 —— 到驿站柜台前先复制，比一个个点开看快。 */
    private fun copyAllCodes(list: List<Pkg>) {
        val withCode = list.filter { it.pickupCode.isNotEmpty() }
        if (withCode.isEmpty()) {
            toast("这些件还没有取件码")
            return
        }
        val text = withCode.joinToString("\n") { "${it.title}  ${it.pickupCode}" }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("取件码", text))
        toast("已复制 ${withCode.size} 个取件码")
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun tickCountdown() {
        val left = OfficeHours.minutesToClose(officeText)
        if (left in 0..180) {
            b.tvClosing.visibility = View.VISIBLE
            b.tvClosing.text = OfficeHours.humanLeft(left)
            val urgent = left <= 60
            b.tvClosing.setBackgroundResource(
                if (urgent) R.drawable.bg_pill_danger else R.drawable.bg_pill_warn
            )
            b.tvClosing.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (urgent) R.color.danger else R.color.warn
                )
            )
        } else {
            b.tvClosing.visibility = View.GONE
        }
    }

    private fun showLoading(on: Boolean) {
        b.boxLoading.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showNotice(text: String, actionRes: Int, colorRes: Int, action: () -> Unit) {
        b.boxNotice.visibility = View.VISIBLE
        b.boxNotice.setBackgroundResource(
            if (colorRes == R.color.danger) R.drawable.bg_pill_danger else R.drawable.bg_pill_warn
        )
        b.tvNotice.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        b.tvNotice.text = text
        b.btnNoticeAction.setText(actionRes)
        noticeAction = action
    }

    private fun hideNotice() {
        b.boxNotice.visibility = View.GONE
        noticeAction = null
    }

    private fun onPkgClick(p: Pkg) {
        if (p.pickupCode.isNotEmpty()) {
            startActivity(CodeActivity.intent(requireContext(), p))
        } else if (p.pending) {
            toast("这件需要短信验证后才能看到取件码，去菜鸟 App 里看一下。")
        } else {
            // 运输中 / 已签收：切到「查快递」页并直接把这一单查出来。
            // （以前这里只 putExtra 就启动了自己，没有任何人接收那个 extra，
            //   表现是点了没反应 —— 因为 MainActivity 是 singleTop，连页面都不刷新。）
            val act = activity as? MainActivity
            if (act != null) {
                act.openSearchWith(p.mailNo)
            } else {
                startActivity(MainActivity.searchIntent(requireContext(), p.mailNo))
            }
        }
    }

    /** 走系统拨号盘，不需要 CALL_PHONE 权限，也不会直接拨出去。 */
    private fun dial(raw: String) {
        val num = raw.filter { it.isDigit() || it == '-' || it == '+' }.trim('-', '+')
        if (num.isEmpty()) {
            toast("这个驿站没留可拨打的电话")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
        } catch (e: Exception) {
            toast("没有可用的拨号应用")
        }
    }
}

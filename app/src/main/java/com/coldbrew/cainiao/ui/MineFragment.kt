package com.coldbrew.cainiao.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.Account
import com.coldbrew.cainiao.data.AccountStore
import com.coldbrew.cainiao.data.Repo
import com.coldbrew.cainiao.data.Settings
import com.coldbrew.cainiao.databinding.FragmentMineBinding
import com.coldbrew.cainiao.databinding.ItemAccountBinding
import com.coldbrew.cainiao.work.NotifyScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MineFragment : Fragment() {

    private var _b: FragmentMineBinding? = null
    private val b get() = _b!!

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                toast("登录成功")
            }
            renderAccounts()
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("没有通知权限，到系统设置里给「取件助手」打开通知")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentMineBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnLogin.setOnClickListener { openLogin(null) }   // null = 新增账号
        b.btnVerify.setOnClickListener { verifyAll() }

        b.tvAbout.text = getString(R.string.compliance_note) + "\n\n" +
                "· 可以登录多个淘宝账号，首页会把它们的包裹合并展示，同一件包裹自动去重。\n" +
                "· 取件码、驿站信息全部直接来自手机淘宝的公开 H5 网关，中间不经过任何第三方服务器。\n" +
                "· 唯一保存的东西是这台手机上的淘宝登录 cookie，只存在应用私有目录里。\n" +
                "· 登录态会不定期失效，这是服务端策略，重新登录一次即可，不会丢任何数据。"

        b.swNotify.isChecked = Settings.isNotifyEnabled(requireContext())
        b.swNotify.setOnCheckedChangeListener { _, checked ->
            Settings.setNotifyEnabled(requireContext(), checked)
            if (checked) {
                NotifyScheduler.reschedule(requireContext())
                askNotificationPermission()
            } else {
                NotifyScheduler.cancelAll(requireContext())
            }
        }

        // 「立即同步」= 把每个账号都验证一遍（比只验主账号更符合多账号预期）
        b.rowSync.setOnClickListener { verifyAll() }

        b.rowImport.setOnClickListener {
            val show = b.boxImport.visibility != View.VISIBLE
            b.boxImport.visibility = if (show) View.VISIBLE else View.GONE
            b.ivImportArrow.rotation = if (show) 90f else 0f
        }
        b.btnImport.setOnClickListener { importCookie() }

        renderAccounts()
    }

    override fun onResume() {
        super.onResume()
        renderAccounts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ─────────────────────── 账号列表 ───────────────────────

    private fun renderAccounts() {
        val ctx = context ?: return
        AccountStore.ensureMigrated(ctx)

        val accounts = AccountStore.list(ctx)
        val loggedIn = accounts.filter { AccountStore.isLoggedIn(ctx, it.id) }
        val activeCount = loggedIn.count { it.enabled }
        val primaryId = AccountStore.primary(ctx)?.id

        b.boxAccounts.removeAllViews()
        val inflater = LayoutInflater.from(ctx)

        accounts.forEach { acc ->
            val row = ItemAccountBinding.inflate(inflater, b.boxAccounts, false)
            val ok = AccountStore.isLoggedIn(ctx, acc.id)

            row.tvAvatar.text = acc.initial
            row.tvName.text = acc.displayName
            row.tvPrimary.visibility = if (acc.id == primaryId) View.VISIBLE else View.GONE

            row.tvSub.text = buildString {
                append(if (ok) "登录态有效" else "未登录")
                if (acc.savedAt > 0) {
                    append(" · ")
                    append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(acc.savedAt)))
                    append(" 更新")
                }
                if (!acc.enabled) append(" · 已停用")
            }
            row.tvSub.setTextColor(
                ContextCompat.getColor(ctx, if (ok) R.color.text_muted else R.color.danger)
            )

            // 复用视图时要先摘监听，否则会串号
            row.swEnabled.setOnCheckedChangeListener(null)
            row.swEnabled.isChecked = acc.enabled
            row.swEnabled.setOnCheckedChangeListener { _, checked ->
                AccountStore.setEnabled(ctx, acc.id, checked)
                renderAccounts()
            }

            row.root.setOnClickListener { openLogin(acc.id) }
            row.root.setOnLongClickListener {
                showAccountMenu(acc)
                true
            }

            b.boxAccounts.addView(row.root)
        }

        b.tvAccountState.text = if (accounts.isEmpty()) "淘宝账号" else "${accounts.size} 个账号"
        b.tvAccountBadge.text = "$activeCount 个启用"
        b.tvAccountBadge.setBackgroundResource(
            if (activeCount > 0) R.drawable.bg_pill_ok else R.drawable.bg_pill_danger
        )
        b.tvAccountBadge.setTextColor(
            ContextCompat.getColor(ctx, if (activeCount > 0) R.color.ok else R.color.danger)
        )

        b.tvAccountDetail.text = when {
            accounts.isEmpty() ->
                "还没有账号，点下面「添加账号」登录一次。"
            activeCount == 0 ->
                "所有账号都已停用或未登录，首页取不到包裹。点某个账号重新登录，或把开关打开。"
            activeCount == 1 ->
                "当前只启用 1 个账号。再添加一个（比如家人的），首页会把两个号的包裹合并去重展示。"
            else ->
                "$activeCount 个账号合并展示，同一件包裹只出现一次；长按账号可以改名或删除。"
        }
        b.tvSyncState.text = ""
    }

    private fun openLogin(accountId: String?) {
        val i = Intent(requireContext(), LoginActivity::class.java)
        if (accountId != null) i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, accountId)
        loginLauncher.launch(i)
    }

    private fun showAccountMenu(acc: Account) {
        val ctx = requireContext()
        val items = arrayOf("重命名（起个好记的名字）", "重新登录这个账号", "删除这个账号")
        AlertDialog.Builder(ctx)
            .setTitle(acc.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameAccount(acc)
                    1 -> openLogin(acc.id)
                    2 -> confirmRemove(acc)
                }
            }
            .show()
    }

    private fun renameAccount(acc: Account) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(acc.customName)
            hint = "例如：家人的号"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val wrap = android.widget.FrameLayout(ctx).apply {
            val lp = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val pad = (ctx.resources.displayMetrics.density * 20).toInt()
            lp.setMargins(pad, pad / 2, pad, 0)
            addView(input, lp)
        }
        AlertDialog.Builder(ctx)
            .setTitle("给这个账号起个名字")
            .setMessage("留空则显示淘宝昵称。「家人的号」这类名字会出现在首页的包裹卡片上。")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                AccountStore.rename(ctx, acc.id, input.text.toString())
                renderAccounts()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemove(acc: Account) {
        val ctx = requireContext()
        if (AccountStore.list(ctx).size <= 1) {
            toast("至少要保留一个账号")
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("删除「${acc.displayName}」？")
            .setMessage("会同时删掉这个账号保存在本机的登录凭证（淘宝服务端不受影响）。")
            .setPositiveButton("删除") { _, _ ->
                if (AccountStore.remove(ctx, acc.id)) {
                    toast("已删除")
                    renderAccounts()
                } else {
                    toast("至少要保留一个账号")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─────────────────────── 验证 ───────────────────────

    private fun verifyAll() {
        val ctx = requireContext()
        val accounts = AccountStore.list(ctx)
        if (accounts.isEmpty()) {
            toast("还没添加账号")
            return
        }
        b.tvSyncState.text = "验证中…"
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            var firstErr = ""
            for (acc in accounts) {
                val ret = try {
                    Repo.verify(ctx, acc.id)
                } catch (e: Exception) {
                    "NETWORK::" + e.javaClass.simpleName
                }
                if (ret.startsWith("SUCCESS")) {
                    ok++
                    // 网关会刷新 _m_h5_tk，顺手更新"最后同步时间"
                    AccountStore.markSaved(ctx, acc.id)
                } else if (firstErr.isEmpty()) {
                    firstErr = ret
                }
            }
            // 逐个账号验证可能要好几秒，期间视图可能已被销毁 —— 之后不能再碰 b
            if (_b == null) return@launch
            b.tvSyncState.text = if (ok == accounts.size) "全部正常" else "$ok/${accounts.size} 正常"
            toast(
                when {
                    ok == accounts.size -> "${accounts.size} 个账号都有效 ✅"
                    ok == 0 -> "全部失效：" + Repo.humanError(firstErr)
                    else -> "$ok/${accounts.size} 个账号有效，其余需要重新登录"
                }
            )
            renderAccounts()
        }
    }

    // ─────────────────────── 导入 ───────────────────────

    /** 粘贴导入：写进主账号的槽位（多账号场景下用于救活失效的那个号）。 */
    private fun importCookie() {
        val text = b.etCookie.text.toString().trim()
        if (text.length < 20) {
            toast("粘贴的内容太短，看起来不是完整 cookie")
            return
        }
        val ctx = requireContext()
        val target = AccountStore.primary(ctx)
        if (target == null) {
            toast("还没有账号")
            return
        }
        val n = AccountStore.store(ctx, target.id).mergeCookieText(text)
        if (n == 0) {
            toast("没解析出任何 cookie，检查格式是否为 k=v; k=v")
            return
        }
        toast("已导入 $n 项到「${target.displayName}」，正在验证…")
        viewLifecycleOwner.lifecycleScope.launch {
            val ret = try {
                Repo.verify(ctx, target.id)
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
            // 验证是一次网络往返，期间视图可能已被销毁
            if (_b == null) return@launch
            if (ret.startsWith("SUCCESS")) {
                AccountStore.markSaved(ctx, target.id)
                b.etCookie.setText("")
                b.boxImport.visibility = View.GONE
                toast("导入成功，登录态有效 ✅")
            } else {
                toast("导入完成但服务端仍判失效：" + Repo.humanError(ret))
            }
            renderAccounts()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}

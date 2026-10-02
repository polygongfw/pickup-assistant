package com.coldbrew.pickupassistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coldbrew.pickupassistant.R
import com.coldbrew.pickupassistant.data.MtopException
import com.coldbrew.pickupassistant.data.Repo
import com.coldbrew.pickupassistant.databinding.FragmentSearchBinding
import kotlinx.coroutines.launch

/**
 * 免登录查快递页。
 *
 * 走 pid.get 通道 —— 这个接口不需要任何 cookie，也不会触发滑块，
 * 所以即使登录态失效、甚至一次都没登录过，这页也能用。
 */
class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!
    private val adapter = TraceAdapter()

    /** 当前结果里的快递公司电话（由 [render] 写，供点击拨打用）。 */
    private var companyPhone = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentSearchBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.listTrace.layoutManager = LinearLayoutManager(requireContext())
        b.listTrace.adapter = adapter
        b.btnSearch.setOnClickListener { search() }
        b.tvPhone.setOnClickListener { dial(companyPhone) }
        b.etMailNo.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    /** 外部入口：把运单号填进输入框并直接查（首页卡片点「运输中/已签收」的件走这里）。 */
    fun submitMailNo(mailNo: String) {
        if (mailNo.isEmpty() || _b == null) return
        b.etMailNo.setText(mailNo)
        b.etMailNo.setSelection(b.etMailNo.text.length)
        search()
    }

    private fun search() {
        val ctx = context ?: return
        val mailNo = b.etMailNo.text.toString().trim()
        if (mailNo.length < 6) {
            Toast.makeText(ctx, "请输入完整的运单号", Toast.LENGTH_SHORT).show()
            return
        }

        val imm = ctx.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.etMailNo.windowToken, 0)

        b.boxLoading.visibility = View.VISIBLE
        b.cardResult.visibility = View.GONE
        b.tvHint.visibility = View.GONE
        b.btnSearch.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = Repo.queryExpress(ctx, mailNo)
                // 请求期间视图可能已经销毁（切页/退出），碰 b 会 NPE
                if (_b == null) return@launch
                render(r)
            } catch (e: MtopException) {
                if (_b != null) showHint(Repo.humanError(e.ret))
            } catch (e: Exception) {
                if (_b != null) showHint("查询失败：${e.javaClass.simpleName}")
            } finally {
                _b?.let {
                    it.boxLoading.visibility = View.GONE
                    it.btnSearch.isEnabled = true
                }
            }
        }
    }

    /** 走系统拨号盘，不需要 CALL_PHONE 权限。 */
    private fun dial(raw: String) {
        val num = raw.filter { it.isDigit() || it == '-' || it == '+' }.trim('-', '+')
        if (num.isEmpty()) return
        val ctx = context ?: return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
        } catch (_: Exception) {
            Toast.makeText(ctx, "没有可用的拨号应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(r: Repo.TraceResult) {
        b.tvHint.visibility = View.GONE
        b.cardResult.visibility = View.VISIBLE

        b.tvCompany.text = r.companyName.ifEmpty { r.companyCode.ifEmpty { "快递" } }
        b.tvMail.text = r.mailNo

        if (r.statusText.isNotEmpty()) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.text = r.statusText
            b.tvStatus.setBackgroundResource(
                if (r.nodes.isEmpty()) R.drawable.bg_pill_warn else R.drawable.bg_pill_brand
            )
            b.tvStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (r.nodes.isEmpty()) R.color.warn else R.color.brand_deep
                )
            )
        } else {
            b.tvStatus.visibility = View.GONE
        }

        if (r.companyPhone.isNotEmpty()) {
            companyPhone = r.companyPhone
            b.tvPhone.visibility = View.VISIBLE
            b.tvPhone.text = "客服电话 ${r.companyPhone} · 点击拨打"
        } else {
            companyPhone = ""
            b.tvPhone.visibility = View.GONE
        }

        if (r.nodes.isEmpty()) {
            b.listTrace.visibility = View.GONE
            b.tvHint.visibility = View.VISIBLE
            b.tvHint.text = getString(R.string.search_no_node)
        } else {
            b.listTrace.visibility = View.VISIBLE
            adapter.submit(r.nodes)
        }
    }

    private fun showHint(text: String) {
        b.cardResult.visibility = View.GONE
        b.tvHint.visibility = View.VISIBLE
        b.tvHint.text = text
    }
}

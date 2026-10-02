package com.coldbrew.pickupassistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.coldbrew.pickupassistant.R
import com.coldbrew.pickupassistant.data.AccountStore
import com.coldbrew.pickupassistant.data.NickCodec
import com.coldbrew.pickupassistant.data.QrLogin
import com.coldbrew.pickupassistant.data.QrStatus
import com.coldbrew.pickupassistant.data.Repo
import com.coldbrew.pickupassistant.data.SessionStore
import com.coldbrew.pickupassistant.databinding.ActivityQrLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 淘宝「扫码登录」页 —— 全程原生 HTTP，登录态由 Set-Cookie 直接落地，不经过 WebView 业务页。
 *
 * 与 [LoginActivity]（WebView 里输账号密码）的关系：
 *   · 默认入口就是这个页面，扫码最省事：不用输账号、不用过滑块、不用等短信；
 *   · 扫码接口不可用/风控拦了，点底部的「改用账号密码 / 短信登录」切到 [LoginActivity]，
 *     两条路写的是同一个 cookie 槽位，可以随便切。
 *
 * 接口与状态机见 [QrLogin]；二维码本身用内置的官方渲染库（assets/qrcode.js，qrcodejs）
 * 画在 WebView 里 —— 这样不用引第三方二维码依赖，也不用把 lgToken 发到任何外部服务去出图。
 *
 * ⚠ 别把二维码交给在线「二维码生成 API」：二维码内容就是本次登录的 lgToken，
 *   发给第三方等于把这次登录会话递给别人（别人扫了就把他的号登进你的 App）。必须本地画。
 */
class QrLoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityQrLoginBinding

    /**
     * 本次登录写进哪个 cookie 槽位。
     *  - 传了 [EXTRA_ACCOUNT_ID] → 覆盖那个已有账号（重新登录 / 换号）
     *  - 没传 → 先写临时槽位，**只有验证通过才登记成新账号**
     */
    private val slotId: String by lazy {
        intent.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotEmpty() }
            ?: ("tmp" + System.currentTimeMillis().toString(36))
    }

    private val isNewSlot: Boolean by lazy {
        intent.getStringExtra(EXTRA_ACCOUNT_ID).isNullOrEmpty()
    }

    private val store by lazy { SessionStore.open(this, slotId) }
    private val qr by lazy { QrLogin(store) }

    private var pollJob: Job? = null
    private var current: QrLogin.Qr? = null
    private var finished = false

    /** 「账号密码」那一路的结果要原样透传回调用方，否则账号列表不会刷新。 */
    private val pwdLogin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            finished = true
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 二维码有效期 3 分钟，屏幕别睡了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityQrLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupQrWebView()

        b.btnBack.setOnClickListener { handleBack() }
        b.btnRefresh.setOnClickListener { startQr() }
        b.btnOverlayRefresh.setOnClickListener { startQr() }
        b.btnPwd.setOnClickListener { openPwdLogin() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        startQr()
    }

    // ─────────────────────── 二维码渲染 ───────────────────────

    /**
     * 二维码画在一个纯展示用 WebView 里。
     *
     * 直接把 `assets/qrcode.js` 的源码**内联**进 HTML（而不是 `<script src="qrcode.js">`）：
     * `file:///android_asset` 的跨源读取在部分 ROM 上会被拦（`setAllowFileAccessFromFileURLs`
     * 默认 false），内联就没有这个变量。
     */
    private fun setupQrWebView() {
        b.webQr.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setOnLongClickListener { true }
        }
    }

    private fun renderQr(content: String) {
        val lib = try {
            assets.open("qrcode.js").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            failHard("二维码渲染库缺失（assets/qrcode.js）")
            return
        }
        // 用 JSON 转义，避免内容里的引号把 script 打断
        val lit = JSONObject.quote(content)
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
              html,body{margin:0;padding:0;height:100%;background:#fff;overflow:hidden}
              body{display:flex;align-items:center;justify-content:center}
              #q canvas,#q img{display:block;width:100%;height:100%}
            </style>
            </head><body><div id="q"></div>
            <script>$lib</script>
            <script>
            (function(){
              try{
                var el=document.getElementById('q');
                new QRCode(el,{width:228,height:228,colorDark:'#14171D',
                  colorLight:'#FFFFFF',correctLevel:QRCode.CorrectLevel.M}).makeCode($lit);
              }catch(e){
                document.body.innerHTML='<div style="font:11px/1.5 monospace;color:#c0392b;'
                  +'padding:10px;word-break:break-all">'+String(e)+'</div>';
              }
            })();
            </script></body></html>
        """.trimIndent()
        b.webQr.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    // ─────────────────────── 扫码主流程 ───────────────────────

    private fun startQr() {
        pollJob?.cancel()
        current = null
        b.qrOverlay.visibility = View.GONE
        b.qrLoading.visibility = View.VISIBLE
        b.tvQrStatus.text = "正在生成二维码…"
        setBadge("等待扫码", R.drawable.bg_pill_grey, R.color.text_sub)

        pollJob = lifecycleScope.launch {
            val q = try {
                withContext(Dispatchers.IO) { qr.generate() }
            } catch (e: Exception) {
                b.qrLoading.visibility = View.GONE
                showOverlay("二维码获取失败，请点「刷新二维码」重试\n" + (e.message ?: ""))
                setBadge("获取失败", R.drawable.bg_pill_danger, R.color.danger)
                return@launch
            }
            if (finished || isFinishing) return@launch
            current = q
            renderQr(q.codeContent)
            b.qrLoading.visibility = View.GONE
            b.tvQrStatus.text = "请用手机淘宝扫描上方二维码"

            pollLoop(q)
        }
    }

    /**
     * 轮询状态。区间与容错都对齐官方登录页：2s 一次、连续 5 次请求失败判死、总时长 3 分钟。
     *
     * 注意「连续失败」和「业务状态」要分开处理：官方 JS 里网络失败是 `failureTimes` 累加，
     * 拿到任何一次正常响应就清零 —— 不然网抖两下就把用户的二维码判死了。
     */
    private suspend fun pollLoop(q: QrLogin.Qr) {
        val deadline = System.currentTimeMillis() + QR_TOTAL_MS
        var failures = 0

        while (lifecycleScope.isActive && !finished && !isFinishing) {
            delay(POLL_INTERVAL_MS)
            if (System.currentTimeMillis() > deadline) {
                expired("二维码已超时，请刷新")
                return
            }

            val st = try {
                withContext(Dispatchers.IO) { qr.query(q) }
            } catch (e: Exception) {
                failures++
                if (failures >= 5) {
                    expired("网络不稳，二维码已停用，请刷新重试")
                    return
                }
                continue
            }
            failures = 0

            when (st) {
                QrStatus.NEW -> b.tvQrStatus.text = "请用手机淘宝扫描上方二维码"
                QrStatus.SCANED -> {
                    setBadge("已扫描", R.drawable.bg_pill_warn, R.color.warn)
                    b.tvQrStatus.text = "扫描成功！请在手机上点击「确认登录」"
                }
                QrStatus.CONFIRMED -> {
                    // query() 内部已跑完收尾请求（补 login cookie），这里直接验会话
                    b.tvQrStatus.text = "已确认，正在补全登录态…"
                    onConfirmed()
                    return
                }
                QrStatus.EXPIRED -> {
                    expired("二维码已过期，请刷新")
                    return
                }
                QrStatus.CANCELED -> {
                    expired("你已取消登录，可重新扫码")
                    return
                }
                QrStatus.ERROR -> {
                    expired("登录失败（可能触发风控），请刷新重试")
                    return
                }
                QrStatus.UNKNOWN -> Unit // 官方 JS 没定义的状态：继续轮询，不要误判
            }
        }
    }

    /**
     * 扫码确认后：确认服务端真的认这个登录态，再登记账号。
     *
     * 注意收尾动作（官方页面在 CONFIRMED 后补打 `asyncUrls` / 跳转 URL 拿剩余 cookie）
     * **已经在 [QrLogin.query] 里做掉了** —— 这里只剩「验证 + 登记」。
     */
    private fun onConfirmed() {
        if (finished || isFinishing) return
        b.tvQrStatus.text = "已确认，正在建立会话…"
        setBadge("已确认", R.drawable.bg_pill_ok, R.color.ok)
        b.qrOverlay.visibility = View.VISIBLE
        b.tvOverlay.text = "登录成功"
        b.btnOverlayRefresh.visibility = View.GONE

        pollJob = lifecycleScope.launch {
            var ret = ""
            for (attempt in 1..3) {
                ret = try {
                    Repo.verify(applicationContext, slotId)
                } catch (e: Exception) {
                    "NETWORK::" + e.javaClass.simpleName
                }
                if (ret.startsWith("SUCCESS")) break
                b.tvQrStatus.text = "服务端返回 ${ret.substringBefore("::")}，第 $attempt 次重试…"
                delay(2500L)
                if (finished || isFinishing) return@launch
            }

            if (!ret.startsWith("SUCCESS")) {
                b.btnOverlayRefresh.visibility = View.VISIBLE
                b.tvQrStatus.text = "扫码成功但服务端仍判失效（${ret.substringBefore("::")}），请刷新重试"
                setBadge("没生效", R.drawable.bg_pill_danger, R.color.danger)
                return@launch
            }

            finished = true
            registerAccount()
            setBadge("登录成功", R.drawable.bg_pill_ok, R.color.ok)
            b.tvQrStatus.text = "凭证已保存，正在返回…"
            toast("登录成功 ✅")
            setResult(Activity.RESULT_OK)
            delay(600L)
            if (!isFinishing) finish()
        }
    }

    // ─────────────────────── 账号登记 ───────────────────────

    /**
     * 把刚验证通过的 cookie 登记进账号注册表。
     *
     * 昵称取 `_nk_`（URL-encoded，中文常见 GBK 编码）→ 退到 `tracknick`；
     * 数字 ID 取 `unb`，[AccountStore.onLoggedIn] 靠它识别「同一个淘宝号登了两次」。
     */
    private fun registerAccount() {
        runCatching {
            store.persist()
            val nick = readNick()
            val unb = store.get("unb") ?: ""
            if (isNewSlot) {
                AccountStore.add(applicationContext, nick = nick, unb = unb, slotId = slotId)
            } else {
                AccountStore.onLoggedIn(applicationContext, slotId, nick = nick, unb = unb)
            }
        }
    }

    private fun readNick(): String {
        val cands = listOf("_nk_", "tracknick", "lgc")
        for (k in cands) {
            val v = store.get(k) ?: continue
            if (v.isEmpty()) continue
            val d = NickCodec.decode(v)
            if (d.isNotEmpty()) return d
        }
        return ""
    }

    // ─────────────────────── 界面小工具 ───────────────────────

    private fun showOverlay(msg: String) {
        b.qrOverlay.visibility = View.VISIBLE
        b.btnOverlayRefresh.visibility = View.VISIBLE
        b.tvOverlay.text = msg
    }

    private fun expired(msg: String) {
        if (finished || isFinishing) return
        b.tvQrStatus.text = msg
        showOverlay(msg)
        setBadge("已失效", R.drawable.bg_pill_warn, R.color.warn)
    }

    private fun failHard(msg: String) {
        b.qrLoading.visibility = View.GONE
        b.tvQrStatus.text = msg
        showOverlay(msg)
        setBadge("不可用", R.drawable.bg_pill_danger, R.color.danger)
    }

    private fun setBadge(text: String, bg: Int, color: Int) {
        b.tvDetect.text = text
        b.tvDetect.setBackgroundResource(bg)
        b.tvDetect.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun openPwdLogin() {
        pollJob?.cancel()
        val i = Intent(this, LoginActivity::class.java)
        intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let { i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, it) }
        pwdLogin.launch(i)
    }

    private fun handleBack() {
        setResult(if (finished) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        if (isNewSlot && !finished) {
            // 新增账号模式下没登录成功 → 撤销临时槽位，别在账号列表里留空壳
            runCatching { store.clear() }
        }
        destroyWebView()
        super.onDestroy()
    }

    /** WebView 不销毁会漏 Activity（尤其是它在布局树里的时候）。 */
    private fun destroyWebView() {
        try {
            val w: WebView = b.webQr
            w.stopLoading()
            w.loadUrl("about:blank")
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.destroy()
        } catch (_: Exception) {
            // 销毁失败不影响退出
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 要登录/覆盖的账号 id；不传 = 新增一个账号。 */
        const val EXTRA_ACCOUNT_ID = "account_id"

        /** 轮询间隔（官方 JS 是 2s） */
        private const val POLL_INTERVAL_MS = 2000L

        /** 二维码总有效期（官方 JS 是 180000ms） */
        private const val QR_TOTAL_MS = 180_000L

        fun intent(ctx: Context, accountId: String? = null): Intent =
            Intent(ctx, QrLoginActivity::class.java).apply {
                if (!accountId.isNullOrEmpty()) putExtra(EXTRA_ACCOUNT_ID, accountId)
            }
    }
}

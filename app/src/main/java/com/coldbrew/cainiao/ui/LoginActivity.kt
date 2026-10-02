package com.coldbrew.cainiao.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.AccountStore
import com.coldbrew.cainiao.data.Api
import com.coldbrew.cainiao.data.Ck
import com.coldbrew.cainiao.data.NickCodec
import com.coldbrew.cainiao.data.Repo
import com.coldbrew.cainiao.data.SessionStore
import com.coldbrew.cainiao.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * 淘宝账号登录页 —— 内嵌系统 WebView 走官方登录页，登录成功后从 CookieManager 取凭证。
 *
 * ⚡ 更省事的入口是 [QrLoginActivity]（淘宝扫码登录）：不用输账号、不用过滑块、
 *   不用等短信。本页顶部有直达按钮，两条路写同一个 cookie 槽位，可以随便切。
 *
 * 关于「淘宝账户快捷登录」（天猫/菜鸟那种跳淘宝 App 点一下就好的一键授权）：
 *   该入口已移除 —— 那条路（`TbAuthComponent` → 淘宝 `AuthEntranceActivity`
 *   的 getway 分支）的准入凭据只有一个字符串 appKey，而服务端只认「阿里为某个
 *   应用注册过」的 appKey，非注册应用一律回 `appkey not exist`。
 *   所以这里用官方 H5 登录页自带的扫码通道替代。
 *
 * ⚠ 关键实现点（踩过的坑）：
 *   淘系 cookie 常见状态是「字段还在、服务端已判失效」。如果不清掉 WebView 里的旧 cookie，
 *   页面会显示已登录、我们也会导出一份同样死掉的凭证，白跑一趟。
 *   所以进入本页先清 WebView cookie + localStorage，逼一次真实登录。
 *   同时不清 App 里已存的凭证 —— 中途返回不会造成任何损失。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    /**
     * 本次登录写进哪个 cookie 槽位。
     *  - 传了 [EXTRA_ACCOUNT_ID] → 覆盖那个已有账号（重新登录 / 换号）
     *  - 没传 → 先写临时槽位，**只有验证通过才登记成新账号**；
     *    用户半路退出不会在账号列表里留下一个空壳。
     */
    private val slotId: String by lazy {
        intent.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotEmpty() }
            ?: ("tmp" + System.currentTimeMillis().toString(36))
    }

    /** 是不是"新增账号"模式（登录成功后要落一个新账号进注册表）。 */
    private val isNewSlot: Boolean by lazy {
        intent.getStringExtra(EXTRA_ACCOUNT_ID).isNullOrEmpty()
    }

    private val store by lazy { SessionStore.open(this, slotId) }
    private val handler = Handler(Looper.getMainLooper())

    private var finished = false
    private var verifying = false
    private var verifyAttempts = 0
    private var loginFlowStarted = false

    /**
     * 从 [QrLoginActivity] 返回：那边登录成功就把结果原样透传给调用方，
     * 否则留在本页继续用账号密码登录（用户中途反悔的路径）。
     */
    private val qrLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            finished = true
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (finished || isFinishing) return
            snapshotCookies()
            if (verifying) return
            if (store.hasLogin()) {
                onLoginDetected()
                return
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupWebView()

        b.btnBack.setOnClickListener { handleBack() }

        // 扫码登录：比在本页输账号密码省事得多，放最显眼的位置
        b.btnQrLogin.setOnClickListener {
            val i = Intent(this, QrLoginActivity::class.java)
            // 覆盖已有账号时才带 id；新增模式下让那边自己开临时槽位
            if (!isNewSlot) i.putExtra(QrLoginActivity.EXTRA_ACCOUNT_ID, slotId)
            qrLoginLauncher.launch(i)
        }

        b.btnDone.setOnClickListener {
            snapshotCookies()
            if (store.hasLogin()) {
                onLoginDetected()
            } else {
                toast("还没检测到登录，请先在页面上完成登录")
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // 先看现有凭证还有没有救，能救就不折腾用户重登
        if (store.hasLogin()) {
            b.tvSubtitle.text = "正在校验现有登录态…"
            b.tvDetect.text = "校验中"
            lifecycleScope.launch {
                val ret = try {
                    Repo.verify(applicationContext, slotId)
                } catch (e: Exception) {
                    e.javaClass.simpleName
                }
                if (finished) return@launch
                if (ret.startsWith("SUCCESS")) {
                    toast("登录态还是好的，不需要重新登录")
                    registerAccount()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    startLoginFlow(ret)
                }
            }
        } else {
            startLoginFlow("")
        }
    }

    // ─────────────────────── WebView 初始化 ───────────────────────

    private fun setupWebView() {
        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = Api.UA            // 去掉 wv 标识，避免移动端页面降级
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.web, true)

        b.web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url.toString()
                // 放行 http/https，其它 scheme（tbopen:// 等 App 唤起）直接拦掉
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                b.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                b.progress.visibility = View.GONE
                snapshotCookies()
                if (!verifying && !finished && store.hasLogin()) {
                    onLoginDetected()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                b.progress.visibility = View.GONE
                if (!loginFlowStarted) return
                // 主入口打不开时退到备用登录页
                if (view.url?.contains("taobao.com") != true) {
                    view.loadUrl(FALLBACK_URL)
                }
            }
        }

        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.progress = newProgress
                if (newProgress >= 100) b.progress.visibility = View.GONE
            }
        }
    }

    private fun startLoginFlow(reason: String) {
        loginFlowStarted = true
        b.tvSubtitle.text = if (reason.isEmpty()) {
            "已清除旧登录状态，请重新登录"
        } else {
            "旧登录态已失效（${reason.substringBefore("::")}），请重新登录"
        }
        b.tvDetect.text = "等待登录"
        b.tvDetect.setBackgroundResource(R.drawable.bg_pill_grey)
        b.tvDetect.setTextColor(ContextCompat.getColor(this, R.color.text_sub))

        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            if (!isFinishing) b.web.loadUrl(START_URL)
        }
        handler.postDelayed(poll, 2500L)
    }

    // ─────────────────────── cookie 采集 ───────────────────────

    /** 从 CookieManager 里抓所有相关域的 cookie。HttpOnly 的也拿得到，这是不用 JS 读 document.cookie 的原因。 */
    private fun snapshotCookies() {
        val cm = CookieManager.getInstance()
        for (u in COOKIE_URLS) {
            val header = try {
                cm.getCookie(u)
            } catch (e: Exception) {
                null
            } ?: continue
            if (header.isBlank()) continue
            header.split(";").forEach { raw ->
                val s = raw.trim()
                val i = s.indexOf('=')
                if (i <= 0) return@forEach
                val name = s.substring(0, i).trim()
                val value = s.substring(i + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    store.put(Ck(name, value))
                }
            }
        }
    }

    // ─────────────────────── 检测到登录之后 ───────────────────────

    private fun onLoginDetected() {
        if (verifying || finished) return
        verifying = true
        handler.removeCallbacks(poll)

        b.tvDetect.text = "检测到登录"
        b.tvDetect.setBackgroundResource(R.drawable.bg_pill_ok)
        b.tvDetect.setTextColor(ContextCompat.getColor(this, R.color.ok))
        b.tvSubtitle.text = "正在建立菜鸟侧会话…"

        // 走一遍菜鸟页，把 cainiao 域与 _tb_token_ 一并带出来
        try {
            b.web.loadUrl(BOOTSTRAP_URL)
        } catch (_: Exception) {
            // 跳转失败不影响下面的导出
        }
        handler.postDelayed({ verifyAndFinish() }, 3500L)
    }

    private fun verifyAndFinish() {
        if (finished || isFinishing) return
        snapshotCookies()
        store.persist()
        verifyAttempts++

        lifecycleScope.launch {
            val ret = try {
                Repo.verify(applicationContext, slotId)
            } catch (e: Exception) {
                "NETWORK::" + e.javaClass.simpleName
            }
            if (finished || isFinishing) return@launch

            if (ret.startsWith("SUCCESS")) {
                finished = true
                registerAccount()
                b.tvDetect.text = "登录成功"
                b.tvSubtitle.text = "凭证已保存，正在返回…"
                toast("登录成功 ✅")
                setResult(Activity.RESULT_OK)
                handler.postDelayed({ if (!isFinishing) finish() }, 600)
                return@launch
            }

            if (verifyAttempts < 3) {
                b.tvDetect.text = "正在重试"
                b.tvSubtitle.text = "服务端返回 ${ret.substringBefore("::")}，第 $verifyAttempts 次重试…"
                handler.postDelayed({
                    if (!finished && !isFinishing) {
                        try {
                            b.web.loadUrl(BOOTSTRAP_URL)
                        } catch (_: Exception) {
                        }
                        handler.postDelayed({ verifyAndFinish() }, 2500L)
                    }
                }, 2000L)
            } else {
                verifying = false
                b.tvDetect.text = "没生效"
                b.tvDetect.setBackgroundResource(R.drawable.bg_pill_danger)
                b.tvDetect.setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.danger))
                b.tvSubtitle.text = "登录了但服务端仍判失效。可以再点一次「我已登录完成」，或先在下面打开一次菜鸟页面。"
                toast("服务端返回 " + ret.substringBefore("::"))
                handler.postDelayed(poll, 1500L)
            }
        }
    }

    // ─────────────────────── 账号登记 ───────────────────────

    /**
     * 把刚验证通过的 cookie 登记进账号注册表。
     *
     * 昵称取 `_nk_`（URL-encoded，中文常见 GBK 编码）→ 退到 `tracknick`；
     * 用户数字 ID 取 `unb` —— [AccountStore.onLoggedIn] 会用它识别
     * 「同一个淘宝号被登录了两次」，避免列表里出现两个一样的账号。
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
        val nk = store.get("_nk_") ?: ""
        if (nk.isNotEmpty()) return NickCodec.decode(nk)
        val tn = store.get("tracknick") ?: ""
        if (tn.isNotEmpty()) return NickCodec.decode(tn)
        return ""
    }

    // ─────────────────────── 收尾 ───────────────────────

    private fun handleBack() {
        if (!finished && b.web.canGoBack()) {
            b.web.goBack()
        } else {
            setResult(if (finished) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 新增账号模式下没登录成功 → 撤销临时槽位，别在账号列表里留一个空壳
        if (isNewSlot && !finished) {
            runCatching { store.clear() }
        }
        try {
            b.web.stopLoading()
            (b.web.parent as? android.view.ViewGroup)?.removeView(b.web)
            b.web.destroy()
        } catch (_: Exception) {
            // WebView 销毁失败不影响退出
        }
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 要登录/覆盖的账号 id；不传 = 新增一个账号。 */
        const val EXTRA_ACCOUNT_ID = "account_id"

        private const val START_URL = "https://www.taobao.com"
        private const val FALLBACK_URL = "https://login.taobao.com/member/login.jhtml"
        private const val BOOTSTRAP_URL = "https://page.cainiao.com/"

        private val COOKIE_URLS = listOf(
            "https://www.taobao.com/",
            "https://m.taobao.com/",
            "https://login.taobao.com/",
            "https://i.taobao.com/",
            "https://page.cainiao.com/",
            "https://h5api.m.taobao.com/"
        )
    }
}

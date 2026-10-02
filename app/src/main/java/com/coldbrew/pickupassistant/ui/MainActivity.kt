package com.coldbrew.pickupassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.coldbrew.pickupassistant.R
import com.coldbrew.pickupassistant.data.AccountStore
import com.coldbrew.pickupassistant.data.Settings
import com.coldbrew.pickupassistant.databinding.ActivityMainBinding
import com.coldbrew.pickupassistant.work.NotificationChannels

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "没有通知权限，「取件提醒」不会生效。可以到系统设置里给「取件助手」打开。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NotificationChannels.ensure(this)

        // 老版本的单账号 cookie 登记成账号条目（幂等，只在首次跑）
        AccountStore.ensureMigrated(this)

        b.nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_pickup -> { switchTo(TAG_PICKUP); true }
                R.id.tab_search -> { switchTo(TAG_SEARCH); true }
                R.id.tab_mine -> { switchTo(TAG_MINE); true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            b.nav.selectedItemId = R.id.tab_pickup
        }

        askNotificationPermissionOnce()

        intent?.getStringExtra(EXTRA_SEARCH_MAIL)?.takeIf { it.isNotEmpty() }?.let { openSearchWith(it) }
    }

    /**
     * 首次启动补申请通知权限。
     *
     * 提醒开关默认是开的，而 Android 13+ 权限**不申请就等于拒绝** —— 以前申请只挂在
     * 「我的」里那个开关的监听上，用户不动开关就永远不申请，于是「开关显示已开启、
     * 提醒却一条都不来」。这里补一次，并用标记保证只弹一次。
     */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!Settings.isNotifyEnabled(this)) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Settings.markAskedNotifyPermission(this)
            return
        }
        if (Settings.askedNotifyPermission(this)) return
        Settings.markAskedNotifyPermission(this)
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 已经在栈里时（singleTop）从首页卡片再点一次运输中/已签收的件走这里。
     * 原来没有这个方法、也没人读 [EXTRA_SEARCH_MAIL]，结果是「点了没反应」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SEARCH_MAIL)?.takeIf { it.isNotEmpty() }?.let { openSearchWith(it) }
    }

    /** 切到「查快递」并把运单号填进去自动查一次。 */
    fun openSearchWith(mailNo: String) {
        if (mailNo.isEmpty()) return
        b.nav.selectedItemId = R.id.tab_search
        // 上面这句触发 listener → switchTo() → commit()；不强制执行的话下面找不到 fragment
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment)?.submitMailNo(mailNo)
    }

    /** 用 show/hide 而不是 replace，切 tab 不会丢列表和滚动位置。 */
    private fun switchTo(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        var target = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (tag) {
                TAG_PICKUP -> PickupFragment()
                TAG_SEARCH -> SearchFragment()
                else -> MineFragment()
            }
            tx.add(R.id.container, target, tag)
        } else {
            tx.show(target)
        }
        fm.fragments.forEach { f ->
            if (f !== target && !f.isHidden) tx.hide(f)
        }
        tx.commit()
    }

    fun openMineTab() {
        b.nav.selectedItemId = R.id.tab_mine
    }

    companion object {
        const val TAG_PICKUP = "pickup"
        const val TAG_SEARCH = "search"
        const val TAG_MINE = "mine"

        /** 带运单号启动查快递页（用于通知点击等外部入口）。 */
        const val EXTRA_SEARCH_MAIL = "search_mail_no"

        fun searchIntent(ctx: Context, mailNo: String): Intent =
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SEARCH_MAIL, mailNo)
    }
}

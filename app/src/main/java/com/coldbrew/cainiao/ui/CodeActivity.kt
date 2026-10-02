package com.coldbrew.cainiao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.OfficeHours
import com.coldbrew.cainiao.data.Pkg
import com.coldbrew.cainiao.databinding.ActivityCodeBinding

/**
 * 取件码出示页。
 *
 * 两个设备行为是刻意做的：屏幕亮度拉满（驿站灯光下看得清）+ 常亮（掏手机时不会息屏）。
 * 都不需要权限 —— screenBrightness 只作用于本窗口，FLAG_KEEP_SCREEN_ON 只作用于本 Activity。
 */
class CodeActivity : AppCompatActivity() {

    private lateinit var b: ActivityCodeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var officeText = ""
    private var codeText = ""
    private var mailNo = ""

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCodeBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 亮度拉满 + 常亮
        val attrs = window.attributes
        attrs.screenBrightness = 1.0f
        window.attributes = attrs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mailNo = intent.getStringExtra(EX_MAIL).orEmpty()
        codeText = intent.getStringExtra(EX_CODE).orEmpty()
        officeText = intent.getStringExtra(EX_OFFICE).orEmpty()

        val station = intent.getStringExtra(EX_STATION).orEmpty()
        val company = intent.getStringExtra(EX_COMPANY).orEmpty()
        val source = intent.getStringExtra(EX_SOURCE).orEmpty()
        val usable = intent.getBooleanExtra(EX_USABLE, codeText.isNotEmpty())

        b.tvTitle.text = station.ifEmpty { "取件码" }
        b.tvStation.text = station
        b.tvStation.visibility = if (station.isEmpty()) View.GONE else View.VISIBLE

        if (usable && codeText.isNotEmpty()) {
            b.tvCode.text = codeText
            b.tvCode.visibility = View.VISIBLE
            b.tvCodeHint.visibility = View.GONE
            b.btnCopy.isEnabled = true
        } else {
            b.tvCode.visibility = View.GONE
            b.tvCodeHint.visibility = View.VISIBLE
            b.btnCopy.isEnabled = false
        }

        b.tvMail.text = if (mailNo.isEmpty()) "" else "运单号 $mailNo"
        val co = buildString {
            append(company.ifEmpty { "" })
            if (source.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(source)
            }
        }
        b.tvCompany.text = co
        b.tvCompany.visibility = if (co.isEmpty()) View.GONE else View.VISIBLE

        b.btnBack.setOnClickListener { finish() }
        b.btnCopy.setOnClickListener { copyCode() }

        updateCountdown()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 30_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun updateCountdown() {
        if (officeText.isEmpty()) {
            b.tvOffice.visibility = View.GONE
            return
        }
        b.tvOffice.visibility = View.VISIBLE
        val left = OfficeHours.minutesToClose(officeText)
        b.tvOffice.text = when {
            left < 0 && !OfficeHours.isOpen(officeText) -> "当前已打烊 · 营业时间 $officeText"
            left >= 0 -> "营业时间 $officeText · ${OfficeHours.humanLeft(left)}"
            else -> "营业时间 $officeText"
        }
    }

    private fun copyCode() {
        if (codeText.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("取件码", codeText))
        Toast.makeText(this, "已复制 $codeText", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EX_CODE = "code"
        private const val EX_MAIL = "mail"
        private const val EX_STATION = "station"
        private const val EX_COMPANY = "company"
        private const val EX_SOURCE = "source"
        private const val EX_OFFICE = "office"
        private const val EX_USABLE = "usable"

        fun intent(ctx: Context, p: Pkg): Intent = Intent(ctx, CodeActivity::class.java).apply {
            // 用 pickupCode（已处理 needSms 哨兵 → feature.stationOrderPickUpCode 的回退）
            putExtra(EX_CODE, p.pickupCode.ifEmpty { p.authCode })
            putExtra(EX_MAIL, p.mailNo)
            putExtra(EX_STATION, p.stationName)
            putExtra(EX_COMPANY, p.partnerName)
            putExtra(EX_SOURCE, p.sourceDesc)
            putExtra(EX_OFFICE, p.officeTime)
            putExtra(EX_USABLE, p.pickupCode.isNotEmpty())
        }
    }
}

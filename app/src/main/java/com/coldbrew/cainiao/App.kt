package com.coldbrew.cainiao

import android.app.Application
import com.coldbrew.cainiao.data.Settings
import com.coldbrew.cainiao.work.NotifyScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Settings.ensureDefaults(this)
        // 只在用户开着提醒时才排周期任务。关掉时排了也是空跑（Worker 第一件事就是查开关），
        // 但白占一个系统常驻任务；用户在「我的」里打开开关时会立刻补排。
        if (Settings.isNotifyEnabled(this)) NotifyScheduler.reschedule(this)
    }
}

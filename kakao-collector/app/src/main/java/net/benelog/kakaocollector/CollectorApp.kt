package net.benelog.kakaocollector

import android.app.Application

/** 프로세스 시작 시 [Settings]를 초기화한다(Activity/Service보다 먼저 onCreate). */
class CollectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }
}

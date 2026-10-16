package com.zbrowser

import android.app.Application
import android.content.Context
import com.zbrowser.data.AppDb

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
        val db: AppDb by lazy { AppDb.get(instance) }
        val prefs by lazy { instance.getSharedPreferences("zb", Context.MODE_PRIVATE) }
    }
}

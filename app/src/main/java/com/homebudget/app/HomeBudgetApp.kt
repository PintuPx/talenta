package com.homebudget.app

import android.app.Application
import com.homebudget.app.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HomeBudgetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    }
}

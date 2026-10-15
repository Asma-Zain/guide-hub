package com.guidehub

import android.app.Application
import com.guidehub.core.AppContainer

class GuideHubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

package dev.infusion.tv

import android.app.Application
import dev.infusion.tv.di.AppContainer

class InfusionTvApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        runCatching { container.close() }
        super.onTerminate()
    }
}

package com.landpoint.app

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration

class LandPointApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // osmdroid needs a writable base dir and a non-default user agent, or the
        // OSM tile servers reject the requests. load() first so the stored
        // preferences cannot overwrite the paths the store just set.
        Configuration.getInstance()
            .load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        container.offlineMapStore.configure()
    }
}

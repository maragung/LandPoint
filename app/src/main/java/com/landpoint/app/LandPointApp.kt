package com.landpoint.app

import android.app.Application
import com.landpoint.app.map.MapLibreInit

class LandPointApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Before any MapView is constructed, and here rather than on a map screen
        // because it loads the native rendering library — a cost worth paying while
        // the launcher icon is still animating instead of on the first frame of a map.
        MapLibreInit.start(this)
    }
}

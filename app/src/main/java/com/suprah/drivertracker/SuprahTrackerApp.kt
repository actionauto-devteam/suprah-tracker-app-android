package com.suprah.drivertracker

import android.app.Application
import com.suprah.drivertracker.tracking.TrackingNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SuprahTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrackingNotifications.createChannels(this)
    }
}

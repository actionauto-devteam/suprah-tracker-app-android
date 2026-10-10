package com.suprah.drivertracker.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suprah.drivertracker.core.ApplicationScope
import com.suprah.drivertracker.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** After a phone restart or an app update, carry on if the driver was on duty (spec T-3). */
class RestartReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settings(): SettingsRepository

        fun launcher(): TrackingLauncher

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val pending = goAsync()
        dependencies.applicationScope().launch {
            try {
                val current = dependencies.settings().current()
                if (current.onDuty && current.isPaired) dependencies.launcher().resume()
            } catch (_: Exception) {
                // Some Android versions don't allow it here; the app resumes tracking when it's opened (spec H-6).
            } finally {
                pending.finish()
            }
        }
    }
}

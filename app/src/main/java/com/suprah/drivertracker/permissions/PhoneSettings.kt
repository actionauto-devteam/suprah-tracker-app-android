package com.suprah.drivertracker.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The phone settings tracking depends on (spec H-7). */
data class PhoneSettingsStatus(
    val preciseLocation: Boolean,
    val allTheTime: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val locationServicesOn: Boolean,
    /** A Xiaomi, Redmi or POCO phone, which has its own Autostart switch. */
    val xiaomi: Boolean,
    /** Xiaomi's Autostart switch; null when the phone doesn't say (or isn't a Xiaomi). */
    val autostart: Boolean?,
) {
    /** Enough to start tracking while the app is open. */
    val canTrack: Boolean get() = preciseLocation && locationServicesOn

    val allGood: Boolean get() = canTrack && allTheTime && notifications && batteryUnrestricted && autostart != false
}

interface PhoneSettingsChecker {
    fun status(): PhoneSettingsStatus

    fun hasPreciseLocation(): Boolean
}

class AndroidPhoneSettingsChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhoneSettingsChecker {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun hasPreciseLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun status(): PhoneSettingsStatus {
        val power = context.getSystemService<PowerManager>()
        val location = context.getSystemService<LocationManager>()
        return PhoneSettingsStatus(
            preciseLocation = hasPreciseLocation(),
            // Before Android 10, location access already includes the background.
            allTheTime =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                hasPreciseLocation()
            },
            notifications =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            locationServicesOn = location?.let(::locationServicesOn) ?: false,
            xiaomi = isXiaomi,
            autostart = xiaomiAutostart(),
        )
    }

    private fun locationServicesOn(location: LocationManager): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && location.isLocationEnabled) ||
            location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    /** Xiaomi keeps its Autostart switch as app-op 10008 (MIUI and HyperOS); null if the phone doesn't say. */
    private fun xiaomiAutostart(): Boolean? {
        if (!isXiaomi) return null
        return try {
            val ops = context.getSystemService<AppOpsManager>() ?: return null
            val check =
                AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            when (check.invoke(ops, XIAOMI_AUTOSTART_OP, Process.myUid(), context.packageName) as Int) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val XIAOMI_AUTOSTART_OP = 10008

        val isXiaomi: Boolean =
            listOf(Build.MANUFACTURER, Build.BRAND).any { name ->
                listOf("Xiaomi", "Redmi", "POCO").any { it.equals(name, ignoreCase = true) }
            }
    }
}

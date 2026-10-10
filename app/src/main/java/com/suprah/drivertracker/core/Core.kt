package com.suprah.drivertracker.core

import javax.inject.Inject
import javax.inject.Qualifier

/** The current time; replaced in tests. */
fun interface Clock {
    fun nowMillis(): Long
}

class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Build-time settings (from BuildConfig), injected so tests can use their own. */
data class AppConfig(
    val defaultServerUrl: String,
    /** dev builds: the server address can be changed on the pairing screen (spec B-2). */
    val serverEditable: Boolean,
    /** dev builds: http is allowed, for a backend on the developer's PC. */
    val allowHttp: Boolean,
    val driverPortalUrl: String,
    val appVersion: String,
    /** Sent when pairing, so Dispatch can tell phones apart (spec P-5). */
    val deviceName: String = "",
)

/** Coroutine dispatcher for disk and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** A scope that lives as long as the app process (work that must outlive a screen). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

package com.suprah.drivertracker.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One on-duty session at a time inside the tracking service.
 *
 * Going off duty finishes the current session (send what's saved, spec T-17) in the background. If the
 * driver goes back on duty before that finishes, a new session starts and the old one's ending doesn't
 * stop the service: it only stops it when no newer start arrived ([stopServiceIfLatest]).
 */
class TrackingSessions(
    private val parent: CoroutineScope,
    private val newEngine: () -> TrackingEngine,
    private val alert: (TrackingAlert) -> Unit,
    private val stopService: () -> Unit,
    private val stopServiceIfLatest: (startId: Int) -> Unit,
) {
    private var current: Session? = null

    private class Session(val engine: TrackingEngine, val scope: CoroutineScope)

    val isActive: Boolean get() = current != null

    /** Starts a session unless one is running. Returns true when a new one started. */
    fun startIfIdle(): Boolean {
        if (current != null) return false
        val session = Session(newEngine(), childScope())
        current = session
        session.engine.start(session.scope, host(onStop = stopService))
        return true
    }

    /** Ends the current session (or just sends what's saved when none is running). */
    fun goOffDuty(startId: Int) {
        val ending = current ?: Session(newEngine(), childScope())
        current = null
        parent.launch {
            ending.engine.goOffDuty(
                ending.scope,
                host(onStop = {
                    ending.scope.cancel()
                    stopServiceIfLatest(startId)
                }),
            )
        }
    }

    private fun childScope() = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private fun host(onStop: () -> Unit) = object : TrackingHost {
        override fun alert(alert: TrackingAlert) = this@TrackingSessions.alert(alert)

        override fun stop() = onStop()
    }
}

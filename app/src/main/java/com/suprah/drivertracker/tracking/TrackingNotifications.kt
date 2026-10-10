package com.suprah.drivertracker.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.suprah.drivertracker.R
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/** Notification channels, the on-duty notification and alerts (spec B-4, T-1, T-2, T-16). */
object TrackingNotifications {
    /** Channel IDs must never change: Android keeps the driver's settings per ID. */
    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALERTS = "alerts"
    const val ONGOING_ID = 1001
    private const val ALERT_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                context.getString(R.string.channel_tracking),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_tracking_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_alerts_description) },
        )
    }

    /** Title and text of the on-duty notification, or null before Suprah first answers (spec T-16). */
    fun content(resources: Resources, state: TrackerState): Pair<String, String>? {
        val problem = state.problem
        return when {
            problem != null -> resources.getString(R.string.notification_on_duty) to ProblemText.describe(resources, problem)
            state.approval == null -> null
            state.approval == Approval.WAITING ->
                resources.getString(R.string.notification_waiting_title) to resources.getString(R.string.notification_waiting_text)
            state.gpsOn ->
                resources.getString(R.string.notification_sharing_title) to
                    if (state.lastSentAt > 0) {
                        resources.getString(
                            R.string.notification_last_sent,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSentAt)),
                        )
                    } else {
                        resources.getString(R.string.notification_starting)
                    }
            else ->
                resources.getString(R.string.notification_no_load_title) to resources.getString(R.string.notification_no_load_text)
        }
    }

    fun ongoing(context: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PENDING_FLAGS)
        val offDuty =
            PendingIntent.getService(
                context,
                1,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_GO_OFF_DUTY),
                PENDING_FLAGS,
            )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notification_action_off_duty), offDuty)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun alert(context: Context, alert: TrackingAlert) {
        val (title, text) =
            when (alert) {
                TrackingAlert.PERMISSION_NEEDED -> R.string.alert_permission_title to R.string.alert_permission_text
                TrackingAlert.RESUME -> R.string.alert_resume_title to R.string.alert_resume_text
                TrackingAlert.UNLINKED -> R.string.alert_unlinked_title to R.string.alert_unlinked_text
            }
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PENDING_FLAGS)
        val body = context.getString(text)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        try {
            context.getSystemService<NotificationManager>()?.notify(ALERT_ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed; the app shows the problem when opened.
        }
    }

    private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}

/** Plain-language wording for a [TrackingProblem] (spec T-13, T-14). */
object ProblemText {
    fun describe(resources: Resources, problem: TrackingProblem): String = when (problem) {
        TrackingProblem.StatusOffline -> resources.getString(R.string.problem_status_offline)
        is TrackingProblem.SendOffline ->
            resources.getQuantityString(R.plurals.problem_send_offline, problem.saved, problem.saved)
        is TrackingProblem.SendSlow ->
            resources.getQuantityString(R.plurals.problem_send_slow, problem.saved, problem.saved)
        TrackingProblem.TrackingOff -> resources.getString(R.string.problem_tracking_off)
        TrackingProblem.PermissionOff -> resources.getString(R.string.problem_permission_off)
        TrackingProblem.Unlinked -> resources.getString(R.string.problem_unlinked)
        is TrackingProblem.Server ->
            problem.message ?: resources.getString(R.string.problem_server_error, problem.httpStatus)
    }
}

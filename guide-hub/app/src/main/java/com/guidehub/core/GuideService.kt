package com.guidehub.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guidehub.GuideHubApp
import com.guidehub.R
import com.guidehub.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps BLE, audio and the orchestrator alive with the screen off (FR-9).
 * The notification mirrors lock state and hides all content while locked (FR-1).
 */
class GuideService : LifecycleService() {
    private val container get() = (application as GuideHubApp).container

    override fun onCreate() {
        super.onCreate()
        createChannel()
        container.start()
        startInForeground(buildNotification(locked = true, navigating = false))
        lifecycleScope.launch {
            combine(container.auth.locked, container.orchestrator.session.state) { l, s -> l to s }.collect { (locked, state) ->
                val nav = state == SessionState.NAVIGATING || state == SessionState.PAUSED
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(locked, nav))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startInForeground(n: Notification) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 30 && Permissions.hasMic(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, n, types)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(locked: Boolean, navigating: Boolean): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Locked: no destination, no session details.
        val text = when {
            locked -> getString(R.string.notif_locked)
            navigating -> getString(R.string.notif_navigating)
            else -> getString(R.string.notif_running)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "guide"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val i = Intent(context, GuideService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

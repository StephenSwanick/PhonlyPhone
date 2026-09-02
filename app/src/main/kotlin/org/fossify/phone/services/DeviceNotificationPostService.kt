package org.fossify.phone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.phone.R
import org.fossify.phone.helpers.DeviceNotificationApi

/**
 * Brief foreground so pile can use the same network grant as an open Call history.
 * Not Recents, not looked, not overlay, not AlarmManager. Android may flash a
 * silent status-bar entry for a few seconds.
 */
class DeviceNotificationPostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        Log.i(DeviceNotificationApi.TAG, "post FGS up")
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(DeviceNotificationApi.TAG, "post FGS timeout")
        stopSelf()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_call_made_vector)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "phonly_cue_post"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, DeviceNotificationPostService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(DeviceNotificationApi.TAG, "post FGS start ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, DeviceNotificationPostService::class.java)
                )
            } catch (t: Throwable) {
                Log.w(DeviceNotificationApi.TAG, "post FGS stop ${t.message}")
            }
        }
    }
}

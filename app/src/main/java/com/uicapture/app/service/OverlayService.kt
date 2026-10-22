package com.uicapture.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.uicapture.app.R
import com.uicapture.app.overlay.FloatingOverlayController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent foreground service that hosts and maintains the [FloatingOverlayController].
 * Ensures the floating capture bubble remains active across all foreground applications,
 * provides ongoing notification controls with a quick "Stop" action, and manages overlay
 * lifecycle cleanly.
 */
class OverlayService : Service() {

    internal var overlayController: FloatingOverlayController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isRunning.value = true
        Log.i(TAG, "OverlayService created")
        if (overlayController == null) {
            overlayController = FloatingOverlayController(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "OverlayService received ACTION_STOP")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        overlayController?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "OverlayService destroying")
        try {
            overlayController?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Error destroying overlay controller: ${e.message}")
        }
        overlayController = null
        if (instance === this) {
            instance = null
            _isRunning.value = false
        }
    }

    /**
     * Initializes the notification channel and posts the persistent foreground notification
     * with the "Stop" action button and tap-to-open intent.
     */
    private fun startForegroundNotification() {
        createNotificationChannel()

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        } else null

        val notificationTitle = try {
            getString(R.string.notification_channel_name)
        } catch (_: Throwable) {
            "UICapture Overlay Active"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText("Floating capture bubble is running. Tap to capture any screen.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    /**
     * Creates notification channel required on Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val channelName = try {
                    getString(R.string.notification_channel_name)
                } catch (_: Throwable) {
                    "UICapture Overlay Service"
                }
                val channelDesc = try {
                    getString(R.string.notification_channel_desc)
                } catch (_: Throwable) {
                    "Active foreground service keeping the floating capture overlay bubble responsive"
                }
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = channelDesc
                    setShowBadge(false)
                }
                notificationManager?.createNotificationChannel(channel)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_STOP = "com.uicapture.app.action.STOP_OVERLAY"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "uicapture_overlay_channel"

        private val _isRunning = MutableStateFlow(false)

        /**
         * Observable [StateFlow] representing whether [OverlayService] is active.
         */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Direct boolean property check indicating if [OverlayService] is currently running.
         */
        val isServiceRunning: Boolean get() = _isRunning.value

        /**
         * Singleton instance of the active service, or null if stopped.
         */
        @Volatile
        var instance: OverlayService? = null
            internal set

        /**
         * Starts the [OverlayService] as a foreground service across all Android versions.
         */
        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Signals the [OverlayService] to stop and remove the floating overlay.
         */
        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }

        /**
         * Test utility method to set or clear the singleton instance.
         */
        internal fun setInstanceForTesting(service: OverlayService?) {
            instance = service
            _isRunning.value = (service != null)
        }

        /**
         * Test utility method to reset all companion state.
         */
        internal fun resetForTesting() {
            instance = null
            _isRunning.value = false
        }
    }
}

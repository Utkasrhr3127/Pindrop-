package com.pindrop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Pindrop alive while files are moving, even with the screen off or another app in front.
 * It shows a small "Pindrop is working" notification and holds a Wi-Fi lock and a CPU wake lock.
 * Screens call [hold] when they start something and [release] when they finish; the service stops by itself
 * when nobody is holding it any more.
 */
class TransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = buildNote(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTE_ID, note)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        takeLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun takeLocks() {
        try {
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pindrop:transfer").apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "pindrop:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            wake?.let { if (it.isHeld) it.release() }
            wifi?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wake = null
        wifi = null
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 7301
        private const val CHANNEL = "pindrop_transfers"
        private val handler = Handler(Looper.getMainLooper())
        private var holds = 0
        private val gate = Any()

        private fun buildNote(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Pindrop")
                .setContentText("Ready to send and receive files")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .build()
        }

        /** Something is moving files (or waiting for them). Keep Pindrop alive. */
        fun hold(ctx: Context) {
            synchronized(gate) {
                holds++
                if (holds == 1) {
                    val app = ctx.applicationContext
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
                    } catch (e: Exception) {
                        // Android refused to start it (for example the app isn't visible). Transfers still run.
                    }
                }
            }
        }

        /** The thing that called [hold] is finished. */
        fun release(ctx: Context) {
            synchronized(gate) {
                if (holds > 0) holds--
                if (holds == 0) {
                    val app = ctx.applicationContext
                    // A short delay avoids stopping a service Android is still in the middle of starting.
                    handler.postDelayed({
                        synchronized(gate) {
                            if (holds == 0) app.stopService(Intent(app, TransferService::class.java))
                        }
                    }, 1500L)
                }
            }
        }
    }
}

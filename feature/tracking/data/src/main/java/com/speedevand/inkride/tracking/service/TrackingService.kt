package com.speedevand.inkride.tracking.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.speedevand.inkride.core.domain.tracking.RideAlert
import com.speedevand.inkride.core.domain.tracking.RideTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service whose sole job is to keep the app process alive while a
 * ride is being recorded. The actual tracking (GPS/sensor collection and metric
 * calculation) lives in the process-scoped `RideTracker` singleton, so it keeps
 * running even when the UI is gone — this service just guarantees the OS won't
 * kill the process behind it.
 *
 * It also collects the tracker's edge-triggered [RideAlert] stream and vibrates
 * the device, so speed/HR alerts reach the rider even when the screen is off.
 */
class TrackingService : Service() {

    // Keeps the CPU running while the (E-Ink) screen is off so GPS/sensor
    // sampling in the process-scoped RideTracker isn't suspended by Doze. This
    // is separate from the dashboard's FLAG_KEEP_SCREEN_ON, which only applies
    // while the activity is visible.
    private var wakeLock: PowerManager.WakeLock? = null

    private val rideTracker: RideTracker by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var alertJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val channelId = "tracking_channel"
        val channel = NotificationChannel(
            channelId,
            "Ride Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("InkRide")
            .setContentText("Tracking ride…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        observeAlerts()
    }

    private fun observeAlerts() {
        if (alertJob?.isActive == true) return
        alertJob = serviceScope.launch {
            rideTracker.alerts.collect { alert -> vibrateFor(alert) }
        }
    }

    /**
     * Distinct vibration patterns per alert so the rider can tell them apart
     * without looking: a single long buzz for over-speed, two short for HR-high,
     * one short for HR-low, three short for off-route.
     */
    private fun vibrateFor(alert: RideAlert) {
        val pattern = when (alert) {
            is RideAlert.OverSpeed -> longArrayOf(0, 500)
            is RideAlert.HeartRateHigh -> longArrayOf(0, 200, 150, 200)
            is RideAlert.HeartRateLow -> longArrayOf(0, 200)
            is RideAlert.OffRoute -> longArrayOf(0, 150, 100, 150, 100, 150)
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopService() {
        releaseWakeLock()
        alertJob?.cancel()
        alertJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "InkRide:TrackingWakeLock"
        // Safety timeout so the lock can never leak past a very long ride if the
        // service is killed without onDestroy. 12h comfortably covers any ride.
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    }
}

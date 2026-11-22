package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.DropCollectorApp
import com.example.MainActivity
import com.example.R
import com.example.network.NotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MiningForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.example.action.STOP_MINING_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, MiningForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MiningForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as DropCollectorApp
        app.notificationDispatcher.ensureChannels()
        acquireWakeLock()

        val initialNotification = buildNotification("Stream Drop Collector", "Monitoring Twitch & Kick drops...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            app.miningEngine.sessions.collectLatest { sessions ->
                val miningCount = sessions.count { it.minerStatus == "Mining" }
                val activeNames = sessions.filter { it.minerStatus == "Mining" }
                    .joinToString(", ") { "${it.account.platform.displayName}: ${it.progress.minedChannel.ifBlank { it.account.displayName }}" }
                val text = if (miningCount > 0) {
                    "Night/BG Mining ($miningCount): $activeNames"
                } else {
                    "Idle • ${sessions.count { it.account.enabled }} account(s) active"
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification("DropCollector Active", text))
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (wakeLock?.isHeld == true) return
        val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DropCollector::OvernightMining")
        wl.setReferenceCounted(false)
        wl.acquire(12 * 60 * 60 * 1000L) // 12-hour safety cap for overnight session
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(title: String, content: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MiningForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationDispatcher.CHANNEL_MINER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Pause Background", stopPending)
            .build()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}

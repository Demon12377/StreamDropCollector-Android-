package com.example.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.logging.AppLogger
import com.example.core.models.AppSettings
import com.example.core.models.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NotificationDispatcher(private val context: Context) {

    companion object {
        const val CHANNEL_DROPS = "drops_alerts"
        const val CHANNEL_MINER = "miner_status"
        private const val BOT_NAME = "Stream Drop Collector"
        private const val BOT_AVATAR_URL =
            "https://raw.githubusercontent.com/tsgsOFFICIAL/StreamDropCollector/master/UI/Assets/logo.png"
    }

    private val notificationId = AtomicInteger(1000)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        ensureChannels()
    }

    fun ensureChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dropsChannel = NotificationChannel(
            CHANNEL_DROPS,
            "Drop Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when drops are ready to claim, auto-claimed, or require attention."
        }
        val minerChannel = NotificationChannel(
            CHANNEL_MINER,
            "Background Mining",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status notification while mining drops in the background."
        }
        manager.createNotificationChannel(dropsChannel)
        manager.createNotificationChannel(minerChannel)
    }

    suspend fun notify(
        settings: AppSettings,
        title: String,
        message: String,
        type: NotificationType,
        details: List<Pair<String, String>> = emptyList()
    ) {
        if (!settings.quietMode) {
            showLocalNotification(title, message)
        }
        if (settings.webhookEnabled && settings.webhookUrl.isNotBlank() && settings.isWebhookEventEnabled(type)) {
            sendWebhook(settings.webhookUrl, title, message, details)
        }
    }

    private fun showLocalNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DROPS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId.incrementAndGet(), notification)
        }
    }

    suspend fun sendTestWebhook(url: String): String? =
        sendWebhook(
            url = url,
            title = BOT_NAME,
            message = "Test notification - your webhook is working.",
            details = listOf("Platform" to "Android", "Status" to "Connected")
        )

    private suspend fun sendWebhook(
        url: String,
        title: String,
        message: String,
        details: List<Pair<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            return@withContext "Invalid webhook URL"
        }

        val host = uri.host.lowercase()
        val nowIso = Instant.now().toString()
        val payloadObj: JsonObject = when {
            host.contains("discord") -> buildJsonObject {
                put("username", BOT_NAME)
                put("avatar_url", BOT_AVATAR_URL)
                put("embeds", buildJsonArray {
                    add(buildJsonObject {
                        put("title", title)
                        put("description", message)
                        put("timestamp", nowIso)
                        put("fields", JsonArray(details.map { (name, value) ->
                            buildJsonObject {
                                put("name", name)
                                put("value", value)
                                put("inline", true)
                            }
                        }))
                    })
                })
            }
            host.contains("slack") -> {
                val extra = details.joinToString("") { (k, v) -> "\n*$k:* $v" }
                buildJsonObject {
                    put("username", BOT_NAME)
                    put("icon_url", BOT_AVATAR_URL)
                    put("text", "*$title*\n$message$extra")
                }
            }
            else -> buildJsonObject {
                put("title", title)
                put("message", message)
                put("timestamp", nowIso)
                put("details", buildJsonObject {
                    details.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
        }

        runCatching {
            val req = Request.Builder()
                .url(trimmed)
                .post(payloadObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else {
                    AppLogger.warn("Webhook", "Webhook HTTP ${resp.code} host=$host")
                    "HTTP ${resp.code} ${resp.message}"
                }
            }
        }.getOrElse { ex ->
            AppLogger.warn("Webhook", "Webhook send failed: ${ex.message}")
            ex.message ?: "Request failed"
        }
    }
}

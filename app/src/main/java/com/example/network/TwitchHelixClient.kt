package com.example.network

import com.example.core.logging.AppLogger
import com.example.core.models.LiveChannelSnapshot
import com.example.core.models.TwitchDeviceCodePrompt
import com.example.core.models.TwitchHelixTokens
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TwitchHelixClient(
    private val settingsRepository: SettingsRepository,
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val CLIENT_ID = "stn3drjs9882jj68u9295v8u0x5q2k"
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var cachedTokens: TwitchHelixTokens? = null
    private var eventSubSocket: WebSocket? = null

    suspend fun hasValidToken(): Boolean {
        if (cachedTokens != null) return true
        cachedTokens = settingsRepository.getHelixTokens()
        return cachedTokens != null
    }

    suspend fun startDeviceCodeFlow(
        onPrompt: suspend (TwitchDeviceCodePrompt) -> Unit
    ): TwitchHelixTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scopes", "")
            .build()

        val req = Request.Builder()
            .url("https://id.twitch.tv/oauth2/device")
            .post(form)
            .build()

        val bodyText = okHttp.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Device code start failed (${resp.code}): $body")
            }
            body
        }

        val root = json.parseToJsonElement(bodyText).jsonObject
        val deviceCode = root["device_code"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing device_code")
        val userCode = root["user_code"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing user_code")
        val verificationUri = root["verification_uri"]?.jsonPrimitive?.content
            ?: "https://www.twitch.tv/activate"
        val completeUri = root["verification_uri_complete"]?.jsonPrimitive?.content
        val activationUrl = if (!completeUri.isNullOrBlank()) {
            completeUri
        } else {
            val sep = if (verificationUri.contains('?')) "&" else "?"
            "$verificationUri${sep}user_code=${URLEncoder.encode(userCode, "UTF-8")}"
        }
        val intervalSec = (root["interval"]?.jsonPrimitive?.intOrNull ?: 5).coerceAtLeast(3)
        val expiresInSec = root["expires_in"]?.jsonPrimitive?.intOrNull ?: 600

        onPrompt(TwitchDeviceCodePrompt(verificationUri, userCode, activationUrl))
        AppLogger.info("TwitchHelix", "Waiting for Twitch device-code approval (code=$userCode)")

        val deadline = System.currentTimeMillis() + expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSec * 1000L)
            val tokenForm = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scopes", "")
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val tokenReq = Request.Builder()
                .url("https://id.twitch.tv/oauth2/token")
                .post(tokenForm)
                .build()

            val (code, tokenBody) = okHttp.newCall(tokenReq).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }

            if (code in 200..299) {
                val obj = json.parseToJsonElement(tokenBody).jsonObject
                val tokens = TwitchHelixTokens(
                    accessToken = obj["access_token"]!!.jsonPrimitive.content,
                    refreshToken = obj["refresh_token"]!!.jsonPrimitive.content
                )
                cachedTokens = tokens
                settingsRepository.saveHelixTokens(tokens)
                AppLogger.info("TwitchHelix", "Twitch Helix authorization completed.")
                return@withContext tokens
            }

            if (!tokenBody.contains("authorization_pending", ignoreCase = true) &&
                !tokenBody.contains("slow_down", ignoreCase = true)
            ) {
                throw IllegalStateException("Device code auth failed ($code): $tokenBody")
            }
        }
        throw IllegalStateException("Device code expired before approval.")
    }

    private suspend fun refreshToken(refreshToken: String): TwitchHelixTokens? = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val req = Request.Builder()
                .url("https://id.twitch.tv/oauth2/token")
                .post(form)
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val tokens = TwitchHelixTokens(
                    accessToken = obj["access_token"]!!.jsonPrimitive.content,
                    refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                )
                cachedTokens = tokens
                settingsRepository.saveHelixTokens(tokens)
                tokens
            }
        }.getOrNull()
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val tokens = cachedTokens ?: settingsRepository.getHelixTokens()?.also { cachedTokens = it } ?: return@withContext null
        var accessToken = tokens.accessToken

        fun execute(tok: String): Pair<Int, String> {
            val req = Request.Builder()
                .url(url)
                .header("Client-Id", CLIENT_ID)
                .header("Authorization", "Bearer $tok")
                .get()
                .build()
            return okHttp.newCall(req).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, body) = execute(accessToken)
        if (code == 401 && tokens.refreshToken.isNotBlank()) {
            val refreshed = refreshToken(tokens.refreshToken) ?: return@withContext null
            accessToken = refreshed.accessToken
            val retry = execute(accessToken)
            code = retry.first
            body = retry.second
        }
        if (code !in 200..299) {
            AppLogger.warn("TwitchHelix", "Helix GET $url failed ($code)")
            return@withContext null
        }
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    suspend fun getLiveStreamsByLogins(logins: List<String>): List<LiveChannelSnapshot> {
        if (logins.isEmpty()) return emptyList()
        val results = mutableListOf<LiveChannelSnapshot>()
        logins.distinct().chunked(100).forEach { chunk ->
            val query = chunk.joinToString("&") { "user_login=${URLEncoder.encode(it.lowercase(), "UTF-8")}" }
            val obj = getJson("https://api.twitch.tv/helix/streams?$query") ?: return@forEach
            val data = obj["data"]?.jsonArray ?: return@forEach
            data.forEach { el ->
                val item = el.jsonObject
                val login = item["user_login"]?.jsonPrimitive?.content ?: return@forEach
                results += LiveChannelSnapshot(
                    login = login.lowercase(),
                    displayName = item["user_name"]?.jsonPrimitive?.content ?: login,
                    isLive = item["type"]?.jsonPrimitive?.content == "live",
                    viewerCount = item["viewer_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    gameName = item["game_name"]?.jsonPrimitive?.content,
                    title = item["title"]?.jsonPrimitive?.content
                )
            }
        }
        return results
    }

    suspend fun getTopLiveStreamsForGame(gameId: String, limit: Int = 20): List<LiveChannelSnapshot> {
        val obj = getJson("https://api.twitch.tv/helix/streams?first=${limit.coerceIn(1, 100)}&game_id=${URLEncoder.encode(gameId, "UTF-8")}")
            ?: return emptyList()
        val data = obj["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val item = el.jsonObject
            val login = item["user_login"]?.jsonPrimitive?.content ?: return@mapNotNull null
            LiveChannelSnapshot(
                login = login.lowercase(),
                displayName = item["user_name"]?.jsonPrimitive?.content ?: login,
                isLive = true,
                viewerCount = item["viewer_count"]?.jsonPrimitive?.intOrNull ?: 0,
                gameName = item["game_name"]?.jsonPrimitive?.content,
                title = item["title"]?.jsonPrimitive?.content
            )
        }
    }

    suspend fun searchGameId(name: String): String? {
        val obj = getJson("https://api.twitch.tv/helix/search/categories?first=10&query=${URLEncoder.encode(name, "UTF-8")}")
            ?: return null
        val data = obj["data"]?.jsonArray ?: return null
        val exact = data.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content.equals(name, ignoreCase = true)
        } ?: data.firstOrNull()
        return exact?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    fun connectEventSub(onNotification: (String) -> Unit) {
        if (eventSubSocket != null) return
        val req = Request.Builder().url("wss://eventsub.wss.twitch.tv/ws").build()
        eventSubSocket = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.debug("TwitchEventSub", "EventSub WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("notification")) {
                    onNotification(text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                eventSubSocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                eventSubSocket = null
            }
        })
    }

    fun disconnectEventSub() {
        eventSubSocket?.close(1000, "Client shutdown")
        eventSubSocket = null
    }
}

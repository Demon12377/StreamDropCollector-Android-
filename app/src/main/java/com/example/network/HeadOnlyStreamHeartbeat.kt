package com.example.network

import com.example.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Native Kotlin OkHttp implementation of low-bandwidth HLS `audio_only` HEAD heartbeat.
 * Unlike WebView JavaScript timers (which Android/OneUI freezes when the screen is off),
 * this runs reliably in background coroutines while consuming negligible RAM, CPU, and battery.
 */
class HeadOnlyStreamHeartbeat(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private data class StreamPlaylistState(
        val playlistUrl: String,
        val seenSegments: LinkedHashSet<String> = LinkedHashSet(),
        var lastRefreshedMs: Long = System.currentTimeMillis()
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val playlistStates = ConcurrentHashMap<String, StreamPlaylistState>()

    suspend fun pulseTwitchChannel(
        accountId: String,
        authToken: String,
        clientId: String,
        channelLogin: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanLogin = channelLogin.trim().lowercase()
        if (cleanLogin.isBlank() || authToken.isBlank()) return@withContext false

        val key = "$accountId#$cleanLogin"
        var state = playlistStates[key]
        if (state == null || System.currentTimeMillis() - state.lastRefreshedMs > 20 * 60 * 1000L) {
            val resolved = resolveTwitchAudioPlaylist(accountId, authToken, clientId, cleanLogin)
                ?: return@withContext false
            state = StreamPlaylistState(playlistUrl = resolved)
            playlistStates[key] = state
        }

        val mediaPlaylist = fetchText(accountId, state.playlistUrl)
        if (mediaPlaylist.isNullOrBlank()) {
            playlistStates.remove(key)
            return@withContext false
        }

        val segments = mediaPlaylist.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("https://") }
            .toList()

        if (segments.isEmpty()) return@withContext false

        val freshSegments = segments.filterNot { state.seenSegments.contains(it) }
        val toHead = (freshSegments.ifEmpty { segments }).takeLast(2)

        var anyOk = false
        for (segUrl in toHead) {
            if (headSegment(accountId, segUrl)) {
                anyOk = true
            }
            state.seenSegments.add(segUrl)
        }

        if (state.seenSegments.size > 160) {
            val trimmed = state.seenSegments.drop(state.seenSegments.size - 80)
            state.seenSegments.clear()
            state.seenSegments.addAll(trimmed)
        }
        anyOk
    }

    fun clearAccount(accountId: String) {
        playlistStates.keys.filter { it.startsWith("$accountId#") }.forEach { playlistStates.remove(it) }
    }

    private fun resolveTwitchAudioPlaylist(
        accountId: String,
        authToken: String,
        clientId: String,
        login: String
    ): String? {
        val gqlBody = JSONObject().apply {
            put("operationName", "PlaybackAccessToken_Template")
            put(
                "query",
                "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { streamPlaybackAccessToken(channelName: \$login, params: {platform: \"android\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } }"
            )
            put("variables", JSONObject().apply {
                put("isLive", true)
                put("login", login)
                put("isVod", false)
                put("vodID", "")
                put("playerType", "mobile")
            })
        }.toString()

        val req = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", clientId.ifBlank { "kimne78kx3ncx6brgo4mv6wki5h1ko" })
            .header("Authorization", "OAuth ${authToken.lowercase()}")
            .header("Content-Type", "application/json")
            .post(gqlBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val gqlResp = runCatching {
            okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                NetworkMeterRegistry.forAccount(accountId).recordBytes(text.length.toLong())
                if (resp.isSuccessful) text else null
            }
        }.getOrNull() ?: return null

        val tokenObj = runCatching {
            json.parseToJsonElement(gqlResp)
                .jsonObject["data"]?.jsonObject
                ?.get("streamPlaybackAccessToken")?.jsonObject
        }.getOrNull() ?: return null

        val sig = tokenObj["signature"]?.jsonPrimitive?.content.orEmpty()
        val value = tokenObj["value"]?.jsonPrimitive?.content.orEmpty()
        if (sig.isBlank() || value.isBlank()) return null

        val encToken = URLEncoder.encode(value, "UTF-8")
        val p = Random.nextInt(100_000, 9_999_999)
        val usherUrl =
            "https://usher.ttvnw.net/api/channel/hls/$login.m3u8?allow_audio_only=true&allow_source=true&sig=$sig&token=$encToken&p=$p&player=twitchweb&type=any"

        val master = fetchText(accountId, usherUrl) ?: return null
        val lines = master.lines().map { it.trim() }
        var preferNextUrl = false
        var fallbackLowestUrl: String? = null
        for (line in lines) {
            if (line.contains("audio_only", ignoreCase = true)) {
                preferNextUrl = true
                continue
            }
            if (line.startsWith("https://")) {
                if (preferNextUrl) return line
                fallbackLowestUrl = line
            }
        }
        return fallbackLowestUrl
    }

    private fun fetchText(accountId: String, url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .get()
            .build()
        return runCatching {
            okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                NetworkMeterRegistry.forAccount(accountId).recordBytes(text.length.toLong())
                if (resp.isSuccessful) text else null
            }
        }.getOrElse { ex ->
            AppLogger.debug("HeadHeartbeat", "Playlist fetch failed: ${ex.message}")
            null
        }
    }

    private fun headSegment(accountId: String, url: String): Boolean {
        val req = Request.Builder()
            .url(url)
            .head()
            .build()
        return runCatching {
            okHttp.newCall(req).execute().use { resp ->
                NetworkMeterRegistry.forAccount(accountId).recordBytes(512L)
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }
}

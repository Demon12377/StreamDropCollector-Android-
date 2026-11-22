package com.example.network

import com.example.BuildConfig
import com.example.core.logging.AppLogger
import com.example.core.models.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(includeBeta: Boolean): UpdateInfo = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        val endpoint = if (includeBeta) {
            "https://api.github.com/repos/tsgsOFFICIAL/StreamDropCollector/releases/tags/nightly"
        } else {
            "https://api.github.com/repos/tsgsOFFICIAL/StreamDropCollector/releases/latest"
        }

        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StreamDropCollector-Android")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub API ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val tagName = obj["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.content
                    ?: "https://github.com/tsgsOFFICIAL/StreamDropCollector/releases"
                val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
                val latest = tagName.ifBlank { current }
                val newer = isVersionNewer(latest, current)
                UpdateInfo(
                    currentVersion = current,
                    latestVersion = latest,
                    releaseUrl = htmlUrl,
                    releaseNotes = notes,
                    isBeta = includeBeta,
                    isUpdateAvailable = newer
                )
            }
        }.getOrElse { ex ->
            AppLogger.warn("UpdateChecker", "Update check failed: ${ex.message}")
            UpdateInfo(
                currentVersion = current,
                latestVersion = current,
                releaseUrl = "https://github.com/tsgsOFFICIAL/StreamDropCollector/releases",
                releaseNotes = "",
                isBeta = includeBeta,
                isUpdateAvailable = false
            )
        }
    }

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('-', '+').first().split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('-', '+').first().split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av > bv) return true
            if (av < bv) return false
        }
        return false
    }
}

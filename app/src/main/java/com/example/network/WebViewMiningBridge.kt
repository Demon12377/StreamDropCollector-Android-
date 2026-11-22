package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.core.logging.AppLogger
import com.example.core.models.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class CapturedTwitchHeaders(
    val clientId: String,
    val integrityToken: String,
    val deviceId: String,
    val authToken: String,
    val capturedAtMs: Long = System.currentTimeMillis()
)

class WebViewMiningBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val twitchMutex = Mutex()
    private val kickMutex = Mutex()

    @Volatile
    private var twitchWebView: WebView? = null
    @Volatile
    private var kickWebView: WebView? = null

    private val capturedHeaders = ConcurrentHashMap<String, String>()
    private val capturedHashes = ConcurrentHashMap<String, String>()
    @Volatile
    private var activeTwitchMeterAccountId: String? = null
    @Volatile
    private var activeKickMeterAccountId: String? = null

    private var twitchPageFinished: CompletableDeferred<Unit>? = null
    private var kickPageFinished: CompletableDeferred<Unit>? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun createOrGetWebView(context: Context, platform: Platform): WebView {
        val existing = if (platform == Platform.TWITCH) twitchWebView else kickWebView
        if (existing != null) return existing

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            ensureTabMuted(this)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (platform == Platform.TWITCH) {
                        twitchPageFinished?.complete(Unit)
                    } else {
                        kickPageFinished?.complete(Unit)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (platform == Platform.TWITCH && url.contains("gql.twitch.tv/gql")) {
                        request?.requestHeaders?.forEach { (k, v) ->
                            when {
                                k.equals("Client-ID", ignoreCase = true) -> capturedHeaders["Client-ID"] = v
                                k.equals("Client-Integrity", ignoreCase = true) -> capturedHeaders["Client-Integrity"] = v
                                k.equals("X-Device-Id", ignoreCase = true) -> capturedHeaders["X-Device-Id"] = v
                            }
                        }
                    }
                    val meterAcc = if (platform == Platform.TWITCH) activeTwitchMeterAccountId else activeKickMeterAccountId
                    if (!meterAcc.isNullOrBlank()) {
                        val lower = url.lowercase()
                        val estimated = when {
                            lower.contains(".ts") || lower.contains(".m4s") || lower.contains(".aac") ||
                                lower.contains("live-video.net") || lower.contains("playback.") -> 45_000L
                            lower.contains(".m3u8") || lower.contains("gql.twitch.tv") ||
                                lower.contains("web.kick.com") || lower.contains("kick.com/api") -> 4_096L
                            else -> 0L
                        }
                        if (estimated > 0L) {
                            NetworkMeterRegistry.forAccount(meterAcc).recordBytes(estimated)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        if (platform == Platform.TWITCH) {
            twitchWebView = webView
        } else {
            kickWebView = webView
        }
        return webView
    }

    private fun ensureTabMuted(webView: WebView): Boolean {
        return runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(webView, true)
                WebViewCompat.isAudioMuted(webView)
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun getCookie(url: String, name: String): String? {
        val raw = CookieManager.getInstance().getCookie(url) ?: return null
        return raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
    }

    suspend fun applyCookie(domainUrl: String, name: String, value: String) = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        cm.setCookie(domainUrl, "$name=$value; Path=/; Secure")
        cm.flush()
    }

    suspend fun clearAllCookies() = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    suspend fun captureTwitchHeaders(
        accountId: String,
        authToken: String
    ): CapturedTwitchHeaders? = twitchMutex.withLock {
        val webView = twitchWebView ?: return null
        activeTwitchMeterAccountId = accountId

        withContext(Dispatchers.Main) {
            webView.resumeTimers()
            webView.onResume()
            applyCookie("https://www.twitch.tv", "auth-token", authToken)
            capturedHeaders.clear()
            val deferred = CompletableDeferred<Unit>()
            twitchPageFinished = deferred
            webView.loadUrl("https://www.twitch.tv/drops/campaigns?t=${System.currentTimeMillis()}")
            withTimeoutOrNull(14_000L) { deferred.await() }
        }

        // Allow brief post-load GQL bursts to hit shouldInterceptRequest
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            if (capturedHeaders.containsKey("Client-ID") && capturedHeaders.containsKey("Client-Integrity")) {
                break
            }
            kotlinx.coroutines.delay(350L)
        }

        val clientId = capturedHeaders["Client-ID"] ?: "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val integrity = capturedHeaders["Client-Integrity"].orEmpty()
        val deviceId = capturedHeaders["X-Device-Id"].orEmpty()

        AppLogger.debug(
            "TwitchWebView",
            "Captured headers clientId=$clientId integrity=${AppLogger.redactToken(integrity)} deviceId=${deviceId.ifBlank { "(none)" }}"
        )

        return CapturedTwitchHeaders(
            clientId = clientId,
            integrityToken = integrity,
            deviceId = deviceId,
            authToken = authToken
        )
    }

    suspend fun suspendWebViewForLowPower(platform: Platform) = withContext(Dispatchers.Main) {
        val wv = if (platform == Platform.TWITCH) twitchWebView else kickWebView
        wv?.apply {
            stopLoading()
            loadUrl("about:blank")
            onPause()
            pauseTimers()
        }
    }

    suspend fun navigateAndPrepareTwitchStream(
        accountId: String,
        authToken: String,
        streamUrl: String,
        headOnly: Boolean
    ): Boolean = twitchMutex.withLock {
        val webView = twitchWebView ?: return false
        activeTwitchMeterAccountId = accountId

        if (headOnly) {
            withContext(Dispatchers.Main) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.pauseTimers()
            }
            return@withLock true
        }

        withContext(Dispatchers.Main) {
            webView.resumeTimers()
            webView.onResume()
            applyCookie("https://www.twitch.tv", "auth-token", authToken)
            val deferred = CompletableDeferred<Unit>()
            twitchPageFinished = deferred
            webView.loadUrl(streamUrl)
            withTimeoutOrNull(15_000L) { deferred.await() }
        }

        // Mute at Chromium WebView tab level so Twitch thinks player sound is ON (volume=1.0)
        val tabMuted = withContext(Dispatchers.Main) { ensureTabMuted(webView) }
        evaluateJs(
            webView,
            """
            (() => {
                localStorage.setItem('video-quality', '{"default":"160p30"}');
                localStorage.setItem('video-muted', '{"default":false}');
                localStorage.setItem('volume', '1');
                const btn = document.querySelector('button[data-a-target="content-classification-gate-overlay-start-watching-button"]');
                if (btn) btn.click();
                const v = document.querySelector('video');
                if (v) {
                    if ($tabMuted) {
                        v.muted = false;
                        v.volume = 1.0;
                    } else {
                        v.muted = true;
                        v.volume = 0;
                    }
                    if (v.paused) v.play().catch(() => {});
                }
                return 'ok';
            })();
            """.trimIndent()
        )
        true
    }

    suspend fun navigateKickOrigin(
        accountId: String,
        sessionToken: String,
        url: String = "https://kick.com",
        onlyIfNotOnDomain: Boolean = false
    ) =
        kickMutex.withLock {
            val webView = kickWebView ?: return@withLock
            activeKickMeterAccountId = accountId
            val currentUrl = withContext(Dispatchers.Main) {
                webView.resumeTimers()
                webView.onResume()
                webView.url.orEmpty()
            }
            if (onlyIfNotOnDomain && currentUrl.contains("kick.com", ignoreCase = true)) {
                withContext(Dispatchers.Main) {
                    applyCookie("https://kick.com", "session_token", sessionToken)
                }
                return@withLock
            }

            val sameStreamUrl = currentUrl.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true)
            if (!sameStreamUrl) {
                withContext(Dispatchers.Main) {
                    applyCookie("https://kick.com", "session_token", sessionToken)
                    val deferred = CompletableDeferred<Unit>()
                    kickPageFinished = deferred
                    webView.loadUrl(url)
                    withTimeoutOrNull(14_000L) { deferred.await() }
                }
                kotlinx.coroutines.delay(1_500L)
            }

            var playRes: String? = null
            // Poll briefly so slower mobile CPUs finish mounting Kick's React video player
            for (attempt in 1..4) {
                playRes = applyKickLowestQualityScript(webView)
                if (playRes?.contains("video:playing") == true) break
                kotlinx.coroutines.delay(900L)
            }
            AppLogger.info("KickWebView", "Opened $url -> $playRes")
        }

    suspend fun nudgeKickPlayerLowQuality() = kickMutex.withLock {
        val webView = kickWebView ?: return@withLock
        val currentUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
        if (!currentUrl.contains("kick.com", ignoreCase = true)) return@withLock
        val res = applyKickLowestQualityScript(webView)
        if (res?.contains("paused") == true || res?.contains("no-video") == true) {
            AppLogger.debug("KickWebView", "Playback nudge on $currentUrl -> $res")
        }
    }

    private suspend fun applyKickLowestQualityScript(webView: WebView): String? {
        val tabMuted = withContext(Dispatchers.Main) { ensureTabMuted(webView) }
        return evaluateJs(
            webView,
            """
            (() => {
                try {
                    localStorage.setItem('kick_video_quality', '160p');
                    localStorage.setItem('videoQuality', '160p');
                    localStorage.setItem('quality', '160p');
                    if ($tabMuted) {
                        localStorage.setItem('kick_video_muted', 'false');
                        localStorage.setItem('volume', '1');
                    }
                } catch (_) {}
                const btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                for (const b of btns) {
                    const t = (b.textContent || '').trim().toLowerCase();
                    if (t.includes('start watching') || t.includes('18 or older') || t === 'accept' || t.includes('i am 18')) {
                        b.click();
                    }
                }
                if ($tabMuted) {
                    const unmuteBtn = document.querySelector('button[aria-label*="unmute" i]');
                    if (unmuteBtn) unmuteBtn.click();
                }
                const v = document.querySelector('video');
                const needsLowerQuality = !v || !v.videoHeight || v.videoHeight > 240;
                if (needsLowerQuality) {
                    const gear = document.querySelector(
                        'button[aria-label*="quality" i], button[aria-label*="settings" i], button[data-testid*="quality" i], button[data-testid*="settings" i]'
                    );
                    const hasMenu = document.querySelector('[role="menuitemradio"]');
                    if (gear && !hasMenu) gear.click();
                    const candidates = Array.from(document.querySelectorAll('[role="menuitemradio"], [role="option"], button, li'));
                    const low = ['160p', '240p', '360p']
                        .map(q => candidates.find(el => new RegExp('\\b' + q + '\\b', 'i').test(el.textContent || '')))
                        .find(Boolean);
                    if (low) low.click();
                }
                if (v) {
                    if ($tabMuted) {
                        v.muted = false;
                        v.volume = 1.0;
                    } else {
                        v.muted = true;
                        v.volume = 0;
                    }
                    if (v.paused) {
                        v.play().catch(() => {
                            // If autoplay policy blocks unmuted start on rare OEM builds, kick-start muted then restore
                            v.muted = true;
                            v.play().then(() => {
                                if ($tabMuted) { v.muted = false; v.volume = 1.0; }
                            }).catch(() => {});
                        });
                    }
                    const qLabel = v.videoHeight ? (v.videoHeight + 'p') : 'auto';
                    return 'video:' + (v.paused ? 'paused' : 'playing') + '@' + qLabel + ' (playerSoundOn=' + (!v.muted) + ', tabMuted=$tabMuted)';
                }
                return 'no-video';
            })();
            """.trimIndent()
        )
    }

    suspend fun executeKickFetch(
        accountId: String,
        sessionToken: String,
        method: String,
        url: String,
        jsonBody: String? = null
    ): String? = kickMutex.withLock {
        val webView = kickWebView ?: run {
            AppLogger.warn("KickWebView", "Kick WebView not initialized yet for $url")
            return@withLock null
        }
        activeKickMeterAccountId = accountId

        val currentUrl = withContext(Dispatchers.Main) {
            webView.resumeTimers()
            webView.onResume()
            webView.url.orEmpty()
        }
        if (!currentUrl.contains("kick.com")) {
            withContext(Dispatchers.Main) {
                applyCookie("https://kick.com", "session_token", sessionToken)
                val deferred = CompletableDeferred<Unit>()
                kickPageFinished = deferred
                webView.loadUrl("https://kick.com")
                val loaded = withTimeoutOrNull(12_000L) { deferred.await() }
                if (loaded == null) {
                    AppLogger.warn("KickWebView", "Timed out waiting for kick.com origin load")
                }
            }
        }

        val headersJson = buildString {
            append("'Accept': 'application/json'")
            if (jsonBody != null) {
                append(", 'Content-Type': 'application/json'")
            }
            append(", 'Authorization': 'Bearer ' + decodeURIComponent(")
            append(JSONObject.quote(sessionToken))
            append(")")
        }
        val bodyPart = if (jsonBody != null) ", body: ${JSONObject.quote(jsonBody)}" else ""
        val js = """
            (async () => {
                try {
                    const r = await fetch(${JSONObject.quote(url)}, {
                        method: ${JSONObject.quote(method)},
                        credentials: 'include',
                        headers: { $headersJson }$bodyPart
                    });
                    const txt = await r.text();
                    window.__sdcLastFetch = JSON.stringify({ ok: r.ok, status: r.status, body: txt });
                } catch (e) {
                    window.__sdcLastFetch = JSON.stringify({ ok: false, status: 0, body: String(e) });
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.__sdcLastFetch = null; $js", null)
        }

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val res = evaluateJs(webView, "window.__sdcLastFetch")
            if (!res.isNullOrBlank() && res != "null") {
                val unquoted = runCatching { JSONObject("{\"v\":$res}").getString("v") }.getOrNull() ?: res
                val parsed = runCatching { JSONObject(unquoted) }.getOrNull()
                if (parsed != null && parsed.optBoolean("ok", false)) {
                    val body = parsed.optString("body")
                    NetworkMeterRegistry.forAccount(accountId).recordBytes(body.length.toLong())
                    return@withLock body
                }
                val status = parsed?.optInt("status", -1) ?: -1
                val errBody = parsed?.optString("body").orEmpty().take(180)
                AppLogger.warn("KickWebView", "$method $url failed via WebView (status=$status): $errBody")
                return@withLock null
            }
            kotlinx.coroutines.delay(250L)
        }
        AppLogger.warn("KickWebView", "$method $url timed out in WebView fetch")
        null
    }

    suspend fun scrapeKickCategoryDropsChannels(
        accountId: String,
        sessionToken: String,
        categorySlug: String
    ): List<String> = kickMutex.withLock {
        val webView = kickWebView ?: return@withLock emptyList()
        activeKickMeterAccountId = accountId

        val urlsToTry = listOf(
            "https://kick.com/category/${categorySlug.lowercase()}/drops",
            "https://kick.com/category/${categorySlug.lowercase()}"
        )
        val reserved = setOf(
            "browse", "category", "categories", "drops", "following", "subscriptions",
            "transactions", "dashboard", "settings", "search", "login", "signup",
            "terms", "privacy", "dmca", "help", "about", "video", "clips", "api"
        )

        for (targetUrl in urlsToTry) {
            withContext(Dispatchers.Main) {
                webView.resumeTimers()
                webView.onResume()
                applyCookie("https://kick.com", "session_token", sessionToken)
                val deferred = CompletableDeferred<Unit>()
                kickPageFinished = deferred
                webView.loadUrl(targetUrl)
                withTimeoutOrNull(12_000L) { deferred.await() }
            }
            kotlinx.coroutines.delay(2_000L)

            val raw = evaluateJs(
                webView,
                """
                (() => {
                    const out = [];
                    const seen = new Set();
                    const anchors = Array.from(document.querySelectorAll('a[href^="/"]'));
                    for (const a of anchors) {
                        const href = (a.getAttribute('href') || '').split('?')[0].trim();
                        if (/^\/[a-zA-Z0-9_-]{2,32}$/.test(href)) {
                            const slug = href.slice(1).toLowerCase();
                            if (!seen.has(slug)) {
                                seen.add(slug);
                                out.push(slug);
                            }
                        }
                    }
                    return out.slice(0, 20).join(',');
                })();
                """.trimIndent()
            )
            val cleaned = raw?.trim('"', ' ', '\n').orEmpty()
            if (cleaned.isNotBlank() && cleaned != "null") {
                val slugs = cleaned.split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() && it !in reserved }
                    .distinct()
                if (slugs.isNotEmpty()) {
                    AppLogger.info("KickWebView", "Directory $targetUrl found ${slugs.size} channel candidates")
                    return@withLock slugs
                }
            }
        }
        emptyList()
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            mainHandler.post {
                webView.evaluateJavascript(script) { result ->
                    deferred.complete(result)
                }
            }
            withTimeoutOrNull(8_000L) { deferred.await() }
        }
}

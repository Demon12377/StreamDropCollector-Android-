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
                    if (!meterAcc.isNullOrBlank() && (url.contains(".ts") || url.contains(".m3u8") || url.contains("gql.twitch.tv") || url.contains("web.kick.com"))) {
                        val estimated = if (url.contains(".ts")) 45_000L else 4_096L
                        NetworkMeterRegistry.forAccount(meterAcc).recordBytes(estimated)
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

        // Dismiss mature gate + force lowest quality 160p30
        evaluateJs(
            webView,
            """
            (() => {
                localStorage.setItem('video-quality', '{"default":"160p30"}');
                const btn = document.querySelector('button[data-a-target="content-classification-gate-overlay-start-watching-button"]');
                if (btn) btn.click();
                const v = document.querySelector('video');
                if (v) { v.muted = true; }
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
                kotlinx.coroutines.delay(1_200L)
            }

            // Dismiss 18+/cookie gates, pick lowest quality (160p), mute, and force playback
            evaluateJs(
                webView,
                """
                (() => {
                    try {
                        localStorage.setItem('kick_video_quality', '160p');
                        localStorage.setItem('videoQuality', '160p');
                    } catch (_) {}
                    const btns = Array.from(document.querySelectorAll('button, [role="button"]'));
                    for (const b of btns) {
                        const t = (b.textContent || '').trim().toLowerCase();
                        if (t.includes('start watching') || t.includes('18 or older') || t === 'accept' || t.includes('i am 18')) {
                            b.click();
                        }
                    }
                    const items = Array.from(document.querySelectorAll('[role="menuitemradio"], button'));
                    const low = items.find(el => /\b160p\b/i.test(el.textContent || ''));
                    if (low) low.click();
                    const v = document.querySelector('video');
                    if (v) {
                        v.muted = true;
                        v.volume = 0;
                        if (v.paused) v.play().catch(() => {});
                    }
                    return 'ok';
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
        val webView = kickWebView ?: return@withLock null
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
                withTimeoutOrNull(12_000L) { deferred.await() }
            }
        }

        val bodyPart = if (jsonBody != null) ", body: ${JSONObject.quote(jsonBody)}" else ""
        val js = """
            (async () => {
                try {
                    const r = await fetch(${JSONObject.quote(url)}, {
                        method: ${JSONObject.quote(method)},
                        credentials: 'include',
                        headers: {
                            'Accept': 'application/json',
                            'Content-Type': 'application/json',
                            'Authorization': 'Bearer ' + decodeURIComponent(${JSONObject.quote(sessionToken)})
                        }$bodyPart
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

        val deadline = System.currentTimeMillis() + 12_000L
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
                return@withLock null
            }
            kotlinx.coroutines.delay(250L)
        }
        null
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

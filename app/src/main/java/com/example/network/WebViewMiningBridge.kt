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
            settings.mediaPlaybackRequiresUserGesture = true
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

    suspend fun navigateAndPrepareTwitchStream(
        accountId: String,
        authToken: String,
        streamUrl: String,
        headOnly: Boolean
    ): Boolean = twitchMutex.withLock {
        val webView = twitchWebView ?: return false
        activeTwitchMeterAccountId = accountId

        withContext(Dispatchers.Main) {
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

        if (headOnly) {
            startTwitchHeadOnlyLoop(webView, authToken, streamUrl)
        }
        true
    }

    private suspend fun startTwitchHeadOnlyLoop(webView: WebView, authToken: String, streamUrl: String) {
        val login = streamUrl.trimEnd('/').substringAfterLast('/')
        val script = """
            (async () => {
                if (window.__sdcAudio && !window.__sdcAudio.stopped) return JSON.stringify({ ok: true, already: true });
                const login = ${JSONObject.quote(login)};
                const headers = {
                    'Client-ID': 'kimne78kx3ncx6brgo4mv6wki5h1ko',
                    'Content-Type': 'text/plain; charset=UTF-8',
                    'Authorization': 'OAuth ' + ${JSONObject.quote(authToken)}
                };
                const payload = {
                    operationName: 'PlaybackAccessToken_Template',
                    query: 'query PlaybackAccessToken_Template(${'$'}login: String!, ${'$'}isLive: Boolean!, ${'$'}vodID: ID!, ${'$'}isVod: Boolean!, ${'$'}playerType: String!) { streamPlaybackAccessToken(channelName: ${'$'}login, params: {platform: "android", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isLive) { value signature __typename } }',
                    variables: { isLive: true, login, isVod: false, vodID: '', playerType: 'mobile' }
                };
                const state = { segments: 0, bytes: 0, lastSegmentAt: null, lastError: null, stopped: false, timer: null };
                window.__sdcAudio = state;
                let playlistUrl = null;
                const seen = new Set();
                const resolveUrl = async () => {
                    const r = await fetch('https://gql.twitch.tv/gql', { method: 'POST', headers, body: JSON.stringify(payload) });
                    const token = (await r.json())?.data?.streamPlaybackAccessToken;
                    if (!token?.value || !token?.signature) throw new Error('no playback token');
                    const u = new URL('https://usher.ttvnw.net/api/channel/hls/' + login + '.m3u8');
                    u.searchParams.set('allow_audio_only', 'true');
                    u.searchParams.set('token', token.value);
                    u.searchParams.set('sig', token.signature);
                    u.searchParams.set('p', String(Math.floor(Math.random() * 1000000)));
                    u.searchParams.set('player', 'twitchweb');
                    u.searchParams.set('type', 'any');
                    const m = await fetch(u.toString());
                    const lines = (await m.text()).split('\n');
                    let found = false;
                    for (const l of lines) {
                        if (l.includes('audio_only')) found = true;
                        if (found && l.startsWith('https://')) return l.trim();
                    }
                    throw new Error('no audio_only playlist');
                };
                const poll = async () => {
                    if (state.stopped) return;
                    try {
                        if (!playlistUrl) playlistUrl = await resolveUrl();
                        const res = await fetch(playlistUrl);
                        if (!res.ok) throw new Error('playlist ' + res.status);
                        const txt = await res.text();
                        const urls = txt.split('\n').map(x => x.trim()).filter(x => x.startsWith('https://'));
                        const fresh = urls.filter(x => !seen.has(x));
                        fresh.forEach(x => seen.add(x));
                        for (const seg of fresh.slice(-2)) {
                            const h = await fetch(seg, { method: 'HEAD' });
                            if (h.ok) {
                                state.segments++;
                                state.bytes += 512;
                                state.lastSegmentAt = Date.now();
                            }
                        }
                        if (seen.size > 200) [...seen].slice(0, 100).forEach(x => seen.delete(x));
                    } catch (e) {
                        state.lastError = String(e.message || e);
                        playlistUrl = null;
                    }
                    state.timer = setTimeout(poll, 10000);
                };
                await poll();
                const playBtn = document.querySelector("button[data-a-target='player-play-pause-button']");
                if (playBtn && playBtn.getAttribute('data-a-player-state') === 'playing') playBtn.click();
                return JSON.stringify({ ok: true, segments: state.segments });
            })();
        """.trimIndent()
        evaluateJs(webView, script)
    }

    suspend fun navigateKickOrigin(accountId: String, sessionToken: String, url: String = "https://kick.com") =
        kickMutex.withLock {
            val webView = kickWebView ?: return@withLock
            activeKickMeterAccountId = accountId
            withContext(Dispatchers.Main) {
                applyCookie("https://kick.com", "session_token", sessionToken)
                val deferred = CompletableDeferred<Unit>()
                kickPageFinished = deferred
                webView.loadUrl(url)
                withTimeoutOrNull(14_000L) { deferred.await() }
            }
            evaluateJs(
                webView,
                """
                (() => {
                    const v = document.querySelector('video');
                    if (v) { v.muted = true; }
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

        val currentUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
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

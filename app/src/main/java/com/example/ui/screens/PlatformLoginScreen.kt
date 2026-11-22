package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.logging.AppLogger
import com.example.core.models.Platform
import com.example.ui.theme.KickGreen
import com.example.ui.theme.LocalDropColors
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.TwitchPurple
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformLoginScreen(
    platform: Platform,
    existingAccountId: String?,
    onLoginComplete: (Platform, String?, String, String, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDropColors.current
    val initialUrl = if (platform == Platform.TWITCH) "https://www.twitch.tv/login" else "https://kick.com/"
    val brandColor = if (platform == Platform.TWITCH) TwitchPurple else KickGreen

    var currentUrl by remember { mutableStateOf(initialUrl) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var detectedToken by remember { mutableStateOf<String?>(null) }
    var detectedCookieHeader by remember { mutableStateOf("") }
    var detectedUsername by remember { mutableStateOf("") }
    var hasAutoSubmitted by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun finishCapture() {
        val token = detectedToken ?: return
        if (hasAutoSubmitted) return
        hasAutoSubmitted = true
        runCatching { CookieManager.getInstance().flush() }
        val finalName = detectedUsername.ifBlank { "${platform.displayName} User" }
        AppLogger.info(
            "Login",
            "${platform.displayName} session captured for $finalName (cookieLen=${detectedCookieHeader.length})"
        )
        onLoginComplete(
            platform,
            existingAccountId,
            token,
            finalName,
            detectedCookieHeader
        )
        onBack()
    }

    // Poll CookieManager every 1.2s for platform auth cookie (mirrors PlatformLoginDetector.cs)
    LaunchedEffect(platform, currentUrl) {
        while (!hasAutoSubmitted) {
            val cookieManager = CookieManager.getInstance()
            val domainUrl = if (platform == Platform.TWITCH) "https://www.twitch.tv" else "https://kick.com"
            val cookieHeader = cookieManager.getCookie(domainUrl).orEmpty()

            if (platform == Platform.TWITCH) {
                val match = Regex("""(?:^|;\s*)auth-token=([^;]+)""").find(cookieHeader)
                val rawToken = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (rawToken.isNotBlank()) {
                    detectedToken = rawToken
                    detectedCookieHeader = cookieHeader
                    val loginCookie = Regex("""(?:^|;\s*)(?:login|name)=([^;]+)""")
                        .find(cookieHeader)?.groupValues?.getOrNull(1)
                    if (!loginCookie.isNullOrBlank()) {
                        detectedUsername = URLDecoder.decode(loginCookie, StandardCharsets.UTF_8.name())
                    }
                    val urlLower = currentUrl.lowercase()
                    val leftLoginFlow = urlLower.contains("twitch.tv") &&
                        !urlLower.contains("/login") &&
                        !urlLower.contains("/passport") &&
                        !urlLower.contains("authorize")
                    if (leftLoginFlow && detectedUsername.isNotBlank()) {
                        finishCapture()
                    }
                }
            } else {
                val match = Regex("""(?:^|;\s*)session_token=([^;]+)""").find(cookieHeader)
                val encodedToken = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (encodedToken.isNotBlank()) {
                    val decoded = runCatching {
                        URLDecoder.decode(encodedToken, StandardCharsets.UTF_8.name())
                    }.getOrDefault(encodedToken)
                    detectedToken = decoded
                    detectedCookieHeader = cookieHeader

                    webViewRef?.evaluateJavascript(
                        "(function(){try{fetch('/api/v1/user').then(r=>r.ok?r.json():null).then(d=>{if(d&&d.username)window.__kickUser=d.username;});return window.__kickUser||'';}catch(e){return '';}})()"
                    ) { jsRes ->
                        val cleaned = jsRes?.trim('"', ' ', '\n').orEmpty()
                        if (cleaned.isNotBlank() && cleaned != "null") {
                            detectedUsername = cleaned
                        }
                    }
                }
            }
            delay(1200)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.mainBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sign in to ${platform.displayName}",
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.headerBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = brandColor
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(10.dp),
                color = colors.cardBackground,
                border = BorderStroke(
                    1.dp,
                    if (detectedToken != null) StatusOnline.copy(alpha = 0.7f) else colors.border
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (detectedToken != null) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (detectedToken != null) StatusOnline else brandColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (detectedToken != null) {
                                val who = if (detectedUsername.isNotBlank()) " ($detectedUsername)" else ""
                                "${platform.displayName} session captured$who."
                            } else {
                                "Sign in inside ${platform.displayName} below; session cookies are detected automatically."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                    }

                    if (detectedToken != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { finishCapture() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("finish_platform_login_button")
                        ) {
                            Text("Use Session", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                currentUrl = url.orEmpty()
                                CookieManager.getInstance().flush()
                            }
                        }
                        loadUrl(initialUrl)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

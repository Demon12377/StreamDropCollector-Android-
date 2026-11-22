package com.example.network

import android.webkit.CookieManager
import com.example.core.logging.AppLogger
import com.example.core.models.DropsCampaign
import com.example.core.models.DropsReward
import com.example.core.models.EligibleStreamer
import com.example.core.models.KickChallenge
import com.example.core.models.KickLevelProgress
import com.example.core.models.LiveChannelSnapshot
import com.example.core.models.Platform
import com.example.data.KnownGameEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class KickCampaignFetchResult(
    val campaigns: List<DropsCampaign>,
    val knownGames: List<KnownGameEntry>,
    val username: String? = null,
    val apiReachable: Boolean = true
)

class KickApiClient(
    private val webViewBridge: WebViewMiningBridge,
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val emoteRegex = Regex("\\bemotes?\\b|\\braffles?\\b|\\btickets?\\b|^x\\d+\\s+entr(y|ies)$", RegexOption.IGNORE_CASE)
    private val userIds = ConcurrentHashMap<String, Long>()
    private val unlinkedClaims = ConcurrentHashMap.newKeySet<String>()

    fun isClaimBlockedUnlinked(accountId: String, campaignId: String, rewardId: String): Boolean =
        unlinkedClaims.contains("$accountId#$campaignId#$rewardId")

    private fun buildKickCookieHeader(sessionToken: String): Pair<String, String?> {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val rawMain = cm?.getCookie("https://kick.com").orEmpty()
        val rawWeb = cm?.getCookie("https://web.kick.com").orEmpty()
        val merged = linkedMapOf<String, String>()
        listOf(rawMain, rawWeb).forEach { header ->
            header.split(';').forEach { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    merged[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
            }
        }
        if (sessionToken.isNotBlank() && !merged.containsKey("session_token")) {
            merged["session_token"] = sessionToken
        }
        val xsrf = merged["XSRF-TOKEN"]?.let { raw ->
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        val header = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return header to xsrf
    }

    private suspend fun fetchAuthenticated(
        accountId: String,
        sessionToken: String,
        method: String,
        url: String,
        bodyJson: String? = null
    ): String? {
        // 1. Fast OkHttp attempt with live WebView CookieManager cookies (cf_clearance + session_token)
        val (directBody, httpCode) = withContext(Dispatchers.IO) {
            val bearer = runCatching { URLDecoder.decode(sessionToken, "UTF-8") }.getOrDefault(sessionToken)
            val (cookieHeader, xsrfToken) = buildKickCookieHeader(sessionToken)
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $bearer")
                .header("Origin", "https://kick.com")
                .header("Referer", "https://kick.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")

            if (cookieHeader.isNotBlank()) {
                builder.header("Cookie", cookieHeader)
            }
            if (!xsrfToken.isNullOrBlank()) {
                builder.header("X-XSRF-TOKEN", xsrfToken)
            }

            when (method.uppercase()) {
                "POST" -> builder.post((bodyJson ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
                else -> builder.get()
            }

            runCatching {
                okHttp.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    NetworkMeterRegistry.forAccount(accountId).recordBytes(text.length.toLong())
                    if (resp.isSuccessful) {
                        text to resp.code
                    } else {
                        AppLogger.warn("KickHttp", "$method $url -> HTTP ${resp.code}: ${text.take(150)}")
                        null to resp.code
                    }
                }
            }.getOrElse { ex ->
                AppLogger.warn("KickHttp", "$method $url exception: ${ex.message}")
                null to 0
            }
        }
        if (!directBody.isNullOrBlank()) return directBody

        // 2. Fallback to in-page WebView fetch if blocked (e.g. HTTP 403 Cloudflare or 0)
        if (httpCode == 403 || httpCode == 0) {
            val viaWebView = webViewBridge.executeKickFetch(accountId, sessionToken, method, url, bodyJson)
            if (!viaWebView.isNullOrBlank()) return viaWebView
        }
        return null
    }

    suspend fun fetchUserProfile(accountId: String, sessionToken: String): Pair<Long?, String?> {
        val raw = fetchAuthenticated(accountId, sessionToken, "GET", "https://kick.com/api/v1/user")
            ?: return null to null
        return runCatching {
            var root = json.parseToJsonElement(raw).jsonObject
            root["data"]?.let { if (it is JsonObject) root = it }
            val id = root["id"]?.jsonPrimitive?.longOrNull
            val username = root["username"]?.jsonPrimitive?.content
            if (id != null) userIds[accountId] = id
            id to username
        }.getOrDefault(null to null)
    }

    suspend fun fetchActiveCampaigns(accountId: String, sessionToken: String): KickCampaignFetchResult {
        val campaignsJson = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://web.kick.com/api/v1/drops/campaigns"
        ) ?: run {
            AppLogger.warn("KickDrops", "Failed to load https://web.kick.com/api/v1/drops/campaigns")
            return KickCampaignFetchResult(emptyList(), emptyList(), apiReachable = false)
        }

        val knownGames = mutableListOf<KnownGameEntry>()
        val campaigns = mutableListOf<DropsCampaign>()

        runCatching {
            val doc = json.parseToJsonElement(campaignsJson).jsonObject
            val data = doc["data"]?.jsonArray ?: return@runCatching
            data.forEach { el ->
                val c = el.jsonObject
                val category = c["category"] as? JsonObject
                val catId = category?.get("id")?.jsonPrimitive?.content?.trim()
                val catSlug = category?.get("slug")?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                val catName = category?.get("name")?.jsonPrimitive?.content.orEmpty()
                if (catSlug.isNotBlank() && catName.isNotBlank()) {
                    knownGames += KnownGameEntry(Platform.KICK, catSlug, catName)
                }

                if (c["status"]?.jsonPrimitive?.content != "active") return@forEach

                val rawRewards = c["rewards"]?.jsonArray ?: return@forEach
                val rewards = rawRewards.mapNotNull { rEl ->
                    val r = rEl.jsonObject
                    val rId = r["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val rName = r["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val img = r["image_url"]?.jsonPrimitive?.content
                    val req = r["required_units"]?.jsonPrimitive?.intOrNull ?: 0
                    if (req <= 0) return@mapNotNull null
                    DropsReward(
                        id = rId,
                        name = rName,
                        imageUrl = if (img.isNullOrBlank()) null else if (img.startsWith("http")) img else "https://ext.cdn.kick.com/$img",
                        requiredMinutes = req,
                        isEmote = emoteRegex.containsMatchIn(rName.trim())
                    )
                }
                if (rewards.isEmpty()) return@forEach

                val streamers = mutableListOf<EligibleStreamer>()
                val connectUrls = mutableListOf<String>()
                val channels = c["channels"] as? JsonArray
                channels?.forEach { chEl ->
                    val ch = chEl.jsonObject
                    val slug = ch["slug"]?.jsonPrimitive?.content
                        ?: ch["user"]?.jsonObject?.get("username")?.jsonPrimitive?.content
                    if (!slug.isNullOrBlank()) {
                        val lower = slug.lowercase()
                        val url = "https://kick.com/$lower"
                        connectUrls += url
                        streamers += EligibleStreamer(login = lower, displayName = slug, url = url)
                    }
                }

                var isGeneral = false
                if (category == null && connectUrls.isEmpty()) {
                    connectUrls += "https://kick.com/browse?sort=viewers_high_to_low"
                    isGeneral = true
                } else if (connectUrls.isEmpty() && catSlug.isNotBlank()) {
                    connectUrls += "https://kick.com/category/$catSlug/drops"
                    isGeneral = true
                }

                val gameName = if (category != null) catName else c["organization"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Kick"
                val gameImg = if (category != null) category["image_url"]?.jsonPrimitive?.content else c["organization"]?.jsonObject?.get("logo_url")?.jsonPrimitive?.content

                campaigns += DropsCampaign(
                    id = c["id"]!!.jsonPrimitive.content,
                    name = c["name"]!!.jsonPrimitive.content,
                    slug = catSlug,
                    gameId = catId,
                    gameName = gameName,
                    gameImageUrl = gameImg,
                    startsAtEpochMs = parseIso(c["starts_at"]?.jsonPrimitive?.content),
                    endsAtEpochMs = parseIso(c["ends_at"]?.jsonPrimitive?.content),
                    rewards = rewards,
                    platform = Platform.KICK,
                    connectUrls = connectUrls.distinct(),
                    isGeneralDrop = isGeneral,
                    eligibleStreamers = streamers
                )
            }
        }.onFailure {
            AppLogger.warn("KickDrops", "Failed parsing Kick campaigns: ${it.message}")
        }

        // Merge progress
        val progressJson = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://web.kick.com/api/v1/drops/progress"
        )

        if (!progressJson.isNullOrBlank()) {
            runCatching {
                val progDoc = json.parseToJsonElement(progressJson).jsonObject
                val progArr = progDoc["data"]?.jsonArray ?: return@runCatching
                progArr.forEach { itemEl ->
                    val item = itemEl.jsonObject
                    val campId = item["id"]?.jsonPrimitive?.content ?: return@forEach
                    val idx = campaigns.indexOfFirst { it.id == campId }
                    if (idx < 0) return@forEach

                    val connectUrl = item["connect_url"]?.jsonPrimitive?.content
                    val appConnected = item["user_app_connected"]?.jsonPrimitive?.booleanOrNull
                    val needsLink = !connectUrl.isNullOrBlank() && appConnected == false
                    val progressUnits = item["progress_units"]?.jsonPrimitive?.intOrNull ?: 0
                    val progRewards = item["rewards"]?.jsonArray ?: JsonArray(emptyList())

                    var current = campaigns[idx].copy(needsAccountLink = needsLink)
                    val updatedRewards = current.rewards.map { r ->
                        val matched = progRewards.firstOrNull {
                            it.jsonObject["id"]?.jsonPrimitive?.content == r.id
                        }?.jsonObject
                        if (matched != null) {
                            val claimed = matched["claimed"]?.jsonPrimitive?.booleanOrNull ?: r.isClaimed
                            r.copy(
                                progressMinutes = if (claimed) r.requiredMinutes else minOf(progressUnits, r.requiredMinutes),
                                isClaimed = claimed
                            )
                        } else r
                    }
                    current = current.copy(rewards = updatedRewards)
                    campaigns[idx] = current
                }
            }
        }

        return KickCampaignFetchResult(campaigns, knownGames)
    }

    suspend fun claimDrop(accountId: String, sessionToken: String, campaignId: String, rewardId: String): Boolean {
        val body = buildJsonObject {
            put("reward_id", rewardId)
            put("campaign_id", campaignId)
        }.toString()
        val resp = fetchAuthenticated(
            accountId,
            sessionToken,
            "POST",
            "https://web.kick.com/api/v1/drops/claim",
            body
        )
        if (resp != null) return true
        // Fallback: flag unlinked if campaign reports missing link
        return false
    }

    suspend fun fetchLevelProgress(accountId: String, sessionToken: String): KickLevelProgress? {
        val raw = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://web.kick.com/api/v1/gamification/user/level"
        ) ?: return null
        return runCatching {
            var root = json.parseToJsonElement(raw).jsonObject
            root["data"]?.let { if (it is JsonObject) root = it }
            val level = root["level"]?.jsonPrimitive?.intOrNull ?: return null
            val progressXp = root["progress_xp"]?.jsonPrimitive?.longOrNull ?: 0L
            val toNext = root["xp_to_next_level"]?.jsonPrimitive?.longOrNull ?: 0L
            val totalXp = root["total_xp"]?.jsonPrimitive?.longOrNull
            val badgeUrl = root["badge"]?.jsonObject?.get("image_url")?.jsonPrimitive?.content
            KickLevelProgress(level, progressXp, toNext, totalXp, badgeUrl)
        }.getOrNull()
    }

    suspend fun fetchChallenges(accountId: String, sessionToken: String): List<KickChallenge> {
        val raw = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://web.kick.com/api/v1/gamification/challenges"
        ) ?: return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val data = root["data"]?.jsonArray ?: return@runCatching emptyList()
            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val cond = obj["condition"]?.jsonObject
                val window = obj["window"]?.jsonObject
                KickChallenge(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    recurrence = obj["recurrence"]?.jsonPrimitive?.content.orEmpty(),
                    status = obj["status"]?.jsonPrimitive?.content.orEmpty(),
                    conditionType = cond?.get("type")?.jsonPrimitive?.content.orEmpty(),
                    progress = cond?.get("progress")?.jsonPrimitive?.longOrNull ?: 0L,
                    threshold = cond?.get("threshold")?.jsonPrimitive?.longOrNull ?: 0L,
                    endsAtEpochMs = window?.get("ends_at")?.jsonPrimitive?.content?.let { parseIso(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchStreakDays(accountId: String, sessionToken: String): Int? {
        val uid = userIds[accountId] ?: fetchUserProfile(accountId, sessionToken).first ?: return null
        val raw = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://web.kick.com/api/v1/gamification/users/$uid/streak"
        ) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root["data"]?.jsonObject?.get("length_days")?.jsonPrimitive?.intOrNull
        }.getOrNull()
    }

    suspend fun claimChallenge(accountId: String, sessionToken: String, challengeId: String): Boolean {
        val resp = fetchAuthenticated(
            accountId,
            sessionToken,
            "POST",
            "https://web.kick.com/api/v1/gamification/challenges/$challengeId/claim",
            "{}"
        )
        return resp != null
    }

    suspend fun fetchFollowedChannels(accountId: String, sessionToken: String): List<String> {
        val raw = fetchAuthenticated(
            accountId,
            sessionToken,
            "GET",
            "https://kick.com/api/v2/channels/followed"
        ) ?: return emptyList()
        return runCatching {
            val el = json.parseToJsonElement(raw)
            val arr = if (el is JsonArray) el else el.jsonObject["channels"]?.jsonArray ?: JsonArray(emptyList())
            arr.mapNotNull { item ->
                val obj = item.jsonObject
                obj["channel_slug"]?.jsonPrimitive?.content
                    ?: obj["slug"]?.jsonPrimitive?.content
                    ?: obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.content
            }.map { it.lowercase() }.distinct()
        }.getOrDefault(emptyList())
    }

    suspend fun getChannelLiveSnapshot(
        slug: String,
        accountId: String = "",
        sessionToken: String = ""
    ): LiveChannelSnapshot? {
        val url = "https://kick.com/api/v2/channels/${slug.lowercase()}"
        val body = fetchPublicOrKickWebView(url, accountId, sessionToken) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val livestream = obj["livestream"] as? JsonObject
            val isLive = livestream != null && livestream["is_live"]?.jsonPrimitive?.booleanOrNull == true
            val viewers = livestream?.get("viewer_count")?.jsonPrimitive?.intOrNull ?: 0
            val category = livestream?.get("categories")?.jsonArray?.firstOrNull()?.jsonObject
            LiveChannelSnapshot(
                login = slug.lowercase(),
                displayName = obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.content ?: slug,
                isLive = isLive,
                viewerCount = viewers,
                gameName = category?.get("name")?.jsonPrimitive?.content,
                gameSlug = category?.get("slug")?.jsonPrimitive?.content,
                title = livestream?.get("session_title")?.jsonPrimitive?.content
            )
        }.getOrNull()
    }

    suspend fun getTopLiveChannels(
        categorySlug: String?,
        accountId: String = "",
        sessionToken: String = "",
        categoryId: String? = null
    ): List<LiveChannelSnapshot> {
        val slug = categorySlug?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val candidateUrls = if (slug != null) {
            buildList {
                add("https://kick.com/stream/livestreams/en?page=1&limit=15&subcategory=$slug&sort=desc")
                add("https://kick.com/stream/livestreams?page=1&limit=15&subcategory=$slug&sort=desc")
                add("https://kick.com/stream/livestreams/en?page=1&limit=15&subcategory=$slug")
                if (!categoryId.isNullOrBlank()) {
                    add("https://kick.com/stream/livestreams/en?page=1&limit=15&subcategory_id=$categoryId&sort=desc")
                }
            }
        } else {
            listOf(
                "https://kick.com/stream/livestreams/en?page=1&limit=15&sort=desc",
                "https://kick.com/stream/livestreams/en?page=1&limit=15",
                "https://kick.com/stream/featured-livestreams/en"
            )
        }

        for (url in candidateUrls) {
            val body = fetchPublicOrKickWebView(url, accountId, sessionToken) ?: continue
            val parsed = parseLivestreamDirectory(body)
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }

        if (slug != null && accountId.isNotBlank()) {
            val scrapedSlugs = webViewBridge.scrapeKickCategoryDropsChannels(accountId, sessionToken, slug)
            for (cand in scrapedSlugs.take(6)) {
                val snap = getChannelLiveSnapshot(cand, accountId, sessionToken)
                if (snap?.isLive == true &&
                    (snap.gameSlug.isNullOrBlank() || snap.gameSlug.equals(slug, ignoreCase = true))
                ) {
                    return listOf(snap)
                }
            }
        }
        return emptyList()
    }

    private fun parseLivestreamDirectory(body: String): List<LiveChannelSnapshot> {
        return runCatching {
            val rootEl = json.parseToJsonElement(body)
            val data = when {
                rootEl is JsonArray -> rootEl
                rootEl is JsonObject -> rootEl["data"]?.jsonArray ?: rootEl["livestreams"]?.jsonArray ?: return@runCatching emptyList()
                else -> return@runCatching emptyList()
            }
            data.mapNotNull { el ->
                val obj = el.jsonObject
                val channel = obj["channel"]?.jsonObject
                val slug = channel?.get("slug")?.jsonPrimitive?.content
                    ?: obj["slug"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val username = channel?.get("user")?.jsonObject?.get("username")?.jsonPrimitive?.content
                    ?: obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.content
                    ?: slug
                val category = obj["categories"]?.jsonArray?.firstOrNull()?.jsonObject
                LiveChannelSnapshot(
                    login = slug.lowercase(),
                    displayName = username,
                    isLive = true,
                    viewerCount = obj["viewer_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    gameName = category?.get("name")?.jsonPrimitive?.content,
                    gameSlug = category?.get("slug")?.jsonPrimitive?.content,
                    title = obj["session_title"]?.jsonPrimitive?.content
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchPublicOrKickWebView(
        url: String,
        accountId: String,
        sessionToken: String
    ): String? {
        val (directBody, code) = withContext(Dispatchers.IO) {
            val (cookieHeader, _) = buildKickCookieHeader(sessionToken)
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Referer", "https://kick.com/")
                .header("Origin", "https://kick.com")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            if (cookieHeader.isNotBlank()) builder.header("Cookie", cookieHeader)

            runCatching {
                okHttp.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) text to resp.code
                    else {
                        AppLogger.warn("KickPublic", "GET $url -> HTTP ${resp.code}: ${text.take(140)}")
                        null to resp.code
                    }
                }
            }.getOrElse { ex ->
                AppLogger.warn("KickPublic", "GET $url failed: ${ex.message}")
                null to 0
            }
        }
        if (!directBody.isNullOrBlank()) return directBody
        if ((code == 403 || code == 0) && accountId.isNotBlank()) {
            return webViewBridge.executeKickFetch(accountId, sessionToken, "GET", url, null)
        }
        return null
    }

    private fun parseIso(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    }
}

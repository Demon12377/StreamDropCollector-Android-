package com.example.network

import com.example.core.logging.AppLogger
import com.example.core.models.DropsCampaign
import com.example.core.models.DropsReward
import com.example.core.models.EligibleStreamer
import com.example.core.models.Platform
import com.example.data.KnownGameEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

data class TwitchCampaignFetchResult(
    val userId: String,
    val login: String,
    val campaigns: List<DropsCampaign>,
    val rewardCampaigns: List<DropsCampaign>,
    val knownGames: List<KnownGameEntry>
)

class TwitchGqlClient(
    private val settingsRepository: SettingsRepository,
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val GQL_URL = "https://gql.twitch.tv/gql"
        private const val DEFAULT_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val HASH_INVENTORY = "9c3723b02655d08fb4fffa40ff0b79d69bb1dcbf27d4e55fbcf3056f5d525093"
        private const val HASH_VIEWER_DASHBOARD = "e8b98b52bbd7ccd37d0b671ad0d47be5238caa5bea637d2a65776175b4a23a64"
        private const val HASH_CAMPAIGN_DETAILS = "039277bf98f3130929262cc7c6efd9c141ca3749cb6dca442fc8ead9a53f77c1"
        private const val HASH_CLAIM_DROP = "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun executeGql(
        accountId: String,
        headers: CapturedTwitchHeaders,
        bodyJson: String
    ): JsonElement? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", headers.clientId.ifBlank { DEFAULT_WEB_CLIENT_ID })
            .header("Authorization", "OAuth ${headers.authToken.lowercase()}")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")

        if (headers.integrityToken.isNotBlank()) {
            builder.header("Client-Integrity", headers.integrityToken)
        }
        if (headers.deviceId.isNotBlank()) {
            builder.header("X-Device-Id", headers.deviceId)
        }

        val req = builder
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            okHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                NetworkMeterRegistry.forAccount(accountId).recordBytes(text.length.toLong())
                if (!resp.isSuccessful) {
                    AppLogger.warn("TwitchGql", "GQL HTTP ${resp.code}: ${text.take(250)}")
                    return@runCatching null
                }
                json.parseToJsonElement(text)
            }
        }.getOrElse { ex ->
            AppLogger.warn("TwitchGql", "GQL request failed: ${ex.message}")
            null
        }
    }

    suspend fun fetchDashboardAndCampaigns(
        accountId: String,
        headers: CapturedTwitchHeaders
    ): TwitchCampaignFetchResult? {
        val cache = settingsRepository.getGqlHashCache()
        val invHash = cache["Inventory"] ?: HASH_INVENTORY
        val dashHash = cache["ViewerDropsDashboard"] ?: HASH_VIEWER_DASHBOARD
        val detHash = cache["DropCampaignDetails"] ?: HASH_CAMPAIGN_DETAILS

        val payload = buildJsonArray {
            add(buildJsonObject {
                put("operationName", "Inventory")
                put("variables", buildJsonObject { put("fetchRewardCampaigns", true) })
                put("extensions", buildJsonObject {
                    put("persistedQuery", buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", invHash)
                    })
                })
            })
            add(buildJsonObject {
                put("operationName", "ViewerDropsDashboard")
                put("variables", buildJsonObject { put("fetchRewardCampaigns", true) })
                put("extensions", buildJsonObject {
                    put("persistedQuery", buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", dashHash)
                    })
                })
            })
        }

        val root = executeGql(accountId, headers, payload.toString())?.jsonArray ?: return null
        if (root.size < 2) return null

        val inventoryData = root[0].jsonObject["data"]?.jsonObject
        val dashboardData = root[1].jsonObject["data"]?.jsonObject

        val currentUser = dashboardData?.get("currentUser")?.jsonObject ?: inventoryData?.get("currentUser")?.jsonObject
        val userId = currentUser?.get("id")?.jsonPrimitive?.content.orEmpty()
        val login = currentUser?.get("login")?.jsonPrimitive?.content.orEmpty()

        val inProgressList = inventoryData
            ?.get("currentUser")?.jsonObject
            ?.get("inventory")?.jsonObject
            ?.get("dropCampaignsInProgress")?.jsonArray
            ?: JsonArray(emptyList())

        val earnedEdges = inventoryData
            ?.get("currentUser")?.jsonObject
            ?.get("inventory")?.jsonObject
            ?.get("earnedDropRewards")?.jsonObject
            ?.get("edges")?.jsonArray
            ?: JsonArray(emptyList())

        val claimedBenefitIds = mutableSetOf<String>()
        earnedEdges.forEach { edge ->
            val node = edge.jsonObject["node"]?.jsonObject ?: return@forEach
            if (node["status"]?.jsonPrimitive?.content == "CLAIMED") {
                node["item"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.let { claimedBenefitIds.add(it) }
            }
        }

        val rawCampaigns = dashboardData
            ?.get("currentUser")?.jsonObject
            ?.get("dropCampaigns")?.jsonArray
            ?: JsonArray(emptyList())

        val knownGames = mutableListOf<KnownGameEntry>()
        val activeIds = mutableListOf<String>()

        rawCampaigns.forEach { el ->
            val obj = el.jsonObject
            val gameObj = obj["game"]?.jsonObject
            val gameSlug = gameObj?.get("slug")?.jsonPrimitive?.content.orEmpty()
            val gameName = gameObj?.get("displayName")?.jsonPrimitive?.content
                ?: gameObj?.get("name")?.jsonPrimitive?.content.orEmpty()
            if (gameSlug.isNotBlank() && gameName.isNotBlank()) {
                knownGames += KnownGameEntry(Platform.TWITCH, gameSlug.lowercase(), gameName)
            }

            val status = obj["status"]?.jsonPrimitive?.content.orEmpty()
            if (status != "ACTIVE") return@forEach

            val isConnected = obj["self"]?.jsonObject?.get("isAccountConnected")?.jsonPrimitive?.booleanOrNull == true
            val linkUrl = obj["accountLinkURL"]?.jsonPrimitive?.content.orEmpty()
            val linksToTwitch = linkUrl.contains("twitch.tv", ignoreCase = true)
            if (!isConnected && !linksToTwitch) return@forEach

            obj["id"]?.jsonPrimitive?.content?.let { activeIds += it }
        }

        val parsedCampaigns = mutableListOf<DropsCampaign>()
        if (activeIds.isNotEmpty() && userId.isNotBlank()) {
            activeIds.chunked(20).forEach { chunk ->
                val batchPayload = buildJsonArray {
                    chunk.forEach { dropId ->
                        add(buildJsonObject {
                            put("operationName", "DropCampaignDetails")
                            put("variables", buildJsonObject {
                                put("dropID", dropId)
                                put("channelLogin", userId)
                            })
                            put("extensions", buildJsonObject {
                                put("persistedQuery", buildJsonObject {
                                    put("version", 1)
                                    put("sha256Hash", detHash)
                                })
                            })
                        })
                    }
                }
                val batchRes = executeGql(accountId, headers, batchPayload.toString())?.jsonArray
                batchRes?.forEach { itemEl ->
                    val campObj = itemEl.jsonObject["data"]?.jsonObject?.get("user")?.jsonObject?.get("dropCampaign")?.jsonObject
                        ?: return@forEach
                    parseCampaignDetails(campObj, inProgressList, claimedBenefitIds)?.let {
                        parsedCampaigns += it
                    }
                }
            }
        }

        // Parse open reward campaigns if available
        val rewardCampaigns = mutableListOf<DropsCampaign>()
        val rawRewards = dashboardData?.get("rewardCampaignsAvailableToUser")?.jsonArray
        rawRewards?.forEach { el ->
            parseRewardCampaign(el.jsonObject)?.let { rewardCampaigns += it }
        }

        return TwitchCampaignFetchResult(
            userId = userId,
            login = login,
            campaigns = parsedCampaigns,
            rewardCampaigns = rewardCampaigns,
            knownGames = knownGames
        )
    }

    private fun parseCampaignDetails(
        campObj: JsonObject,
        inProgressList: JsonArray,
        claimedBenefitIds: Set<String>
    ): DropsCampaign? {
        val id = campObj["id"]?.jsonPrimitive?.content ?: return null
        val name = campObj["name"]?.jsonPrimitive?.content ?: "Twitch Campaign"
        val gameObj = campObj["game"]?.jsonObject
        val gameId = gameObj?.get("id")?.jsonPrimitive?.content
        val gameSlug = gameObj?.get("slug")?.jsonPrimitive?.content.orEmpty()
        val gameName = gameObj?.get("displayName")?.jsonPrimitive?.content
            ?: gameObj?.get("name")?.jsonPrimitive?.content ?: "Twitch"
        val gameImageUrl = gameObj?.get("boxArtURL")?.jsonPrimitive?.content
            ?.replace("{width}", "285")?.replace("{height}", "380")

        val startsAt = parseIso(campObj["startAt"]?.jsonPrimitive?.content)
        val endsAt = parseIso(campObj["endAt"]?.jsonPrimitive?.content)

        val progressObj = inProgressList.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == id
        }?.jsonObject
        val progressDrops = progressObj?.get("timeBasedDrops")?.jsonArray ?: JsonArray(emptyList())

        val timeBasedDrops = campObj["timeBasedDrops"]?.jsonArray ?: JsonArray(emptyList())
        val rewards = timeBasedDrops.mapNotNull { dropEl ->
            val d = dropEl.jsonObject
            val dropId = d["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val dropName = d["name"]?.jsonPrimitive?.content ?: "Drop Reward"
            val requiredMins = d["requiredMinutesWatched"]?.jsonPrimitive?.intOrNull ?: 0
            if (requiredMins <= 0) return@mapNotNull null

            val firstEdge = d["benefitEdges"]?.jsonArray?.firstOrNull()?.jsonObject
            val benefit = firstEdge?.get("benefit")?.jsonObject
            val benefitId = benefit?.get("id")?.jsonPrimitive?.content
            val imageUrl = benefit?.get("imageAssetURL")?.jsonPrimitive?.content
            val distributionType = benefit?.get("distributionType")?.jsonPrimitive?.content.orEmpty()
            val isBadge = distributionType.equals("BADGE", ignoreCase = true) ||
                dropName.contains("badge", ignoreCase = true)
            val isEmote = distributionType.equals("EMOTE", ignoreCase = true) ||
                dropName.contains("emote", ignoreCase = true)

            val matchedProg = progressDrops.firstOrNull {
                it.jsonObject["id"]?.jsonPrimitive?.content == dropId
            }?.jsonObject?.get("self")?.jsonObject

            val progMins = matchedProg?.get("currentMinutesWatched")?.jsonPrimitive?.intOrNull ?: 0
            val selfClaimed = matchedProg?.get("isClaimed")?.jsonPrimitive?.booleanOrNull ?: false
            val dropInstanceId = matchedProg?.get("dropInstanceID")?.jsonPrimitive?.content ?: benefitId
            val isClaimed = selfClaimed || (benefitId != null && benefitId in claimedBenefitIds)

            DropsReward(
                id = dropId,
                name = dropName,
                imageUrl = imageUrl,
                requiredMinutes = requiredMins,
                progressMinutes = if (isClaimed) requiredMins else progMins,
                isClaimed = isClaimed,
                dropInstanceId = dropInstanceId,
                isBadge = isBadge,
                isEmote = isEmote
            )
        }

        if (rewards.isEmpty()) return null

        val allowObj = campObj["allow"]?.jsonObject
        val channels = allowObj?.get("channels")?.jsonArray ?: JsonArray(emptyList())
        val isGeneral = channels.isEmpty()
        val streamers = channels.mapNotNull { ch ->
            val login = ch.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val display = ch.jsonObject["displayName"]?.jsonPrimitive?.content ?: login
            EligibleStreamer(
                login = login.lowercase(),
                displayName = display,
                url = "https://www.twitch.tv/${login.lowercase()}"
            )
        }
        val connectUrls = if (isGeneral && gameSlug.isNotBlank()) {
            listOf("https://www.twitch.tv/directory/category/$gameSlug?filter=drops")
        } else {
            streamers.map { it.url }
        }

        return DropsCampaign(
            id = id,
            name = name,
            slug = gameSlug,
            gameId = gameId,
            gameName = gameName,
            gameImageUrl = gameImageUrl,
            startsAtEpochMs = startsAt,
            endsAtEpochMs = endsAt,
            rewards = rewards,
            platform = Platform.TWITCH,
            connectUrls = connectUrls,
            isGeneralDrop = isGeneral,
            eligibleStreamers = streamers
        )
    }

    private fun parseRewardCampaign(obj: JsonObject): DropsCampaign? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val brand = obj["brand"]?.jsonPrimitive?.content ?: "Twitch Rewards"
        val imageUrl = obj["image"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        val startsAt = parseIso(obj["startsAt"]?.jsonPrimitive?.content)
        val endsAt = parseIso(obj["endsAt"]?.jsonPrimitive?.content)
        val quests = obj["quests"]?.jsonArray ?: JsonArray(emptyList())
        val rewards = quests.mapNotNull { qEl ->
            val q = qEl.jsonObject
            val qId = q["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val qName = q["name"]?.jsonPrimitive?.content ?: name
            val req = q["goal"]?.jsonPrimitive?.intOrNull ?: 60
            DropsReward(
                id = qId,
                name = qName,
                imageUrl = imageUrl,
                requiredMinutes = req
            )
        }
        if (rewards.isEmpty()) return null
        return DropsCampaign(
            id = id,
            name = name,
            slug = "",
            gameName = brand,
            gameImageUrl = imageUrl,
            startsAtEpochMs = startsAt,
            endsAtEpochMs = endsAt,
            rewards = rewards,
            platform = Platform.TWITCH,
            isGeneralDrop = true,
            isRewardCampaign = true
        )
    }

    suspend fun claimDrop(
        accountId: String,
        headers: CapturedTwitchHeaders,
        userId: String,
        campaignId: String,
        rewardId: String,
        dropInstanceId: String?
    ): Boolean {
        val instanceId = if (!dropInstanceId.isNullOrBlank() && dropInstanceId.contains('#')) {
            dropInstanceId
        } else {
            "$userId#$campaignId#$rewardId"
        }

        val payload = buildJsonArray {
            add(buildJsonObject {
                put("operationName", "DropsPage_ClaimDropRewards")
                put("variables", buildJsonObject {
                    put("input", buildJsonObject {
                        put("dropInstanceID", instanceId)
                    })
                })
                put("extensions", buildJsonObject {
                    put("persistedQuery", buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", HASH_CLAIM_DROP)
                    })
                })
            })
        }

        val res = executeGql(accountId, headers, payload.toString())?.jsonArray ?: return false
        val claimResult = res.firstOrNull()?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("claimDropRewards")?.jsonObject ?: return false
        val status = claimResult["status"]?.jsonPrimitive?.content
        val connected = claimResult["isUserAccountConnected"]?.jsonPrimitive?.booleanOrNull == true
        return status == "ELIGIBLE_FOR_ALL" || status == "DROP_INSTANCE_ALREADY_CLAIMED" || connected
    }

    private fun parseIso(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    }
}

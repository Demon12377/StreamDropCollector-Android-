package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.models.AccountCredentials
import com.example.core.models.AccountModel
import com.example.core.models.AppSettings
import com.example.core.models.Platform
import com.example.core.models.TwitchHelixTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drop_collector_store")

@Serializable
data class KnownGameEntry(
    val platform: Platform,
    val slug: String,
    val name: String
)

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val SETTINGS = stringPreferencesKey("app_settings_json")
        val ACCOUNTS = stringPreferencesKey("accounts_json")
        val CREDENTIALS = stringPreferencesKey("credentials_json")
        val HELIX_TOKENS = stringPreferencesKey("helix_tokens_json")
        val PINNED_CAMPAIGNS = stringPreferencesKey("pinned_campaigns_json")
        val LAST_STREAMERS = stringPreferencesKey("last_streamers_json")
        val GQL_HASH_CACHE = stringPreferencesKey("gql_hash_cache_json")
        val KNOWN_GAMES = stringPreferencesKey("known_games_json")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[Keys.SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    val accountsFlow: Flow<List<AccountModel>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCOUNTS]?.let { raw ->
            runCatching { json.decodeFromString<List<AccountModel>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    val knownGamesFlow: Flow<List<KnownGameEntry>> = context.dataStore.data.map { prefs ->
        prefs[Keys.KNOWN_GAMES]?.let { raw ->
            runCatching { json.decodeFromString<List<KnownGameEntry>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SETTINGS]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
            } ?: AppSettings()
            prefs[Keys.SETTINGS] = json.encodeToString(transform(current))
        }
    }

    suspend fun getAccounts(): List<AccountModel> = accountsFlow.first()

    suspend fun saveAccounts(accounts: List<AccountModel>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCOUNTS] = json.encodeToString(accounts)
        }
    }

    suspend fun getCredentialsMap(): Map<String, AccountCredentials> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.CREDENTIALS] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, AccountCredentials>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun saveCredentials(credentials: AccountCredentials) {
        context.dataStore.edit { prefs ->
            val map = prefs[Keys.CREDENTIALS]?.let {
                runCatching { json.decodeFromString<MutableMap<String, AccountCredentials>>(it) }.getOrNull()
            } ?: mutableMapOf()
            map[credentials.accountId] = credentials
            prefs[Keys.CREDENTIALS] = json.encodeToString(map)
        }
    }

    suspend fun removeAccountData(accountId: String) {
        context.dataStore.edit { prefs ->
            val accounts = prefs[Keys.ACCOUNTS]?.let {
                runCatching { json.decodeFromString<List<AccountModel>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == accountId }
            prefs[Keys.ACCOUNTS] = json.encodeToString(accounts)

            val creds = prefs[Keys.CREDENTIALS]?.let {
                runCatching { json.decodeFromString<MutableMap<String, AccountCredentials>>(it) }.getOrNull()
            } ?: mutableMapOf()
            creds.remove(accountId)
            prefs[Keys.CREDENTIALS] = json.encodeToString(creds)

            val pins = prefs[Keys.PINNED_CAMPAIGNS]?.let {
                runCatching { json.decodeFromString<MutableMap<String, String>>(it) }.getOrNull()
            } ?: mutableMapOf()
            pins.remove(accountId)
            prefs[Keys.PINNED_CAMPAIGNS] = json.encodeToString(pins)
        }
    }

    suspend fun clearAllAccounts() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCOUNTS] = json.encodeToString(emptyList<AccountModel>())
            prefs[Keys.CREDENTIALS] = json.encodeToString(emptyMap<String, AccountCredentials>())
            prefs[Keys.PINNED_CAMPAIGNS] = json.encodeToString(emptyMap<String, String>())
            prefs[Keys.LAST_STREAMERS] = json.encodeToString(emptyMap<String, String>())
        }
    }

    suspend fun getHelixTokens(): TwitchHelixTokens? {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.HELIX_TOKENS] ?: return null
        return runCatching { json.decodeFromString<TwitchHelixTokens>(raw) }.getOrNull()
    }

    suspend fun saveHelixTokens(tokens: TwitchHelixTokens?) {
        context.dataStore.edit { prefs ->
            if (tokens == null) {
                prefs.remove(Keys.HELIX_TOKENS)
            } else {
                prefs[Keys.HELIX_TOKENS] = json.encodeToString(tokens)
            }
        }
    }

    suspend fun getPinnedCampaignId(accountId: String): String? {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.PINNED_CAMPAIGNS] ?: return null
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()?.get(accountId)
    }

    suspend fun setPinnedCampaignId(accountId: String, campaignId: String?) {
        context.dataStore.edit { prefs ->
            val map = prefs[Keys.PINNED_CAMPAIGNS]?.let {
                runCatching { json.decodeFromString<MutableMap<String, String>>(it) }.getOrNull()
            } ?: mutableMapOf()
            if (campaignId == null) map.remove(accountId) else map[accountId] = campaignId
            prefs[Keys.PINNED_CAMPAIGNS] = json.encodeToString(map)
        }
    }

    suspend fun getLastMinedStreamer(accountId: String): String? {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.LAST_STREAMERS] ?: return null
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()?.get(accountId)
    }

    suspend fun setLastMinedStreamer(accountId: String, login: String) {
        context.dataStore.edit { prefs ->
            val map = prefs[Keys.LAST_STREAMERS]?.let {
                runCatching { json.decodeFromString<MutableMap<String, String>>(it) }.getOrNull()
            } ?: mutableMapOf()
            map[accountId] = login
            prefs[Keys.LAST_STREAMERS] = json.encodeToString(map)
        }
    }

    suspend fun getGqlHashCache(): Map<String, String> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[Keys.GQL_HASH_CACHE] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun saveGqlHash(operationName: String, hash: String) {
        context.dataStore.edit { prefs ->
            val map = prefs[Keys.GQL_HASH_CACHE]?.let {
                runCatching { json.decodeFromString<MutableMap<String, String>>(it) }.getOrNull()
            } ?: mutableMapOf()
            map[operationName] = hash
            prefs[Keys.GQL_HASH_CACHE] = json.encodeToString(map)
        }
    }

    suspend fun mergeKnownGames(entries: List<KnownGameEntry>) {
        if (entries.isEmpty()) return
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.KNOWN_GAMES]?.let {
                runCatching { json.decodeFromString<List<KnownGameEntry>>(it) }.getOrNull()
            }.orEmpty()
            val merged = (existing + entries)
                .distinctBy { "${it.platform.name}:${it.slug.lowercase()}" }
                .sortedBy { it.name.lowercase() }
            prefs[Keys.KNOWN_GAMES] = json.encodeToString(merged)
        }
    }
}

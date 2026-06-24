package com.locationshare.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "location_share_prefs")

/**
 * All app state lives here, on-device only. Nothing in this file is ever
 * uploaded except the two values (GitHub repo + PAT, Telegram bot token)
 * that the user explicitly enters so the app can talk to those two
 * services directly. There is no analytics, no location history, no
 * third-party SDK other than the ones listed in the README.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        val NAME = stringPreferencesKey("name")
        val MOBILE = stringPreferencesKey("mobile")
        val GITHUB_REPO = stringPreferencesKey("github_repo")       // "owner/repo"
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val TELEGRAM_TOKEN = stringPreferencesKey("telegram_token")
        val SHARING_ENABLED = booleanPreferencesKey("sharing_enabled")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LAST_REQUEST_TIME = stringPreferencesKey("last_request_time")
        val LAST_SHARED_ADDRESS = stringPreferencesKey("last_shared_address")
    }

    val isOnboarded: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDED] ?: false }

    val sharingEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[SHARING_ENABLED] ?: false }

    val name: Flow<String> = context.dataStore.data.map { it[NAME] ?: "" }
    val mobile: Flow<String> = context.dataStore.data.map { it[MOBILE] ?: "" }
    val githubRepo: Flow<String> = context.dataStore.data.map { it[GITHUB_REPO] ?: "" }
    val githubToken: Flow<String> = context.dataStore.data.map { it[GITHUB_TOKEN] ?: "" }
    val telegramToken: Flow<String> = context.dataStore.data.map { it[TELEGRAM_TOKEN] ?: "" }
    val lastRequestTime: Flow<String> = context.dataStore.data.map { it[LAST_REQUEST_TIME] ?: "Never" }
    val lastSharedAddress: Flow<String> = context.dataStore.data.map { it[LAST_SHARED_ADDRESS] ?: "Not shared yet" }

    suspend fun saveOnboarding(name: String, mobile: String, repo: String, ghToken: String, tgToken: String) {
        context.dataStore.edit {
            it[NAME] = name
            it[MOBILE] = mobile
            it[GITHUB_REPO] = repo
            it[GITHUB_TOKEN] = ghToken
            it[TELEGRAM_TOKEN] = tgToken
            it[ONBOARDED] = true
        }
    }

    suspend fun setSharingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHARING_ENABLED] = enabled }
    }

    suspend fun setLastRequestTime(timestamp: String) {
        context.dataStore.edit { it[LAST_REQUEST_TIME] = timestamp }
    }

    suspend fun setLastSharedAddress(address: String) {
        context.dataStore.edit { it[LAST_SHARED_ADDRESS] = address }
    }

    /** Snapshot read, used from the background worker (non-Compose context). */
    suspend fun snapshot(): Map<String, String> {
        val prefs = context.dataStore.data.first()
        return mapOf(
            "name" to (prefs[NAME] ?: ""),
            "mobile" to (prefs[MOBILE] ?: ""),
            "repo" to (prefs[GITHUB_REPO] ?: ""),
            "githubToken" to (prefs[GITHUB_TOKEN] ?: ""),
            "telegramToken" to (prefs[TELEGRAM_TOKEN] ?: ""),
            "sharingEnabled" to ((prefs[SHARING_ENABLED] ?: false)).toString()
        )
    }
}

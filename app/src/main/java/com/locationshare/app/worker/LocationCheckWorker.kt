package com.locationshare.app.worker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.locationshare.app.data.PreferencesManager
import com.locationshare.app.network.GithubApi
import com.locationshare.app.network.NominatimApi
import com.locationshare.app.network.TelegramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This worker does NOT track location on any schedule. It runs periodically
 * only to check a tiny boolean flag on GitHub. Location (the actual GPS
 * fix) is only ever requested the moment that flag is true, and only a
 * single fix is taken — never continuous updates, never stored history.
 */
class LocationCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val REQUEST_PATH = "backend/request.json"
        const val RESPONSE_PATH = "backend/response.json"
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val config = prefs.snapshot()

        val sharingEnabled = config["sharingEnabled"] == "true"
        if (!sharingEnabled) {
            // Sharing OFF -> ignore everything, don't even check GitHub.
            return Result.success()
        }

        val repo = config["repo"].orEmpty()
        val githubToken = config["githubToken"].orEmpty()
        val telegramToken = config["telegramToken"].orEmpty()
        val name = config["name"].orEmpty().ifBlank { "Unknown" }

        if (repo.isBlank() || githubToken.isBlank() || telegramToken.isBlank()) {
            return Result.success() // not configured yet
        }

        val github = GithubApi(repo, githubToken)

        val requestFile = withContext(Dispatchers.IO) { github.getFile(REQUEST_PATH) }
            ?: return Result.retry()

        val isRequested = requestFile.json.optBoolean("request", false)
        if (!isRequested) {
            return Result.success() // nothing pending — do nothing, no GPS touched
        }

        val chatId = requestFile.json.optString("requested_by", "")
        prefs.setLastRequestTime(timestampNow())

        // A pending request exists -> fetch ONE current GPS fix now.
        val location = withContext(Dispatchers.IO) {
            fetchSingleLocation(applicationContext)
        } ?: run {
            // Couldn't get a fix (permission missing, GPS off, etc.) — tell the requester.
            if (chatId.isNotBlank()) {
                TelegramApi(telegramToken).sendMessage(
                    chatId,
                    "⚠️ Could not get a location fix right now. Please try again shortly."
                )
            }
            return Result.retry()
        }

        val address = withContext(Dispatchers.IO) {
            runCatching { NominatimApi().reverseGeocode(location.first, location.second) }
                .getOrDefault("Unknown location")
        }

        val timestamp = timestampNow()

        var deliveryFailed = false
        if (chatId.isNotBlank()) {
            val mapsLink = "https://maps.google.com/?q=${location.first},${location.second}"
            val message = buildString {
                append("📍 Current Location\n")
                append("Name: $name\n")
                append("Address: $address\n")
                append("Latitude: ${location.first}\n")
                append("Longitude: ${location.second}\n")
                append("Google Maps: $mapsLink\n")
                append("Time: $timestamp")
            }
            val sent = withContext(Dispatchers.IO) {
                TelegramApi(telegramToken).sendMessage(chatId, message)
            }
            if (!sent) {
                deliveryFailed = true
            }
        }

        if (deliveryFailed) {
            // Don't clear the request flag — leave it pending so the next
            // scheduled check (or TEST LOCATION) retries delivery instead
            // of silently losing the location fix. Most common cause: the
            // Telegram Bot Token entered in onboarding doesn't match the
            // one BotFather issued (typo / partial paste).
            return Result.retry()
        }

        prefs.setLastSharedAddress(address)

        // Clear the request flag so it isn't answered twice.
        withContext(Dispatchers.IO) {
            val cleared = JSONObject().apply {
                put("request", false)
                put("requested_by", "")
            }
            github.putFile(REQUEST_PATH, cleared, requestFile.sha, "chore: clear handled request")

            // Best-effort: leave a copy of the latest response in the repo too (Workflow 2 territory).
            runCatching {
                val responseFile = github.getFile(RESPONSE_PATH)
                val newResponse = JSONObject().apply {
                    put("latitude", location.first.toString())
                    put("longitude", location.second.toString())
                    put("address", address)
                    put("timestamp", timestamp)
                    put("sharing_enabled", true)
                }
                if (responseFile != null) {
                    github.putFile(RESPONSE_PATH, newResponse, responseFile.sha, "chore: update latest location response")
                }
            }
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchSingleLocation(context: Context): Pair<Double, Double>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(20_000) // give it up to 20s for a fresh fix
            .build()

        val fresh = runCatching {
            val location = client.getCurrentLocation(request, null).await()
            location?.let { it.latitude to it.longitude }
        }.getOrNull()

        if (fresh != null) return fresh

        // Fresh fix failed/timed out (common at night, indoors, or when the
        // OS throttles GPS for background apps on some OEMs). Fall back to
        // the last cached fix instead of failing outright — only used if
        // it's reasonably recent (≤ 30 minutes old) so we never report a
        // stale "current" location.
        return runCatching {
            val last = client.lastLocation.await()
            val ageMs = last?.let { System.currentTimeMillis() - it.time }
            if (last != null && ageMs != null && ageMs <= 30 * 60 * 1000L) {
                last.latitude to last.longitude
            } else {
                null
            }
        }.getOrNull()
    }

    private fun timestampNow(): String {
        val formatter = SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault())
        return formatter.format(Date())
    }
}

package com.locationshare.app.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reverse-geocodes a lat/lng into a human-readable address using
 * OpenStreetMap's Nominatim API. Nominatim's usage policy requires a
 * descriptive User-Agent and no more than ~1 request/second — both are
 * fine here since this only ever runs once per on-demand request.
 */
class NominatimApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun reverseGeocode(latitude: Double, longitude: Double): String {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=json&lat=$latitude&lon=$longitude&zoom=16&addressdetails=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocationShareApp/1.0 (contact: app-owner)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "Unknown location"
            val body = response.body?.string() ?: return "Unknown location"
            val obj = JSONObject(body)
            return obj.optString("display_name", "Unknown location")
        }
    }
}

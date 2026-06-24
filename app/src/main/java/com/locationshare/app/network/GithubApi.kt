package com.locationshare.app.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Minimal wrapper around the GitHub "Contents" REST API
 * (https://docs.github.com/en/rest/repos/contents) used purely as a tiny
 * shared "mailbox" for the pending-request flag. No database involved.
 */
class GithubApi(private val repo: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val base = "https://api.github.com/repos/$repo/contents"

    data class FileContent(val json: JSONObject, val sha: String)

    /** Reads backend/request.json (or any path) and returns its parsed JSON + sha (needed to update it). */
    fun getFile(path: String): FileContent? {
        val request = Request.Builder()
            .url("$base/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val obj = JSONObject(body)
            val sha = obj.getString("sha")
            val contentBase64 = obj.getString("content").replace("\n", "")
            val decoded = String(Base64.getDecoder().decode(contentBase64))
            return FileContent(JSONObject(decoded), sha)
        }
    }

    /** Writes new JSON content to a path, given the previous sha (required by GitHub to avoid clobbering). */
    fun putFile(path: String, newContent: JSONObject, previousSha: String, commitMessage: String): Boolean {
        val encoded = Base64.getEncoder().encodeToString(newContent.toString(2).toByteArray())
        val payload = JSONObject().apply {
            put("message", commitMessage)
            put("content", encoded)
            put("sha", previousSha)
        }

        val request = Request.Builder()
            .url("$base/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .put(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    /** Sends a repository_dispatch event (used to log the latest response, Workflow 2). */
    fun sendDispatch(eventType: String, payload: JSONObject) {
        val body = JSONObject().apply {
            put("event_type", eventType)
            put("client_payload", payload)
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/dispatches")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }
}

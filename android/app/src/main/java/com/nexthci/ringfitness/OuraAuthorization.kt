package com.nexthci.ringfitness

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class OuraEnrollment(
    val participantName: String,
    val authorizationUrl: String,
    val sessionToken: String,
)

data class OuraAuthorizationStatus(
    val participantName: String,
    val connected: Boolean,
    val expired: Boolean,
)

class OuraAuthorizationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePending(participantName: String, sessionToken: String) {
        prefs.edit()
            .putString(KEY_PARTICIPANT_NAME, participantName)
            .putString(KEY_SESSION_TOKEN, sessionToken)
            .remove(KEY_CONNECTED_AT)
            .apply()
    }

    fun markConnected(participantName: String) {
        prefs.edit()
            .putString(KEY_PARTICIPANT_NAME, participantName)
            .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun pendingSession(participantName: String): String? {
        if (prefs.getString(KEY_PARTICIPANT_NAME, null) != participantName) return null
        return prefs.getString(KEY_SESSION_TOKEN, null)
    }

    fun isConnected(participantName: String): Boolean =
        prefs.getString(KEY_PARTICIPANT_NAME, null) == participantName &&
            prefs.getLong(KEY_CONNECTED_AT, 0L) > 0L

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "oura_authorization"
        private const val KEY_PARTICIPANT_NAME = "participant_name"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_CONNECTED_AT = "connected_at"
    }
}

object OuraAuthorizationClient {
    fun start(profile: ParticipantProfile, installationId: String, forceReauthorize: Boolean): OuraEnrollment {
        val base = requireNotNull(profile.authorizationBaseUrl) { "研究 Oura 授权服务尚未配置" }
        val code = requireNotNull(profile.enrollmentCode) { "研究 Oura 注册口令尚未配置" }
        val payload = JSONObject()
            .put("participant_name", profile.displayName)
            .put("installation_id", installationId)
            .put("force_reauthorize", forceReauthorize)
        val result = request(
            method = "POST",
            url = "$base/v1/enroll/start",
            enrollmentCode = code,
            body = payload.toString(),
        )
        return OuraEnrollment(
            participantName = result.getString("participant_name"),
            authorizationUrl = result.getString("authorization_url"),
            sessionToken = result.getString("session_token"),
        )
    }

    fun status(profile: ParticipantProfile, sessionToken: String): OuraAuthorizationStatus {
        val base = requireNotNull(profile.authorizationBaseUrl) { "研究 Oura 授权服务尚未配置" }
        val code = requireNotNull(profile.enrollmentCode) { "研究 Oura 注册口令尚未配置" }
        val encoded = java.net.URLEncoder.encode(sessionToken, StandardCharsets.UTF_8.name())
        val result = request(
            method = "GET",
            url = "$base/v1/enroll/status?session=$encoded",
            enrollmentCode = code,
            body = null,
        )
        return OuraAuthorizationStatus(
            participantName = result.getString("participant_name"),
            connected = result.optBoolean("connected", false),
            expired = result.optBoolean("expired", false),
        )
    }

    private fun request(
        method: String,
        url: String,
        enrollmentCode: String,
        body: String?,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $enrollmentCode")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)
                ?.use(BufferedReader::readText)
                .orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (connection.responseCode !in 200..299) {
                error(
                    payload.optString("error")
                        .ifBlank { "Oura 授权服务请求失败（${connection.responseCode}）" },
                )
            }
            return payload
        } finally {
            connection.disconnect()
        }
    }
}

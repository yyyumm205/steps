package com.nexthci.ringfitness

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ResolvedUser(val username: String, val participantId: String)

object RingFitnessApi {
    fun register(baseUrl: String, code: String, username: String, installationId: String): ResolvedUser =
        participantRequest(baseUrl, code, "/v1/participants/register", username, installationId)

    fun resolve(baseUrl: String, code: String, username: String, installationId: String): ResolvedUser =
        participantRequest(baseUrl, code, "/v1/participants/resolve", username, installationId)

    fun ouraStatus(baseUrl: String, code: String, username: String, installationId: String): Boolean {
        val query = "participant_name=${encode(username)}&installation_id=${encode(installationId)}"
        return request("GET", "${baseUrl.trimEnd('/')}/v1/participants/oura-status?$query", code, null)
            .optBoolean("connected", false)
    }

    private fun participantRequest(
        baseUrl: String,
        code: String,
        path: String,
        username: String,
        installationId: String,
    ): ResolvedUser {
        val body = JSONObject()
            .put("username", ParticipantProfileStore.normalizeUsername(username))
            .put("installation_id", installationId)
        val result = request("POST", "${baseUrl.trimEnd('/')}$path", code, body.toString())
        return ResolvedUser(result.getString("username"), result.getString("participant_id"))
    }

    internal fun request(method: String, url: String, code: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $code")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (responseCode !in 200..299) {
                val message = payload.optString("error").ifBlank { "服务请求失败（$responseCode）" }
                error(when (responseCode) {
                    404 -> if (message.contains("not registered", ignoreCase = true)) {
                        "用户名不存在"
                    } else {
                        "用户服务接口不存在或版本过旧"
                    }
                    409 -> "用户名已被注册"
                    else -> message
                })
            }
            return payload
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

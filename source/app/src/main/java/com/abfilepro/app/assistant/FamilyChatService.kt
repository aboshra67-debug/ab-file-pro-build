package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class FamilyChatMessage(
    val messageId: String,
    val deviceId: String,
    val senderName: String,
    val body: String,
    val createdAt: Long,
)

class FamilyChatService(context: Context) {
    private val appContext = context.applicationContext
    private val syncManager = FamilyCloudSyncManager(appContext, AssistantRepository(appContext))

    fun pull(): List<FamilyChatMessage>? {
        val session = syncManager.session() ?: return null
        val body = JSONObject().put("deviceId", session.deviceId)
        val response = post(
            url = "${session.baseUrl}/v2/families/${session.familyCode}/chat/pull",
            token = session.deviceToken,
            body = body,
        ) ?: return null
        val array = response.optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("messageId").trim()
                val text = item.optString("body").trim()
                if (id.isBlank() || text.isBlank()) continue
                add(
                    FamilyChatMessage(
                        messageId = id,
                        deviceId = item.optString("deviceId"),
                        senderName = item.optString("senderName").ifBlank { "عضو العائلة" },
                        body = text.take(2000),
                        createdAt = item.optLong("createdAt", 0L),
                    )
                )
            }
        }
    }

    fun send(text: String): FamilyChatMessage? {
        val clean = text.trim().take(2000)
        if (clean.isBlank()) return null
        val session = syncManager.session() ?: return null
        val profile = FamilyLinkStore.load(appContext)
        val message = FamilyChatMessage(
            messageId = UUID.randomUUID().toString(),
            deviceId = session.deviceId,
            senderName = profile.selfName.trim().ifBlank { "أنا" },
            body = clean,
            createdAt = System.currentTimeMillis(),
        )
        val body = JSONObject()
            .put("deviceId", session.deviceId)
            .put("messageId", message.messageId)
            .put("senderName", message.senderName)
            .put("body", message.body)
            .put("createdAt", message.createdAt)
        val response = post(
            url = "${session.baseUrl}/v2/families/${session.familyCode}/chat/messages",
            token = session.deviceToken,
            body = body,
        ) ?: return null
        if (response.optString("messageId") != message.messageId) return null
        return message
    }

    fun currentDeviceId(): String = FamilySyncStateStore.deviceId(appContext)

    private fun post(url: String, token: String, body: JSONObject): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 7_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401) FamilySyncStateStore.clearDeviceToken(appContext)
            if (code !in 200..299) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

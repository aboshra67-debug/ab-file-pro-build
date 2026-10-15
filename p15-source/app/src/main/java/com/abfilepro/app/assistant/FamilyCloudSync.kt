package com.abfilepro.app.assistant

import android.content.Context
import com.abfilepro.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID



data class FamilyCloudSession(
    val baseUrl: String,
    val familyCode: String,
    val deviceId: String,
    val deviceToken: String,
)

internal data class FamilyCloudItem(
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val payload: JSONObject,
    val comment: String,
)

object FamilySyncStateStore {
    private const val PREFS = "ab_file_pro_family_cloud_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token" // Alpha83 legacy plaintext key; read-only migration path.
    private const val KEY_DEVICE_TOKEN_ENCRYPTED = "device_token_encrypted"
    private const val KEY_TOKEN_FAMILY_CODE = "token_family_code"
    private const val PREFIX_TS = "ts:"
    private const val PREFIX_DELETE = "del:"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.length >= 8) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun deviceToken(context: Context, familyCode: String): String? {
        val p = prefs(context)
        if (p.getString(KEY_TOKEN_FAMILY_CODE, "").orEmpty() != familyCode) return null

        val encrypted = p.getString(KEY_DEVICE_TOKEN_ENCRYPTED, "").orEmpty()
        if (encrypted.isNotBlank()) {
            val token = SecureLocalSecrets.decrypt(encrypted)
                ?.takeIf { it.length >= 32 }
            if (token != null) return token
            // A Keystore reset/invalidation must never leave an unusable credential behind.
            p.edit().remove(KEY_DEVICE_TOKEN_ENCRYPTED).apply()
        }

        // One-time migration from Alpha83-Alpha96 plaintext storage.
        val legacy = p.getString(KEY_DEVICE_TOKEN, "").orEmpty().takeIf { it.length >= 32 } ?: return null
        saveDeviceToken(context, familyCode, legacy)
        return legacy
    }

    fun saveDeviceToken(context: Context, familyCode: String, token: String) {
        if (familyCode.length != 6 || token.length < 32) return
        val encrypted = SecureLocalSecrets.encrypt(token) ?: return
        prefs(context).edit()
            .putString(KEY_TOKEN_FAMILY_CODE, familyCode)
            .putString(KEY_DEVICE_TOKEN_ENCRYPTED, encrypted)
            .remove(KEY_DEVICE_TOKEN)
            .apply()
    }

    fun clearDeviceToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN_FAMILY_CODE)
            .remove(KEY_DEVICE_TOKEN_ENCRYPTED)
            .remove(KEY_DEVICE_TOKEN)
            .apply()
    }

    fun clearDeviceTokenIfFamilyChanged(context: Context, previousCode: String, newCode: String) {
        if (previousCode != newCode) clearDeviceToken(context)
    }

    fun timestampFor(context: Context, id: String, fallback: Long): Long =
        prefs(context).getLong(PREFIX_TS + id, fallback)

    fun deletedAt(context: Context, id: String): Long =
        prefs(context).getLong(PREFIX_DELETE + id, 0L)

    fun markChanged(context: Context, id: String) {
        val p = prefs(context)
        val old = p.getLong(PREFIX_TS + id, 0L)
        val next = maxOf(System.currentTimeMillis(), old + 1L)
        p.edit().putLong(PREFIX_TS + id, next).remove(PREFIX_DELETE + id).apply()
    }

    fun markDeleted(context: Context, id: String) {
        val p = prefs(context)
        val old = maxOf(p.getLong(PREFIX_TS + id, 0L), p.getLong(PREFIX_DELETE + id, 0L))
        val next = maxOf(System.currentTimeMillis(), old + 1L)
        p.edit().putLong(PREFIX_DELETE + id, next).remove(PREFIX_TS + id).apply()
    }

    fun tombstones(context: Context): Map<String, Long> = buildMap {
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_DELETE) && value is Long && value > 0L) {
                put(key.removePrefix(PREFIX_DELETE), value)
            }
        }
    }

    fun acceptRemote(context: Context, id: String, updatedAt: Long, deleted: Boolean) {
        val edit = prefs(context).edit()
        if (deleted) {
            edit.remove(PREFIX_TS + id).remove(PREFIX_DELETE + id)
        } else {
            edit.putLong(PREFIX_TS + id, updatedAt).remove(PREFIX_DELETE + id)
        }
        edit.apply()
    }
}

class FamilyCloudSyncManager(
    context: Context,
    private val repository: AssistantRepository,
) {
    private val appContext = context.applicationContext

    fun session(): FamilyCloudSession? {
        val baseUrl = baseUrl() ?: return null
        val profile = FamilyLinkStore.load(appContext)
        val familyCode = profile.inviteCode.filter(Char::isDigit)
        if (familyCode.length != 6) return null
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val token = ensureDeviceToken(baseUrl, familyCode, deviceId) ?: return null
        return FamilyCloudSession(baseUrl, familyCode, deviceId, token)
    }

    fun sync(): List<AssistantEntry>? {
        val baseUrl = baseUrl() ?: return null
        val profile = FamilyLinkStore.load(appContext)
        val familyCode = profile.inviteCode.filter(Char::isDigit)
        if (familyCode.length != 6) return null
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val token = ensureDeviceToken(baseUrl, familyCode, deviceId) ?: return null

        val localSnapshot = repository.loadEntries()
        val cloudModules = setOf(AssistantModule.FAMILY_REQUESTS, AssistantModule.HEALTH)
        val localCloudEntries = localSnapshot.filter { it.module in cloudModules }
        val shoppingStore = ShoppingListSyncStore(appContext)
        val outbound = buildList {
            localCloudEntries.forEach { entry ->
                add(
                    FamilyCloudItem(
                        id = entry.id,
                        updatedAt = FamilySyncStateStore.timestampFor(appContext, entry.id, entry.createdAt),
                        deleted = false,
                        payload = encodeEntry(entry),
                        comment = if (entry.module == AssistantModule.FAMILY_REQUESTS) FamilyCommentStore.load(appContext, entry.id) else "",
                    )
                )
            }
            shoppingStore.load().forEach { item ->
                val cloudId = ShoppingListSyncStore.cloudId(item.id)
                add(
                    FamilyCloudItem(
                        id = cloudId,
                        updatedAt = FamilySyncStateStore.timestampFor(appContext, cloudId, item.id.coerceAtLeast(1L)),
                        deleted = false,
                        payload = shoppingStore.itemPayload(item),
                        comment = "",
                    )
                )
            }
            shoppingStore.metaForCloud()?.let { (updatedAt, payload) ->
                add(FamilyCloudItem(ShoppingListSyncStore.META_ID, updatedAt, false, payload, ""))
            }
            FamilySyncStateStore.tombstones(appContext).forEach { (id, deletedAt) ->
                add(FamilyCloudItem(id, deletedAt, true, JSONObject(), ""))
            }
        }

        val remote = postSync(baseUrl, familyCode, deviceId, token, outbound) ?: return null
        if (remote.isEmpty() && outbound.isEmpty()) return null

        val latest = repository.loadEntries()
        val others = latest.filterNot { it.module in cloudModules }.toMutableList()
        val cloudEntries = latest.filter { it.module in cloudModules }.associateBy { it.id }.toMutableMap()
        var changed = false

        remote.forEach { item ->
            if (ShoppingListSyncStore.isShoppingCloudId(item.id)) {
                shoppingStore.applyRemote(item.id, item.payload, item.updatedAt, item.deleted)
                return@forEach
            }
            val local = cloudEntries[item.id]
            val localChangedAt = maxOf(
                local?.let { FamilySyncStateStore.timestampFor(appContext, item.id, it.createdAt) } ?: 0L,
                FamilySyncStateStore.deletedAt(appContext, item.id),
            )
            if (item.updatedAt < localChangedAt) return@forEach

            if (item.deleted) {
                if (cloudEntries.remove(item.id) != null) changed = true
                FamilyCommentStore.deleteFromCloud(appContext, item.id)
                FamilySyncStateStore.acceptRemote(appContext, item.id, item.updatedAt, deleted = true)
            } else {
                val decoded = decodeEntry(item.payload, item.id) ?: return@forEach
                if (local != decoded) {
                    cloudEntries[item.id] = decoded
                    changed = true
                }
                if (decoded.module == AssistantModule.FAMILY_REQUESTS && FamilyCommentStore.load(appContext, item.id) != item.comment) {
                    FamilyCommentStore.saveFromCloud(appContext, item.id, item.comment)
                    changed = true
                }
                FamilySyncStateStore.acceptRemote(appContext, item.id, item.updatedAt, deleted = false)
            }
        }

        val merged = others + cloudEntries.values.sortedByDescending { it.createdAt }
        if (changed) repository.replaceEntries(merged)
        return if (changed) merged else null
    }

    fun openPairingWindow(): Boolean {
        val baseUrl = baseUrl() ?: return false
        val code = FamilyLinkStore.load(appContext).inviteCode.filter(Char::isDigit)
        if (code.length != 6) return false
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val token = FamilySyncStateStore.deviceToken(appContext, code) ?: return false
        return postDeviceRequest(
            url = "$baseUrl/v2/families/$code/pairing/open",
            deviceId = deviceId,
            bearer = token,
        ).first in 200..299
    }

    private fun baseUrl(): String? = BuildConfig.FAMILY_SYNC_BASE_URL.trim().trimEnd('/')
        .takeIf { it.startsWith("https://") }

    private fun ensureDeviceToken(baseUrl: String, familyCode: String, deviceId: String): String? {
        FamilySyncStateStore.deviceToken(appContext, familyCode)?.let { return it }

        val register = postDeviceRequest(
            url = "$baseUrl/v2/families/$familyCode/register",
            deviceId = deviceId,
        )
        if (register.first in 200..299) {
            return parseAndStoreToken(familyCode, register.second)
        }

        if (register.first != 409) return null

        val join = postDeviceRequest(
            url = "$baseUrl/v2/families/$familyCode/join",
            deviceId = deviceId,
        )
        if (join.first !in 200..299) return null
        return parseAndStoreToken(familyCode, join.second)
    }

    private fun parseAndStoreToken(familyCode: String, response: String): String? {
        val token = runCatching { JSONObject(response).optString("deviceToken") }.getOrDefault("")
        if (token.length < 32) return null
        FamilySyncStateStore.saveDeviceToken(appContext, familyCode, token)
        return token
    }

    private fun postDeviceRequest(
        url: String,
        deviceId: String,
        bearer: String? = null,
    ): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 7_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return try {
            val body = JSONObject().put("deviceId", deviceId).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (_: Exception) {
            -1 to ""
        } finally {
            connection.disconnect()
        }
    }

    private fun postSync(
        baseUrl: String,
        familyCode: String,
        deviceId: String,
        deviceToken: String,
        items: List<FamilyCloudItem>,
    ): List<FamilyCloudItem>? {
        val connection = (URL("$baseUrl/v2/families/$familyCode/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 7_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $deviceToken")
        }
        return try {
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("items", JSONArray().apply {
                    items.take(500).forEach { item ->
                        put(JSONObject()
                            .put("id", item.id)
                            .put("updatedAt", item.updatedAt)
                            .put("deleted", item.deleted)
                            .put("payload", item.payload)
                            .put("comment", item.comment.take(500)))
                    }
                })
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401) {
                FamilySyncStateStore.clearDeviceToken(appContext)
                return null
            }
            if (code !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(text).optJSONArray("items") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id").trim()
                    if (id.isBlank()) continue
                    add(
                        FamilyCloudItem(
                            id = id,
                            updatedAt = obj.optLong("updatedAt", 0L),
                            deleted = obj.optBoolean("deleted", false),
                            payload = obj.optJSONObject("payload") ?: JSONObject(),
                            comment = obj.optString("comment").take(500),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeEntry(entry: AssistantEntry): JSONObject = JSONObject()
        .put("module", entry.module.storageId)
        .put("title", entry.title)
        .put("details", entry.details)
        .put("category", entry.category.name)
        .put("createdAt", entry.createdAt)
        .put("dueAt", entry.dueAt ?: JSONObject.NULL)
        .put("completed", entry.completed)
        .put("amount", entry.amount ?: JSONObject.NULL)
        .put("secondary", entry.secondary)
        .put("enabled", entry.enabled)
        .put("pinned", entry.pinned)

    private fun decodeEntry(value: JSONObject, id: String): AssistantEntry? = runCatching {
        val title = value.optString("title").trim().take(240)
        if (title.isBlank()) return null
        val category = runCatching {
            AssistantCategory.valueOf(value.optString("category", AssistantCategory.PERSONAL.name))
        }.getOrDefault(AssistantCategory.PERSONAL)
        val decodedModule = AssistantModule.fromStorageId(value.optString("module"))
            ?.takeIf { it == AssistantModule.FAMILY_REQUESTS || it == AssistantModule.HEALTH }
            ?: return null
        AssistantEntry(
            id = id,
            module = decodedModule,
            title = title,
            details = value.optString("details").take(1200),
            category = AssistantDomain.sanitizeCategory(decodedModule, category),
            createdAt = value.optLong("createdAt", System.currentTimeMillis()),
            dueAt = value.optLongOrNullCompat("dueAt"),
            completed = value.optBoolean("completed", false),
            amount = value.optDoubleOrNullCompat("amount"),
            secondary = value.optString("secondary").take(240),
            enabled = value.optBoolean("enabled", true),
            pinned = value.optBoolean("pinned", false),
        )
    }.getOrNull()
}

private fun JSONObject.optLongOrNullCompat(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNullCompat(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

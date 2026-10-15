package com.abfilepro.app.assistant

import android.content.Context
import com.abfilepro.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
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
    // P17
    private const val PREFIX_REMOTE = "remote:"
    private const val KEY_PUSHED_UP_TO = "pushed_up_to_v17"
    private const val KEY_CURSOR_TS = "cursor_ts_v17"
    private const val KEY_CURSOR_ID = "cursor_id_v17"
    private const val KEY_EXCLUDED = "excluded_ids_v17"

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
            edit.remove(PREFIX_TS + id).remove(PREFIX_DELETE + id).remove(PREFIX_REMOTE + id)
        } else {
            edit.putLong(PREFIX_TS + id, updatedAt).remove(PREFIX_DELETE + id)
        }
        edit.apply()
    }

    // ---- P17 ----------------------------------------------------------------------------

    internal fun markRemoteOrigin(context: Context, id: String) {
        prefs(context).edit().putBoolean(PREFIX_REMOTE + id, true).apply()
    }

    internal fun isRemoteOrigin(context: Context, id: String): Boolean =
        prefs(context).getBoolean(PREFIX_REMOTE + id, false)

    internal fun pushedUpTo(context: Context): Long = prefs(context).getLong(KEY_PUSHED_UP_TO, 0L)

    internal fun setPushedUpTo(context: Context, value: Long) {
        val p = prefs(context)
        if (value > p.getLong(KEY_PUSHED_UP_TO, 0L)) p.edit().putLong(KEY_PUSHED_UP_TO, value).apply()
    }

    internal fun cursor(context: Context): Pair<Long, String> =
        prefs(context).getLong(KEY_CURSOR_TS, 0L) to prefs(context).getString(KEY_CURSOR_ID, "").orEmpty()

    internal fun setCursor(context: Context, updatedAt: Long, id: String) {
        prefs(context).edit().putLong(KEY_CURSOR_TS, updatedAt).putString(KEY_CURSOR_ID, id).apply()
    }

    internal fun excludedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toSet()

    /**
     * Starts a clean sync relationship with a family.
     * [excludeIds]: items that already exist on this phone and must stay local (joining someone
     * else's family). Old tombstones and cursors of a previous family are dropped.
     */
    internal fun startFamily(context: Context, excludeIds: Set<String>) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith(PREFIX_DELETE) }.forEach { edit.remove(it) }
        edit.remove(KEY_PUSHED_UP_TO).remove(KEY_CURSOR_TS).remove(KEY_CURSOR_ID)
            .putStringSet(KEY_EXCLUDED, excludeIds.toSet())
            .apply()
    }

    /** Forgets everything about the current family (device id is kept). */
    internal fun clearFamily(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith(PREFIX_DELETE) || it.startsWith(PREFIX_REMOTE) }.forEach { edit.remove(it) }
        edit.remove(KEY_PUSHED_UP_TO).remove(KEY_CURSOR_TS).remove(KEY_CURSOR_ID).remove(KEY_EXCLUDED)
            .remove(KEY_TOKEN_FAMILY_CODE).remove(KEY_DEVICE_TOKEN_ENCRYPTED).remove(KEY_DEVICE_TOKEN)
            .apply()
    }
}

class FamilyCloudSyncManager(
    context: Context,
    private val repository: AssistantRepository,
) {
    private val appContext = context.applicationContext

    enum class Failure { NOT_CONFIGURED, NETWORK, INVALID_INVITE, PAIRING_CLOSED, NOT_FOUND, FAMILY_FULL, TOO_MANY_ATTEMPTS, UNAUTHORIZED, SERVER }

    sealed class LinkResult {
        /** [invite] is shown on this phone and typed on the other phone (valid until [validUntil]). */
        data class Linked(val familyCode: String, val invite: String?, val validUntil: String?) : LinkResult()
        data class Failed(val reason: Failure) : LinkResult()
    }

    /** Session only for a family the user explicitly created/joined/confirmed. Never registers. */
    fun session(): FamilyCloudSession? {
        val baseUrl = baseUrl() ?: return null
        val profile = FamilyLinkStore.load(appContext)
        if (profile.syncState != FamilySyncState.ON || !profile.isLinked) return null
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val token = FamilySyncStateStore.deviceToken(appContext, profile.inviteCode) ?: return null
        return FamilyCloudSession(baseUrl, profile.inviteCode, deviceId, token)
    }

    /** Creates a new family on the server; this phone's existing family items may be shared. */
    fun createFamily(): LinkResult {
        val baseUrl = baseUrl() ?: return LinkResult.Failed(Failure.NOT_CONFIGURED)
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val random = SecureRandom()
        repeat(5) {
            val code = (random.nextInt(900_000) + 100_000).toString()
            val (status, body) = post("$baseUrl/v2/families/$code/register", JSONObject().put("deviceId", deviceId))
            when (status) {
                in 200..299 -> {
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return LinkResult.Failed(Failure.SERVER)
                    val token = json.optString("deviceToken")
                    val secret = json.optString("pairingSecret")
                    if (token.length < 32) return LinkResult.Failed(Failure.SERVER)
                    FamilySyncStateStore.clearFamily(appContext)
                    FamilySyncStateStore.saveDeviceToken(appContext, code, token)
                    FamilySyncStateStore.startFamily(appContext, excludeIds = emptySet())
                    FamilyLinkStore.link(appContext, code)
                    val invite = secret.takeIf { it.isNotBlank() }?.let { FamilySyncPolicy.Invite(code, it).toString() }
                    return LinkResult.Linked(code, invite, json.optString("pairingOpenUntil").takeIf { it.isNotBlank() })
                }
                409 -> Unit // code already taken; try another
                else -> return LinkResult.Failed(failureFor(status))
            }
        }
        return LinkResult.Failed(Failure.SERVER)
    }

    /** Joins with the invite shown on an already linked phone. Existing local items stay local. */
    fun joinFamily(rawInvite: String): LinkResult {
        val baseUrl = baseUrl() ?: return LinkResult.Failed(Failure.NOT_CONFIGURED)
        val invite = FamilySyncPolicy.parseInvite(rawInvite) ?: return LinkResult.Failed(Failure.INVALID_INVITE)
        val deviceId = FamilySyncStateStore.deviceId(appContext)
        val (status, body) = post(
            "$baseUrl/v2/families/${invite.code}/join",
            JSONObject().put("deviceId", deviceId).put("pairingSecret", invite.secret),
        )
        if (status !in 200..299) return LinkResult.Failed(failureFor(status))
        val token = runCatching { JSONObject(body).optString("deviceToken") }.getOrDefault("")
        if (token.length < 32) return LinkResult.Failed(Failure.SERVER)
        val keepLocal = buildSet {
            repository.loadEntries().filter { it.module in CLOUD_MODULES }.forEach { add(it.id) }
            ShoppingListSyncStore(appContext).load().forEach { add(ShoppingListSyncStore.cloudId(it.id)) }
            add(ShoppingListSyncStore.META_ID)
        }
        FamilySyncStateStore.clearFamily(appContext)
        FamilySyncStateStore.saveDeviceToken(appContext, invite.code, token)
        FamilySyncStateStore.startFamily(appContext, excludeIds = keepLocal)
        FamilyLinkStore.link(appContext, invite.code)
        return LinkResult.Linked(invite.code, null, null)
    }

    /** Opens a 10-minute pairing window and returns a fresh invite for one new phone. */
    fun openPairingWindow(): LinkResult {
        val session = session() ?: return LinkResult.Failed(Failure.UNAUTHORIZED)
        val (status, body) = post(
            "${session.baseUrl}/v2/families/${session.familyCode}/pairing/open",
            JSONObject().put("deviceId", session.deviceId),
            bearer = session.deviceToken,
        )
        if (status !in 200..299) return LinkResult.Failed(failureFor(status))
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return LinkResult.Failed(Failure.SERVER)
        val secret = json.optString("pairingSecret")
        if (secret.isBlank()) return LinkResult.Failed(Failure.SERVER)
        return LinkResult.Linked(
            session.familyCode,
            FamilySyncPolicy.Invite(session.familyCode, secret).toString(),
            json.optString("pairingOpenUntil").takeIf { it.isNotBlank() },
        )
    }

    /** Keeps a pre-P17 automatic link after the user confirmed it. */
    fun confirmLegacyFamily(shareHealth: Boolean) {
        FamilyLinkStore.confirmLegacy(appContext)
        setShareHealth(shareHealth)
    }

    /**
     * Turns health sharing on/off. Turning it off withdraws this phone's own health items from the
     * family (tombstones) while every local health record stays on the phone.
     */
    fun setShareHealth(enabled: Boolean) {
        // Tombstones only ever remove *received* copies on other phones (see sync()), so
        // withdrawing can never delete another member's own records.
        FamilyLinkStore.setShareHealth(appContext, enabled)
        val ownHealth = repository.loadEntries()
            .filter { it.module == AssistantModule.HEALTH && !FamilySyncStateStore.isRemoteOrigin(appContext, it.id) }
        ownHealth.forEach { entry ->
            if (enabled) FamilySyncStateStore.markChanged(appContext, entry.id)
            else FamilySyncStateStore.markDeleted(appContext, entry.id)
        }
    }

    /**
     * Leaves the family on the server first; local state is cleared only when the server confirmed,
     * so a failed request can be retried and never leaves shared data behind silently.
     */
    fun leaveFamily(deleteMyData: Boolean): Boolean {
        val profile = FamilyLinkStore.load(appContext)
        val baseUrl = baseUrl()
        val code = profile.inviteCode
        val token = if (profile.isLinked) FamilySyncStateStore.deviceToken(appContext, code) else null
        if (baseUrl != null && token != null) {
            val (status, _) = post(
                "$baseUrl/v2/families/$code/devices/leave",
                JSONObject().put("deviceId", FamilySyncStateStore.deviceId(appContext)).put("deleteMyData", deleteMyData),
                bearer = token,
            )
            // 401: this device is no longer known to the server, nothing left to remove there.
            if (status !in 200..299 && status != 401) return false
        }
        FamilySyncStateStore.clearFamily(appContext)
        FamilyLinkStore.unlink(appContext)
        return true
    }

    fun sync(): List<AssistantEntry>? {
        val session = session() ?: return null
        val profile = FamilyLinkStore.load(appContext)
        val shareHealth = profile.shareHealth
        val excluded = FamilySyncStateStore.excludedIds(appContext)
        val pushedUpTo = FamilySyncStateStore.pushedUpTo(appContext)

        val localSnapshot = repository.loadEntries()
        val shoppingStore = ShoppingListSyncStore(appContext)
        val outbound = buildList {
            localSnapshot.filter { it.module in CLOUD_MODULES }.forEach { entry ->
                val changedAt = FamilySyncStateStore.timestampFor(appContext, entry.id, entry.createdAt)
                if (!FamilySyncPolicy.shouldUpload(entry.id, entry.module == AssistantModule.HEALTH, shareHealth, excluded, changedAt, pushedUpTo)) return@forEach
                add(
                    FamilyCloudItem(
                        id = entry.id,
                        updatedAt = changedAt,
                        deleted = false,
                        payload = encodeEntry(entry),
                        comment = if (entry.module == AssistantModule.FAMILY_REQUESTS) FamilyCommentStore.load(appContext, entry.id) else "",
                    )
                )
            }
            shoppingStore.load().forEach { item ->
                val cloudId = ShoppingListSyncStore.cloudId(item.id)
                val changedAt = FamilySyncStateStore.timestampFor(appContext, cloudId, item.id.coerceAtLeast(1L))
                if (!FamilySyncPolicy.shouldUpload(cloudId, false, shareHealth, excluded, changedAt, pushedUpTo)) return@forEach
                add(FamilyCloudItem(cloudId, changedAt, false, shoppingStore.itemPayload(item), ""))
            }
            shoppingStore.metaForCloud()?.let { (updatedAt, payload) ->
                if (FamilySyncPolicy.shouldUpload(ShoppingListSyncStore.META_ID, false, shareHealth, excluded, updatedAt, pushedUpTo)) {
                    add(FamilyCloudItem(ShoppingListSyncStore.META_ID, updatedAt, false, payload, ""))
                }
            }
            FamilySyncStateStore.tombstones(appContext).forEach { (id, deletedAt) ->
                if (id !in excluded && deletedAt > pushedUpTo) add(FamilyCloudItem(id, deletedAt, true, JSONObject(), ""))
            }
        }.sortedBy { it.updatedAt }

        // Push in bounded chunks (no item is ever dropped by a 500 cap), confirming each chunk.
        for (chunk in FamilySyncPolicy.chunks(outbound)) {
            if (!stillSameFamily(session.familyCode)) return null
            postSync(session, chunk, cursor = null) ?: return null
            FamilySyncStateStore.setPushedUpTo(appContext, chunk.maxOf { it.updatedAt })
        }

        // Pull every page newer than the stored cursor.
        val remote = ArrayList<FamilyCloudItem>()
        var cursor = FamilySyncStateStore.cursor(appContext)
        for (page in 0 until MAX_PULL_PAGES) {
            if (!stillSameFamily(session.familyCode)) return null
            val response = postSync(session, emptyList(), cursor) ?: return null
            remote += response.items
            cursor = response.nextCursor ?: cursor
            if (!response.hasMore) break
        }
        if (!stillSameFamily(session.familyCode)) return null

        val latest = repository.loadEntries()
        val others = latest.filterNot { it.module in CLOUD_MODULES }.toMutableList()
        val cloudEntries = latest.filter { it.module in CLOUD_MODULES }.associateBy { it.id }.toMutableMap()
        var changed = false

        remote.forEach { item ->
            if (item.id in excluded) return@forEach
            if (ShoppingListSyncStore.isShoppingCloudId(item.id)) {
                if (shoppingStore.applyRemote(item.id, item.payload, item.updatedAt, item.deleted)) changed = true
                return@forEach
            }
            val local = cloudEntries[item.id]
            val localIsOwnHealth = local?.module == AssistantModule.HEALTH && !FamilySyncStateStore.isRemoteOrigin(appContext, item.id)
            if (!FamilySyncPolicy.acceptRemoteForLocalHealth(localIsOwnHealth, shareHealth)) return@forEach
            val localChangedAt = maxOf(
                local?.let { FamilySyncStateStore.timestampFor(appContext, item.id, it.createdAt) } ?: 0L,
                FamilySyncStateStore.deletedAt(appContext, item.id),
            )
            if (item.updatedAt < localChangedAt) return@forEach

            if (item.deleted) {
                // A remote tombstone never removes a health record this phone created itself;
                // it only removes copies that were received from another family member.
                if (localIsOwnHealth) return@forEach
                if (cloudEntries.remove(item.id) != null) changed = true
                FamilyCommentStore.deleteFromCloud(appContext, item.id)
                FamilySyncStateStore.acceptRemote(appContext, item.id, item.updatedAt, deleted = true)
            } else {
                val decoded = decodeEntry(item.payload, item.id) ?: return@forEach
                if (local == null) FamilySyncStateStore.markRemoteOrigin(appContext, item.id)
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
        FamilySyncStateStore.setCursor(appContext, cursor.first, cursor.second)

        if (!changed) return null
        val merged = others + cloudEntries.values.sortedByDescending { it.createdAt }
        repository.replaceEntries(merged)
        return merged
    }

    private fun stillSameFamily(code: String): Boolean {
        val now = FamilyLinkStore.load(appContext)
        return now.syncState == FamilySyncState.ON && now.inviteCode == code
    }

    private fun failureFor(status: Int): Failure = when (status) {
        -1 -> Failure.NETWORK
        401 -> Failure.UNAUTHORIZED
        403 -> Failure.INVALID_INVITE
        404 -> Failure.NOT_FOUND
        409 -> Failure.FAMILY_FULL
        423 -> Failure.PAIRING_CLOSED
        429 -> Failure.TOO_MANY_ATTEMPTS
        else -> Failure.SERVER
    }

    private fun baseUrl(): String? = BuildConfig.FAMILY_SYNC_BASE_URL.trim().trimEnd('/')
        .takeIf { it.startsWith("https://") }

    private fun post(url: String, body: JSONObject, bearer: String? = null): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
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

    private class SyncResponse(val items: List<FamilyCloudItem>, val nextCursor: Pair<Long, String>?, val hasMore: Boolean)

    private fun postSync(session: FamilyCloudSession, items: List<FamilyCloudItem>, cursor: Pair<Long, String>?): SyncResponse? {
        val body = JSONObject()
            .put("deviceId", session.deviceId)
            .put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject()
                        .put("id", item.id)
                        .put("updatedAt", item.updatedAt)
                        .put("deleted", item.deleted)
                        .put("payload", item.payload)
                        .put("comment", item.comment.take(500)))
                }
            })
        // Always send a cursor: a P17 server then pages; the push-only calls ask from "now" (empty page).
        val since = cursor ?: (Long.MAX_VALUE / 4 to "")
        body.put("since", JSONObject().put("updatedAt", since.first).put("id", since.second))
        val (status, text) = post("${session.baseUrl}/v2/families/${session.familyCode}/sync", body, bearer = session.deviceToken)
        if (status == 401) {
            FamilySyncStateStore.clearDeviceToken(appContext)
            return null
        }
        if (status !in 200..299) return null
        return runCatching {
            val json = JSONObject(text)
            val array = json.optJSONArray("items") ?: JSONArray()
            val parsed = buildList {
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
            val next = json.optJSONObject("nextCursor")?.let { it.optLong("updatedAt", 0L) to it.optString("id") }
            SyncResponse(parsed, next, json.optBoolean("hasMore", false))
        }.getOrNull()
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
            // P17 (FAM-N01): same limit as the local store, so sync never shortens details.
            details = value.optString("details").take(4000),
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

    private companion object {
        val CLOUD_MODULES = setOf(AssistantModule.FAMILY_REQUESTS, AssistantModule.HEALTH)
        const val MAX_PULL_PAGES = 20
    }
}

private fun JSONObject.optLongOrNullCompat(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNullCompat(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

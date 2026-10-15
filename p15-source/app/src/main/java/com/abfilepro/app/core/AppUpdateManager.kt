package com.abfilepro.app.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.abfilepro.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Safe, opt-in APK updater for AB File Pro.
 *
 * Android does not allow a normal app to silently replace itself. This manager downloads a
 * server-declared APK, verifies SHA-256 + package identity + increasing versionCode, and then
 * opens the Android package installer. The user only confirms the final Android install step.
 */
object AppUpdateManager {
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val mandatory: Boolean
    )

    enum class InstallLaunchResult { INSTALLER_OPENED, PERMISSION_REQUESTED }

    private const val PREFS = "ab_file_pro_updates"
    private const val PENDING_APK = "pending_apk"
    private const val MAX_APK_BYTES = 350L * 1024L * 1024L

    suspend fun checkForUpdate(activity: Activity): UpdateInfo? = withContext(Dispatchers.IO) {
        val currentCode = installedVersionCode(activity)
        val packageName = activity.packageName
        val endpoint = BuildConfig.FAMILY_SYNC_BASE_URL.trimEnd('/') +
            "/app-update?packageName=" + URLEncoder.encode(packageName, "UTF-8") +
            "&currentVersionCode=$currentCode"
        val connection = openHttps(endpoint)
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("available", false)) return@withContext null
            val versionCode = json.optLong("versionCode", 0L)
            val versionName = json.optString("versionName", "").trim()
            val apkUrl = json.optString("apkUrl", "").trim()
            val sha256 = json.optString("sha256", "").trim().lowercase()
            val notes = json.optString("notes", "").trim()
            val mandatory = json.optBoolean("mandatory", false)
            if (versionCode <= currentCode || versionName.isBlank()) return@withContext null
            require(apkUrl.startsWith("https://")) { "update_apk_url_must_be_https" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "update_sha256_invalid" }
            UpdateInfo(versionCode, versionName, apkUrl, sha256, notes, mandatory)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndRequestInstall(activity: Activity, update: UpdateInfo): InstallLaunchResult {
        val apk = withContext(Dispatchers.IO) { downloadAndVerify(activity, update) }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit().putString(PENDING_APK, apk.absolutePath).apply()
        return launchInstallerOrPermission(activity, apk)
    }

    /** Continue automatically after the user grants "Install unknown apps" permission. */
    fun resumePendingInstall(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val path = prefs.getString(PENDING_APK, null) ?: return
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            prefs.edit().remove(PENDING_APK).apply()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) return
        prefs.edit().remove(PENDING_APK).apply()
        openPackageInstaller(activity, file)
    }

    private fun downloadAndVerify(activity: Activity, update: UpdateInfo): File {
        require(update.apkUrl.startsWith("https://")) { "update_apk_url_must_be_https" }
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile && it.name != "ab_file_pro_${update.versionCode}.apk") runCatching { it.delete() } }
        val finalFile = File(dir, "ab_file_pro_${update.versionCode}.apk")
        val part = File(dir, ".${finalFile.name}.part")

        if (finalFile.exists() && verifySha256(finalFile, update.sha256) && verifyArchiveIdentity(activity, finalFile, update.versionCode)) {
            return finalFile
        }
        runCatching { finalFile.delete() }
        runCatching { part.delete() }

        val connection = openHttps(update.apkUrl, readTimeoutMs = 60_000)
        try {
            val code = connection.responseCode
            require(code in 200..299) { "update_download_http_$code" }
            val declared = connection.contentLengthLong
            if (declared > 0L) require(declared <= MAX_APK_BYTES) { "update_apk_too_large" }
            connection.inputStream.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_APK_BYTES) { "update_apk_too_large" }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        require(part.exists() && part.length() > 0L) { "update_download_empty" }
        require(verifySha256(part, update.sha256)) { "update_sha256_mismatch" }
        require(verifyArchiveIdentity(activity, part, update.versionCode)) { "update_apk_identity_mismatch" }
        if (finalFile.exists()) require(finalFile.delete()) { "update_replace_failed" }
        require(part.renameTo(finalFile)) { "update_commit_failed" }
        return finalFile
    }

    private fun launchInstallerOrPermission(activity: Activity, apk: File): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            return InstallLaunchResult.PERMISSION_REQUESTED
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(PENDING_APK).apply()
        openPackageInstaller(activity, apk)
        return InstallLaunchResult.INSTALLER_OPENED
    }

    private fun openPackageInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun verifyArchiveIdentity(activity: Activity, apk: File, expectedVersionCode: Long): Boolean {
        val info = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA) ?: return false
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return info.packageName == activity.packageName && archiveCode == expectedVersionCode && archiveCode > installedVersionCode(activity)
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(activity: Activity): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun openHttps(url: String, readTimeoutMs: Int = 10_000): HttpURLConnection {
        require(url.startsWith("https://")) { "update_url_must_be_https" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive;q=0.9, */*;q=0.1")
            setRequestProperty("User-Agent", "AB-File-Pro/${BuildConfig.VERSION_NAME}")
        }
    }
}

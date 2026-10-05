package ru.devkz.apsvault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates from the GitHub releases of this repository: the latest release carries the signed
 * APK. The download is installed only when it is this package and signed by the same key as the
 * running app, so a substituted file never reaches the installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/kzhebenev/aps-vault-android/releases/latest"

    data class Release(val version: String, val apkUrl: String, val notes: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Numeric comparison: 1.10 is newer than 1.9. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The latest release when it is newer than the installed version; null otherwise or offline. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", VaultApi.USER_AGENT)
            .build()
        runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) return@use null
                val version = o.optString("tag_name").removePrefix("v")
                val assets = o.optJSONArray("assets") ?: return@use null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
                Release(version, apk.optString("browser_download_url"), o.optString("body"))
                    .takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            }
        }.getOrNull()
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "aps-vault-${release.version}.apk")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", VaultApi.USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw VaultException(resp.code, "download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw VaultException(0, "empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { fileOut ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fileOut.write(buf, 0, n)
                        got += n
                        onProgress(if (total > 0) got.toFloat() / total else -1f)
                    }
                }
            }
        }
        if (!sameSigner(context, out)) { out.delete(); throw VaultException(0, "signature mismatch") }
        out
    }

    /** The archive is this package and every signer matches the installed app's. */
    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = (if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, flags)) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
        fun digests(si: android.content.pm.SigningInfo?) = si?.apkContentsSigners.orEmpty()
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        val a = digests(archive.signingInfo)
        return a.isNotEmpty() && a == digests(installed.signingInfo)
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context) =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

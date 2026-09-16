package com.caydey.ffshare.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.caydey.ffshare.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
)

class UpdateChecker(private val context: Context) {

    private val downloadDir = File(context.cacheDir, "updates")

    /** Returns release info if the published build differs from the installed one, else null. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val release = parseRelease(httpGet(RELEASE_API)) ?: return@withContext null
        if (isNewer(release, BuildConfig.VERSION_NAME)) release else null
    }

    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = downloadDir.apply { mkdirs() }
        val file = File(dir, release.apkName)
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        val connection = openConnection(release.apkUrl, instanceFollowRedirects = true)
        try {
            connection.requireOk()
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            // Manual stream management — use{} takes a plain lambda so withContext isn't callable inside it
            val input = connection.inputStream
            val output = FileOutputStream(file)
            try {
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } >= 0) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            } finally {
                output.close()
                input.close()
            }
            // a connection dropped mid-transfer otherwise leaves a short file that
            // installIntent hands to the package installer as a valid-looking APK
            if (total > 0 && downloaded != total) {
                file.delete()
                throw IOException("truncated download: $downloaded of $total bytes")
            }
        } finally {
            connection.disconnect()
        }
        file
    }

    /**
     * Deletes the APK of the build that is now running. Only that file: a different
     * version might still be open in the system installer if this process was restarted
     * to serve it through the FileProvider.
     */
    fun deleteInstalledUpdate() {
        File(downloadDir, "$APK_PREFIX${BuildConfig.VERSION_NAME}$APK_UNIVERSAL_SUFFIX").delete()
    }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun httpGet(urlString: String): String {
        val connection = openConnection(urlString, extraHeaders = mapOf("Accept" to "application/vnd.github+json"))
        try {
            connection.requireOk()
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Without this an error response surfaces as the IOException HttpURLConnection throws
     * from inputStream, whose message is just the URL — so GitHub's unauthenticated rate
     * limit reads to the user as "Update check failed: https://api.github.com/...".
     */
    private fun HttpURLConnection.requireOk() {
        if (responseCode == HttpURLConnection.HTTP_OK) return
        val detail = runCatching { errorStream?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
            ?.trim()
            ?.take(ERROR_DETAIL_CHARS)
            .orEmpty()
        disconnect()
        throw IOException("HTTP $responseCode from $url${if (detail.isEmpty()) "" else ": $detail"}")
    }

    private fun openConnection(
        urlString: String,
        instanceFollowRedirects: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        this.instanceFollowRedirects = instanceFollowRedirects
        setRequestProperty("User-Agent", "ffshare")
        extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    companion object {
        const val RELEASE_API =
            "https://api.github.com/repos/c0dev0id/ffshare/releases/tags/dev"
        private const val TIMEOUT_MS = 15_000
        private const val ERROR_DETAIL_CHARS = 200
        private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
        private const val APK_PREFIX = "ffshare-"
        private const val APK_UNIVERSAL_SUFFIX = "-universal.apk"

        fun isNewer(remote: ReleaseInfo, installedVersionName: String): Boolean =
            remote.versionName.isNotEmpty() && remote.versionName != installedVersionName

        /**
         * Finds the universal APK asset in a GitHub release JSON. Version name is derived
         * from the filename (`ffshare-<versionName>-universal.apk`), matching the build's
         * `versionName` so [isNewer] can compare them directly.
         */
        fun parseRelease(json: String): ReleaseInfo? {
            val assets = JSONObject(json).optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.startsWith(APK_PREFIX) || !name.endsWith(APK_UNIVERSAL_SUFFIX)) continue
                return ReleaseInfo(
                    versionName = name.removePrefix(APK_PREFIX).removeSuffix(APK_UNIVERSAL_SUFFIX),
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name,
                )
            }
            return null
        }
    }
}

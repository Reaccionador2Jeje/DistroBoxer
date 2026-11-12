package com.atdevdev.distroboxer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Persistent, user-initiated archive download.
 *
 * The caller supplies a pinned digest or a trusted checksum-manifest URL.
 * The worker downloads and verifies; the next WorkManager step performs
 * security-sensitive extraction through the Rust engine.
 */
class DistroArchiveDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val distroId = inputData.getString(KEY_DISTRO_ID).orEmpty()
        val source = inputData.getString(KEY_SOURCE_URL).orEmpty()
        val expectedHashInput = inputData.getString(KEY_SHA256).orEmpty().lowercase()
        val checksumUrl = inputData.getString(KEY_CHECKSUM_URL).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty()
        val hasPinnedHash = expectedHashInput.matches(Regex("[a-f0-9]{64}"))
        val hasTrustedChecksumUrl = isTrustedSource(checksumUrl) &&
            Uri.parse(source).host.equals(Uri.parse(checksumUrl).host, ignoreCase = true)

        if (!distroId.matches(Regex("[a-z0-9_-]{2,32}")) ||
            !fileName.matches(Regex("[a-zA-Z0-9._-]{1,120}")) ||
            (!hasPinnedHash && !hasTrustedChecksumUrl) ||
            !isTrustedSource(source)
        ) {
            return Result.failure(workDataOf(KEY_ERROR to "Invalid download metadata or untrusted HTTPS source"))
        }

        createNotificationChannel()
        setForeground(foregroundInfo("Preparando descarga", 0, true))

        val distroDir = File(applicationContext.filesDir, "distros/$distroId/downloads")
        if (!distroDir.exists() && !distroDir.mkdirs()) {
            return Result.failure(workDataOf(KEY_ERROR to "Cannot create private download directory"))
        }

        val target = File(distroDir, fileName)
        val temporary = File(distroDir, "$fileName.part")
        if (target.canonicalFile.parentFile != distroDir.canonicalFile) {
            return Result.failure(workDataOf(KEY_ERROR to "Unsafe destination path"))
        }

        return try {
            val expectedHash = if (hasPinnedHash) {
                expectedHashInput
            } else {
                fetchExpectedSha256(checksumUrl, fileName)
            }
            downloadAndVerify(source, temporary, expectedHash)
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Cannot replace previous archive")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Cannot finalize downloaded archive")
            }
            setForeground(foregroundInfo("Descarga verificada", 100, false))
            Result.success(
                workDataOf(
                    KEY_ARCHIVE_PATH to target.absolutePath,
                    KEY_DISTRO_ID to distroId,
                    KEY_SHA256 to expectedHash,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (error: Exception) {
            temporary.delete()
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Download failed")))
        }
    }

    private suspend fun downloadAndVerify(source: String, destination: File, expectedHash: String) {
        var currentUrl = source
        var connection: HttpURLConnection? = null
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val uri = Uri.parse(currentUrl)
            require(isTrustedHost(uri.host)) { "Redirected to an untrusted host" }
            connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection!!.connectTimeout = CONNECT_TIMEOUT_MS
            connection!!.readTimeout = READ_TIMEOUT_MS
            connection!!.instanceFollowRedirects = false
            connection!!.setRequestProperty("User-Agent", "DistroBoxer/0.3")
            val response = connection!!.responseCode
            if (response in 300..399) {
                val location = connection!!.getHeaderField("Location")
                    ?: error("Redirect without Location header")
                require(redirect < MAX_REDIRECTS) { "Too many redirects" }
                val resolved = URL(URL(currentUrl), location).toString()
                require(isTrustedSource(resolved)) { "Redirect target is not trusted HTTPS" }
                currentUrl = resolved
                connection!!.disconnect()
                connection = null
            } else {
                require(response == HttpURLConnection.HTTP_OK) { "HTTP $response" }
                val length = connection!!.contentLengthLong
                require(length <= MAX_ARCHIVE_BYTES) { "Archive exceeds the configured size limit" }

                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection!!.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= MAX_ARCHIVE_BYTES) { "Archive exceeds the configured size limit" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (length > 0) {
                                val progress = ((received * 100) / length).toInt().coerceIn(0, 99)
                                setProgress(workDataOf(KEY_PROGRESS to progress))
                                if (progress % 10 == 0) {
                                    setForeground(foregroundInfo("Descargando distribución", progress, true))
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                require(actualHash == expectedHash) { "SHA-256 verification failed" }
                connection!!.disconnect()
                return
            }
        }
        connection?.disconnect()
        error("Unable to complete download")
    }

    private suspend fun fetchExpectedSha256(checksumUrl: String, fileName: String): String =
        withContext(Dispatchers.IO) {
            val connection = URL(checksumUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "DistroBoxer/0.3")
                require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Checksum manifest request failed"
                }

                val body = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(512)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(body.size() + count <= MAX_CHECKSUM_BYTES) {
                            "Checksum manifest is unexpectedly large"
                        }
                        body.write(buffer, 0, count)
                    }
                }

                val firstLine = body.toString(Charsets.US_ASCII.name())
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?: error("Checksum manifest is empty")
                val match = CHECKSUM_LINE.matchEntire(firstLine.trim())
                    ?: error("Checksum manifest has an unsupported format")
                val listedFile = match.groupValues.getOrNull(2).orEmpty()
                    .trim()
                    .removePrefix("*")
                    .substringAfterLast('/')
                require(listedFile.isEmpty() || listedFile == fileName) {
                    "Checksum manifest names a different archive"
                }
                match.groupValues[1].lowercase()
            } finally {
                connection.disconnect()
            }
        }

    private fun isTrustedSource(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && isTrustedHost(uri.host)
    }

    private fun isTrustedHost(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return TRUSTED_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DistroBoxer downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun foregroundInfo(title: String, progress: Int, ongoing: Boolean): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DistroBoxer")
            .setContentText(title)
            .setProgress(100, progress, progress == 0 && ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_DISTRO_ID = "distro_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_SHA256 = "sha256"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_ARCHIVE_FORMAT = "archive_format"
        const val KEY_CHECKSUM_URL = "checksum_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ARCHIVE_PATH = "archive_path"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "distro_downloads"
        private const val NOTIFICATION_ID = 7301
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_ARCHIVE_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_CHECKSUM_BYTES = 4096
        private val CHECKSUM_LINE = Regex("""^\s*([a-fA-F0-9]{64})(?:\s+\*?(.+?))?\s*$""")

        private val TRUSTED_DOMAINS = setOf(
            "debian.org",
            "alpinelinux.org",
            "ubuntu.com",
            "archlinux.org",
            "pkgbuild.com",
            "fedoraproject.org",
            "opensuse.org",
            "voidlinux.org",
            "kali.org",
            "voidlinux.org",
            "linuxcontainers.org",
        )
    }
}

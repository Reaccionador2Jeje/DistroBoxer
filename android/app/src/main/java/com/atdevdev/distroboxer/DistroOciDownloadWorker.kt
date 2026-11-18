package com.atdevdev.distroboxer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pulls an anonymous public OCI image for the requested Linux architecture.
 * The registry manifest supplies immutable layer digests; every blob is
 * checked before it is handed to the native Rust installer.
 */
class DistroOciDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val distroId = inputData.getString(KEY_DISTRO_ID).orEmpty()
        val imageRef = inputData.getString(KEY_IMAGE).orEmpty()
        val tag = inputData.getString(KEY_TAG).orEmpty()
        val architecture = inputData.getString(KEY_ARCH).orEmpty()

        if (!distroId.matches(Regex("[a-z0-9_-]{2,32}")) ||
            !imageRef.matches(Regex("[a-z0-9./_-]{3,180}")) ||
            !tag.matches(Regex("[a-zA-Z0-9._-]{1,80}")) ||
            architecture !in setOf("arm64", "amd64")
        ) {
            return Result.failure(workDataOf(KEY_ERROR to "Invalid OCI image metadata"))
        }

        createNotificationChannel()
        setForeground(foregroundInfo("Conectando al registro OCI", 0, true))

        val parsed = parseImage(imageRef)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Unsupported OCI registry"))
        val directory = File(applicationContext.filesDir, "distros/$distroId/downloads/oci")
        if (!directory.exists() && !directory.mkdirs()) {
            return Result.failure(workDataOf(KEY_ERROR to "Cannot create private OCI download directory"))
        }
        if (directory.canonicalFile.parentFile != File(applicationContext.filesDir, "distros/$distroId/downloads").canonicalFile) {
            return Result.failure(workDataOf(KEY_ERROR to "Unsafe OCI destination"))
        }

        return try {
            directory.listFiles()?.filter {
                it.name.matches(Regex("layer-[0-9]{3}\\.blob")) || it.name == "manifest.json"
            }?.forEach { it.delete() }

            val token = fetchToken(parsed)
            val accept = listOf(
                "application/vnd.oci.image.index.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json",
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.v2+json",
            ).joinToString(", ")
            var manifestResponse = registryGet(parsed, "/v2/${parsed.repository}/manifests/$tag", token, accept)
            verifyManifestDigest(manifestResponse.first, manifestResponse.second)
            var resolvedManifestDigest = manifestResponse.second
                ?: "sha256:" + sha256(manifestResponse.first.toByteArray(Charsets.UTF_8))
            var manifest = JSONObject(manifestResponse.first)
            if (manifest.has("manifests")) {
                val descriptors = manifest.getJSONArray("manifests")
                val selected = selectPlatformManifest(descriptors, architecture)
                    ?: error("The OCI image does not publish $architecture")
                val digest = selected.getString("digest")
                require(digest.matches(Regex("sha256:[a-f0-9]{64}"))) { "Invalid platform manifest digest" }
                manifestResponse = registryGet(parsed, "/v2/${parsed.repository}/manifests/$digest", token, accept)
                verifyManifestDigest(manifestResponse.first, digest)
                resolvedManifestDigest = digest
                manifest = JSONObject(manifestResponse.first)
            }

            require(manifest.optInt("schemaVersion") == 2) { "Unsupported OCI manifest schema" }
            val layers = manifest.optJSONArray("layers") ?: error("OCI manifest has no layers")
            require(layers.length() in 1..128) { "OCI image layer count is outside supported limits" }

            val outputLayers = JSONArray()
            var totalBytes = 0L
            for (index in 0 until layers.length()) {
                currentCoroutineContext().ensureActive()
                val descriptor = layers.getJSONObject(index)
                val digest = descriptor.getString("digest").lowercase()
                val mediaType = descriptor.getString("mediaType")
                val size = descriptor.optLong("size", -1)
                require(digest.matches(Regex("sha256:[a-f0-9]{64}"))) { "Unsupported OCI layer digest" }
                require(mediaType in SUPPORTED_LAYER_TYPES) { "Unsupported OCI layer format: $mediaType" }
                require(size in 1..MAX_LAYER_BYTES) { "OCI layer size is outside supported limits" }
                totalBytes += size
                require(totalBytes <= MAX_TOTAL_BYTES) { "OCI image exceeds the total size limit" }

                val fileName = "layer-%03d.blob".format(index)
                val target = File(directory, fileName)
                downloadBlob(parsed, digest, token, target, size)
                val output = JSONObject()
                    .put("digest", digest)
                    .put("media_type", mediaType)
                    .put("file", fileName)
                outputLayers.put(output)
                val progress = ((index + 1) * 100 / layers.length()).coerceIn(1, 99)
                setProgress(workDataOf(KEY_PROGRESS to progress))
                setForeground(foregroundInfo("Descargando capas OCI · ${index + 1}/${layers.length()}", progress, true))
            }

            val layerManifest = JSONObject()
                .put("schemaVersion", 1)
                .put("image", imageRef)
                .put("tag", tag)
                .put("imageDigest", resolvedManifestDigest)
                .put("layers", outputLayers)
            val manifestFile = File(directory, "manifest.json")
            manifestFile.writeText(layerManifest.toString(), Charsets.UTF_8)
            setForeground(foregroundInfo("Imagen OCI verificada", 100, false))
            Result.success(
                workDataOf(
                    KEY_DISTRO_ID to distroId,
                    KEY_OCI_MANIFEST to manifestFile.absolutePath,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            directory.listFiles()?.filter { it.name.startsWith("layer-") || it.name == "manifest.json" }?.forEach { it.delete() }
            throw cancelled
        } catch (error: Exception) {
            directory.listFiles()?.filter { it.name.startsWith("layer-") || it.name == "manifest.json" }?.forEach { it.delete() }
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "OCI download failed")))
        }
    }

    private data class Registry(val repository: String, val registryBase: String, val tokenUrl: String)

    private fun parseImage(value: String): Registry? {
        val parts = value.split('/')
        if (parts.size < 2) return Registry(
            "library/$value", "https://registry-1.docker.io",
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/$value:pull",
        )
        val hasRegistry = parts.first().contains('.') || parts.first().contains(':') || parts.first() == "localhost"
        val host = if (hasRegistry) parts.first() else "docker.io"
        val repository = if (hasRegistry) parts.drop(1).joinToString("/") else value
        return when (host) {
            "docker.io" -> Registry(
                repository, "https://registry-1.docker.io",
                "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repository:pull",
            )
            "ghcr.io" -> Registry(
                repository, "https://ghcr.io",
                "https://ghcr.io/token?scope=repository:$repository:pull",
            )
            else -> null
        }
    }

    private fun fetchToken(registry: Registry): String {
        val connection = URL(registry.tokenUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "DistroBoxer/0.6")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "OCI registry authentication failed" }
            val body = String(readLimited(connection.inputStream, MAX_TOKEN_BYTES), Charsets.UTF_8)
            val json = JSONObject(body)
            return json.optString("token").ifBlank { json.optString("access_token") }
                .also { require(it.isNotBlank()) { "OCI registry returned no anonymous pull token" } }
        } finally {
            connection.disconnect()
        }
    }

    private fun registryGet(
        registry: Registry,
        path: String,
        token: String,
        accept: String,
    ): Pair<String, String?> {
        val connection = URL(registry.registryBase + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "DistroBoxer/0.6")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "OCI registry manifest request failed: HTTP ${connection.responseCode}"
            }
            val length = connection.contentLengthLong
            require(length < 0 || length <= MAX_MANIFEST_BYTES) { "OCI manifest is too large" }
            val body = String(readLimited(connection.inputStream, MAX_MANIFEST_BYTES), Charsets.UTF_8)
            return body to connection.getHeaderField("Docker-Content-Digest")
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "OCI registry response exceeds its size limit" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun verifyManifestDigest(body: String, expected: String?) {
        if (expected.isNullOrBlank()) return
        require(expected.matches(Regex("sha256:[a-f0-9]{64}"))) { "Registry returned an unsupported manifest digest" }
        val actual = "sha256:" + sha256(body.toByteArray(Charsets.UTF_8))
        require(actual == expected) { "OCI manifest digest verification failed" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun selectPlatformManifest(descriptors: JSONArray, architecture: String): JSONObject? {
        for (index in 0 until descriptors.length()) {
            val item = descriptors.getJSONObject(index)
            val platform = item.optJSONObject("platform") ?: continue
            if (platform.optString("os") == "linux" &&
                platform.optString("architecture") == architecture &&
                platform.optString("variant").let { it.isBlank() || architecture != "arm64" || it == "v8" }
            ) return item
        }
        return null
    }

    private suspend fun downloadBlob(
        registry: Registry,
        digest: String,
        token: String,
        target: File,
        expectedSize: Long,
    ) {
        var currentUrl = "${registry.registryBase}/v2/${registry.repository}/blobs/$digest"
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "DistroBoxer/0.6")
            if (redirect == 0) connection.setRequestProperty("Authorization", "Bearer $token")
            val response = connection.responseCode
            if (response in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("OCI blob redirect has no location")
                require(redirect < MAX_REDIRECTS) { "Too many OCI blob redirects" }
                val resolved = URL(URL(currentUrl), location)
                require(resolved.protocol == "https" && isAllowedBlobHost(resolved.host)) {
                    "OCI blob redirected to an untrusted host"
                }
                currentUrl = resolved.toString()
                connection.disconnect()
            } else {
                require(response == HttpURLConnection.HTTP_OK) { "OCI blob download failed: HTTP $response" }
                val contentLength = connection.contentLengthLong
                require(contentLength < 0 || contentLength == expectedSize) { "OCI blob size does not match manifest" }
                val digestEngine = MessageDigest.getInstance("SHA-256")
                var received = 0L
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                require(received <= expectedSize && received <= MAX_LAYER_BYTES) { "OCI blob exceeded declared size" }
                                output.write(buffer, 0, count)
                                digestEngine.update(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                require(received == expectedSize) { "OCI blob is truncated" }
                val actual = digestEngine.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                require("sha256:$actual" == digest) { "OCI layer digest verification failed" }
                return
            }
        }
        error("Unable to download OCI blob")
    }

    private fun isAllowedBlobHost(host: String): Boolean =
        host == "registry-1.docker.io" ||
            host == "ghcr.io" ||
            host == "production.cloudflare.docker.com" ||
            host == "pkg-containers.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DistroBoxer OCI downloads", NotificationManager.IMPORTANCE_LOW),
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
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_DISTRO_ID = "distro_id"
        const val KEY_IMAGE = "oci_image"
        const val KEY_TAG = "oci_tag"
        const val KEY_ARCH = "oci_arch"
        const val KEY_OCI_MANIFEST = "oci_manifest"
        const val KEY_PROGRESS = "oci_progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "distro_oci_downloads"
        private const val NOTIFICATION_ID = 7311
        private const val MAX_REDIRECTS = 5
        private const val MAX_LAYER_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        private const val MAX_TOKEN_BYTES = 64 * 1024
        private val SUPPORTED_LAYER_TYPES = setOf(
            "application/vnd.oci.image.layer.v1.tar+gzip",
            "application/vnd.docker.image.rootfs.diff.tar.gzip",
            "application/vnd.oci.image.layer.v1.tar",
            "application/vnd.docker.image.rootfs.diff.tar",
        )
    }
}

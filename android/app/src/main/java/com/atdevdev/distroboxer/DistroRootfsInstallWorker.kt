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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/** Extracts a previously verified gzip tarball into the selected private rootfs. */
class DistroRootfsInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val distroId = inputData.getString(DistroArchiveDownloadWorker.KEY_DISTRO_ID).orEmpty()
        val archivePath = inputData.getString(DistroArchiveDownloadWorker.KEY_ARCHIVE_PATH).orEmpty()
        val expectedHash = inputData.getString(DistroArchiveDownloadWorker.KEY_SHA256).orEmpty()
        val archiveFormat = inputData.getString(DistroArchiveDownloadWorker.KEY_ARCHIVE_FORMAT).orEmpty()

        if (distroId.isBlank() || archivePath.isBlank() || expectedHash.isBlank() ||
            archiveFormat !in setOf("tar.gz", "tar.xz", "tar.zst", "tar")) {
            return Result.failure(workDataOf(DistroArchiveDownloadWorker.KEY_ERROR to "Missing verified archive details"))
        }

        createNotificationChannel()
        setForeground(foregroundInfo("Installing $distroId root filesystem"))

        return try {
            val installError = withContext(Dispatchers.IO) {
                NativeBridge.nativeInstallArchive(
                    distroId,
                    applicationContext.filesDir.absolutePath,
                    archivePath,
                    archiveFormat,
                    expectedHash,
                    object : NativeInstallListener {
                        override fun onInstallLog(message: String) {
                            runBlocking {
                                setProgress(workDataOf(KEY_LIVE_LOG to message))
                            }
                        }
                    },
                )
            }
            if (installError == "") {
                Result.success(workDataOf("installed_distro_id" to distroId))
            } else {
                Result.failure(
                    workDataOf(
                        DistroArchiveDownloadWorker.KEY_ERROR to
                            (installError ?: "Native installer returned no result"),
                    ),
                )
            }
        } catch (error: Exception) {
            Result.failure(workDataOf(DistroArchiveDownloadWorker.KEY_ERROR to (error.message ?: "Installation failed")))
        } catch (error: LinkageError) {
            Result.failure(workDataOf(DistroArchiveDownloadWorker.KEY_ERROR to "Native Rust installer is unavailable"))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DistroBoxer installation", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun foregroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DistroBoxer")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_LIVE_LOG = "live_install_log"
        private const val CHANNEL_ID = "distro_installation"
        private const val NOTIFICATION_ID = 7302
    }
}

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Applies verified OCI layers through the shared Rust engine. */
class DistroOciInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val distroId = inputData.getString(DistroOciDownloadWorker.KEY_DISTRO_ID).orEmpty()
        val manifestPath = inputData.getString(DistroOciDownloadWorker.KEY_OCI_MANIFEST).orEmpty()
        if (distroId.isBlank() || manifestPath.isBlank()) {
            return Result.failure(workDataOf(DistroOciDownloadWorker.KEY_ERROR to "Missing OCI installation details"))
        }

        createNotificationChannel()
        setForeground(foregroundInfo("Validando e instalando $distroId"))

        return try {
            val error = withContext(Dispatchers.IO) {
                NativeBridge.nativeInstallOci(
                    distroId,
                    applicationContext.filesDir.absolutePath,
                    manifestPath,
                    object : NativeInstallListener {
                        override fun onInstallLog(message: String) {
                            runBlocking { setProgress(workDataOf(KEY_LIVE_LOG to message)) }
                        }
                    },
                )
            }
            if (error == "") {
                try {
                    withContext(Dispatchers.IO) {
                        ProotRuntime(applicationContext).validateDistro(distroId)
                    }
                    Result.success(workDataOf("installed_distro_id" to distroId))
                } catch (validationError: Exception) {
                    val reason = "La instalación se extrajo, pero no pudo ejecutarse con PRoot: ${validationError.message ?: "falló la prueba de entorno"}"
                    runCatching {
                        NativeBridge.nativeSetInstallFailed(
                            distroId,
                            applicationContext.filesDir.absolutePath,
                            reason,
                        )
                    }
                    Result.failure(workDataOf(DistroOciDownloadWorker.KEY_ERROR to reason))
                }
            } else {
                Result.failure(workDataOf(DistroOciDownloadWorker.KEY_ERROR to (error ?: "OCI installation failed")))
            }
        } catch (error: Exception) {
            Result.failure(workDataOf(DistroOciDownloadWorker.KEY_ERROR to (error.message ?: "OCI installation failed")))
        } catch (error: LinkageError) {
            Result.failure(workDataOf(DistroOciDownloadWorker.KEY_ERROR to "Native OCI installer is unavailable"))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DistroBoxer OCI installation", NotificationManager.IMPORTANCE_LOW),
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
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_LIVE_LOG = "oci_install_live_log"
        private const val CHANNEL_ID = "distro_oci_installation"
        private const val NOTIFICATION_ID = 7312
    }
}

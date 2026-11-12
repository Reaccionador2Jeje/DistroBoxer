package com.atdevdev.distroboxer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OverwritingInputMerger
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules a verified download followed by native rootfs extraction. */
object DistroDownloadScheduler {
    fun enqueue(
        context: Context,
        distroId: String,
        sourceUrl: String,
        expectedSha256: String,
        fileName: String,
    ) {
        enqueueInternal(context, distroId, sourceUrl, expectedSha256, "", fileName)
    }

    /** Uses a checksum manifest served beside the official archive. */
    fun enqueueWithChecksumManifest(
        context: Context,
        distroId: String,
        sourceUrl: String,
        checksumUrl: String,
        fileName: String,
        archiveFormat: String = "tar.gz",
    ) {
        enqueueInternal(context, distroId, sourceUrl, "", checksumUrl, fileName, archiveFormat)
    }

    private fun enqueueInternal(
        context: Context,
        distroId: String,
        sourceUrl: String,
        expectedSha256: String,
        checksumUrl: String,
        fileName: String,
        archiveFormat: String = "tar.gz",
    ) {
        val download = OneTimeWorkRequestBuilder<DistroArchiveDownloadWorker>()
            .setInputData(
                workDataOf(
                    DistroArchiveDownloadWorker.KEY_DISTRO_ID to distroId,
                    DistroArchiveDownloadWorker.KEY_SOURCE_URL to sourceUrl,
                    DistroArchiveDownloadWorker.KEY_SHA256 to expectedSha256,
                    DistroArchiveDownloadWorker.KEY_CHECKSUM_URL to checksumUrl,
                    DistroArchiveDownloadWorker.KEY_FILE_NAME to fileName,
                    DistroArchiveDownloadWorker.KEY_ARCHIVE_FORMAT to archiveFormat,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val install = OneTimeWorkRequestBuilder<DistroRootfsInstallWorker>()
            .setInputMerger(OverwritingInputMerger::class.java)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "distroboxer-install-$distroId",
                ExistingWorkPolicy.KEEP,
                download,
            )
            .then(install)
            .enqueue()
    }
}

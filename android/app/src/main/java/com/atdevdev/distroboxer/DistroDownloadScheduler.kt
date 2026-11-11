package com.atdevdev.distroboxer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
        val download = OneTimeWorkRequestBuilder<DistroArchiveDownloadWorker>()
            .setInputData(
                workDataOf(
                    DistroArchiveDownloadWorker.KEY_DISTRO_ID to distroId,
                    DistroArchiveDownloadWorker.KEY_SOURCE_URL to sourceUrl,
                    DistroArchiveDownloadWorker.KEY_SHA256 to expectedSha256,
                    DistroArchiveDownloadWorker.KEY_FILE_NAME to fileName,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val install = OneTimeWorkRequestBuilder<DistroRootfsInstallWorker>().build()

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

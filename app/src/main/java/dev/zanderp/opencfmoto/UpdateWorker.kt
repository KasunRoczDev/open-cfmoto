// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Setup → "Automatic updates": once a day, on Wi‑Fi with battery to spare, fetch this repo's latest
 * release and install it. Riding or projecting → try again later (replacing the app would drop the
 * dash). No "Install unknown apps" access yet → a notification that opens the update check instead.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AppSettings.autoUpdate(ctx)) return Result.success()
        return try {
            val release = UpdateChecker.check(ctx, manual = false, ignoreInterval = true) ?: return Result.success()
            when {
                !ApkUpdater.safeToInstallNow() -> {
                    LogBus.log("[UPDATE] ${release.version} available — waiting until you're off the bike")
                    Result.retry()
                }
                !ApkUpdater.canInstall(ctx) -> {
                    UpdateNotices.available(ctx, release.version)
                    Result.success()
                }
                else -> {
                    val apk = ApkUpdater.download(ctx, release)
                    // Re-check: the rider may have connected while the download ran.
                    if (!ApkUpdater.safeToInstallNow()) Result.retry()
                    else { ApkUpdater.install(ctx, apk, interactive = false); Result.success() }
                }
            }
        } catch (e: Exception) {
            LogBus.log("[UPDATE] background update failed: ${e.message}")
            Result.success()   // next daily run tries again; don't hammer GitHub
        }
    }

    companion object {
        private const val WORK_NAME = "auto_update"

        /** Idempotent (KEEP), like the other daily workers; the setting is read on each run. */
        fun schedule(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.zanderp.opencfmoto.AndroidAutoService
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeRideService
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.backup.FullBackup
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Me ▸ Backup & restore: one zip with every ride, the fuel log, maintenance, photos and settings. */
@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastBackup by remember { mutableStateOf(AppSettings.lastBackupMs(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Uri, FullBackup.Manifest>?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { exportTo(ctx, uri) } }
            busy = false
            result.onSuccess { m ->
                lastBackup = m.exportedAtMs
                toast(ctx, ctx.getString(R.string.backup_saved, m.trips))
            }.onFailure { e ->
                LogBus.log("[BACKUP] save failed: $e")
                toast(ctx, ctx.getString(R.string.backup_save_failed, e.message ?: e.toString()))
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val m = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { FullBackup.readManifest(it) } }
            }
            m.onSuccess { pending = uri to it }
                .onFailure { e -> toast(ctx, ctx.getString(R.string.backup_not_valid, e.message ?: e.toString())) }
        }
    }

    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.backup_title), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            Text(stringResource(R.string.backup_save_desc), style = OcmType.body, color = c.text)
            Text(stringResource(R.string.backup_private), style = OcmType.body, color = c.text2)
            Text(
                lastBackup?.let { stringResource(R.string.backup_last, dateText(it)) } ?: stringResource(R.string.backup_never),
                style = OcmType.body, color = c.text2,
            )
            PrimaryButton(
                stringResource(R.string.backup_save), Icons.Rounded.Save, enabled = !busy,
                onClick = { save.launch(FullBackup.suggestedFileName(System.currentTimeMillis())) },
            )
        }
        OcmCard {
            Text(stringResource(R.string.backup_restore_desc), style = OcmType.body, color = c.text)
            PrimaryButton(
                stringResource(R.string.backup_restore), Icons.Rounded.Restore, enabled = !busy,
                onClick = {
                    if (AndroidAutoService.isRunning || BikeRideService.running) toast(ctx, ctx.getString(R.string.backup_stop_first))
                    else open.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
            )
        }
    }

    pending?.let { (uri, m) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Text(stringResource(R.string.backup_confirm_message, dateText(m.exportedAtMs), m.trips, m.fuelLogs, m.maintenanceLogs, m.photos))
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { restoreFrom(ctx, uri) } }
                        result.onSuccess { restartApp(ctx) }.onFailure { e ->
                            busy = false
                            LogBus.log("[BACKUP] restore failed: $e")
                            toast(ctx, ctx.getString(R.string.backup_restore_failed, e.message ?: e.toString()))
                        }
                    }
                }) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.dash_cancel)) } },
        )
    }
}

private fun prefsDir(ctx: Context) = File(ctx.applicationInfo.dataDir, "shared_prefs")

private fun exportTo(ctx: Context, uri: Uri): FullBackup.Manifest {
    val now = System.currentTimeMillis()
    // Stamp first so the backup itself carries its own "last backup" time.
    AppSettings.setLastBackupMs(ctx, now)
    val m = ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
        FullBackup.export(ctx.filesDir, prefsDir(ctx), out, BuildConfig.VERSION_NAME, now)
    }
    LogBus.log("[BACKUP] saved ${m.trips} rides, ${m.fuelLogs} fuel log(s), ${m.maintenanceLogs} maintenance log(s), ${m.photos} photo(s), ${m.settingsFiles} settings file(s)")
    return m
}

private fun restoreFrom(ctx: Context, uri: Uri): FullBackup.Manifest {
    val m = ctx.contentResolver.openInputStream(uri)!!.use {
        FullBackup.restore(it, ctx.filesDir, prefsDir(ctx), File(ctx.cacheDir, "restore-staging"))
    }
    LogBus.log("[BACKUP] restored backup from ${Date(m.exportedAtMs)} — restarting")
    return m
}

/** Restored prefs are only read cleanly by a fresh process — relaunch before anything rewrites them. */
private fun restartApp(ctx: Context) {
    ctx.startActivity(Intent.makeRestartActivityTask(ComponentName(ctx, MainActivity::class.java)))
    Runtime.getRuntime().exit(0)
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

private fun dateText(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(ms))

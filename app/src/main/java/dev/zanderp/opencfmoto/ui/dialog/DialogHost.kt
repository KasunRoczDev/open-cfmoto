// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.dialog

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ReleaseNotes
import dev.zanderp.opencfmoto.connect.AppDialog
import dev.zanderp.opencfmoto.connect.ConnectionController
import dev.zanderp.opencfmoto.ui.design.Ocm

/** Renders the controller's pending dialog. Texts are the old MainActivity dialogs' texts. */
@Composable
fun DialogHost(dialog: AppDialog?, c: ConnectionController) {
    when (dialog) {
        null -> Unit
        is AppDialog.Rival -> AlertDialog(
            onDismissRequest = c::onRivalDismiss,
            title = { Text(stringResource(R.string.main_rival_title)) },
            text = {
                Text(
                    if (dialog.installed) {
                        "The official CFMoto/EasyConnect app is running and holding the bike's link ports, so " +
                            "the dash stays blank. Close it, then reconnect.\n\n" +
                            "\"Close & retry\" tries to stop it for you; if the dash is still blank, use " +
                            "\"App settings\" and tap Force stop."
                    } else {
                        "Something is holding the bike's link ports (10920-10922). Close any other CFMoto/EasyConnect app and reconnect."
                    },
                )
            },
            confirmButton = { TextButton(onClick = c::onRivalCloseAndRetry) { Text("Close & retry") } },
            dismissButton = {
                Column {
                    TextButton(onClick = c::onRivalAppSettings) { Text("App settings") }
                    TextButton(onClick = c::onRivalDismiss) { Text("Dismiss") }
                }
            },
        )
        AppDialog.VpnKillSwitch -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text("VPN is blocking the bike") },
            text = {
                Text(
                    "Android Auto reached the phone, but a VPN kill-switch is blocking the bike Wi‑Fi " +
                        "(Network.bindSocket → EPERM).\n\nFix (any one):\n• Turn the VPN off for this ride\n" +
                        "• Disable Always-on VPN → \"Block connections without VPN\"\n" +
                        "• Allow LAN / local network in the VPN app (PCAPdroid, AdGuard, etc.)\n\nThen tap Connect again.",
                )
            },
            confirmButton = { TextButton(onClick = c::onVpnSettings) { Text("VPN settings") } },
            dismissButton = { TextButton(onClick = c::dismissDialog) { Text("Dismiss") } },
        )
        is AppDialog.NearbyBt -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text(stringResource(R.string.main_aa_need_nearby_title)) },
            text = {
                Text(
                    stringResource(
                        if (dialog.needNearby) R.string.main_aa_need_nearby_message else R.string.main_aa_need_bt_on_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { c.onNearbySettings(dialog.needNearby) }) {
                    Text(stringResource(R.string.main_aa_need_nearby_settings))
                }
            },
            dismissButton = { TextButton(onClick = c::dismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
        AppDialog.CrashRecovery -> AlertDialog(
            onDismissRequest = c::onCrashKeepLogs,
            title = { Text(stringResource(R.string.main_crash_title)) },
            text = {
                Text(
                    "OpenCfMoto closed unexpectedly last time. The log and crash report were saved — " +
                        "open Logs and tap Share Logs so we can see what happened.",
                )
            },
            confirmButton = { TextButton(onClick = c::onCrashShareLogs) { Text("Share Logs") } },
            dismissButton = {
                Column {
                    TextButton(onClick = c::onCrashKeepLogs) { Text("Keep logs") }
                    TextButton(onClick = c::onCrashDismiss) { Text("Dismiss") }
                }
            },
        )
        is AppDialog.Update -> {
            val textColor = Ocm.colors.text.toArgb()
            AlertDialog(
                onDismissRequest = c::dismissDialog,
                title = { Text("Update ${dialog.release.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AndroidView(factory = { ctx ->
                            TextView(ctx).apply {
                                text = ReleaseNotes.toSpanned(dialog.release.notes)
                                movementMethod = LinkMovementMethod.getInstance()
                                setTextColor(textColor)
                            }
                        })
                    }
                },
                confirmButton = { TextButton(onClick = { c.onUpdateDownload(dialog.release) }) { Text("Download") } },
                dismissButton = {
                    Column {
                        TextButton(onClick = { c.onUpdateSkip(dialog.release) }) { Text("Skip") }
                        TextButton(onClick = c::dismissDialog) { Text("Later") }
                    }
                },
            )
        }
        AppDialog.MirrorReady -> AlertDialog(
            onDismissRequest = {},  // not cancelable (as before)
            title = { Text(stringResource(R.string.main_mirror_ready_title)) },
            text = {
                Text(
                    "Entire screen — best for riding. Uses Setup ▸ Screen fit and Setup ▸ Mirror orientation " +
                        "(Match dash rotates the phone so the TFT fills).\n\nSingle app — that app must stay on screen; " +
                        "Android sends no frames in the background. Prefer GPX / Tracks or Android Auto for pocket use.\n\n" +
                        "Bike touch does not drive mirrored apps. Continue connects and sends OpenCfMoto to the background.",
                )
            },
            confirmButton = { TextButton(onClick = c::onMirrorContinue) { Text("Continue") } },
        )
        is AppDialog.ReportProblem -> {
            var problem by remember { mutableStateOf("") }
            var model by remember { mutableStateOf(dialog.defaultModel) }
            var year by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = c::dismissDialog,
                title = { Text(stringResource(R.string.main_report_a_problem)) },
                text = {
                    Column {
                        Text(stringResource(R.string.main_report_problem_message))
                        OutlinedTextField(problem, { problem = it }, Modifier.fillMaxWidth(), minLines = 3,
                            placeholder = { Text(stringResource(R.string.main_report_hint_problem)) })
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.main_report_hint_bike_model)) })
                        OutlinedTextField(year, { year = it }, Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.main_report_hint_year)) })
                    }
                },
                confirmButton = { TextButton(onClick = { c.onReportShare(problem, model, year) }) { Text(stringResource(R.string.main_share)) } },
                dismissButton = { TextButton(onClick = c::dismissDialog) { Text(stringResource(R.string.dash_cancel)) } },
            )
        }
    }
}

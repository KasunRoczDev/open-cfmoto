// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.maintenance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.TripStore
import dev.zanderp.opencfmoto.maintenance.ItemState
import dev.zanderp.opencfmoto.maintenance.MaintenanceEstimate
import dev.zanderp.opencfmoto.maintenance.MaintenanceItem
import dev.zanderp.opencfmoto.maintenance.MaintenanceSchedule
import dev.zanderp.opencfmoto.maintenance.MaintenanceState
import dev.zanderp.opencfmoto.maintenance.MaintenanceStatus
import dev.zanderp.opencfmoto.maintenance.MaintenanceStore
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.StatusChip
import dev.zanderp.opencfmoto.ui.design.StatusKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Me ▸ Maintenance: estimated odometer, every schedule item with its status, mark-serviced. */
@Composable
fun MaintenanceScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val bike: SavedBike? = remember { BikeMemory.selected(ctx) }
    val items: List<MaintenanceItem> = remember(bike) { MaintenanceSchedule.forModel(bike?.model) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    var reloadTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<MaintenanceState?>(null) }
    var estimateKm by remember { mutableStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }

    // Reload every time the screen comes back (a ride may have been saved while the phone was pocketed),
    // so the estimate, statuses and "now" are never stale when the rider re-syncs or marks something serviced.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, bike, reloadTick) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (bike == null) { loaded = true; return@repeatOnLifecycle }
            val (s, est) = withContext(Dispatchers.IO) {
                val s = MaintenanceStore.load(ctx.applicationContext, bike.raw)
                s to (if (s != null) MaintenanceEstimate.estimateKm(s, TripStore.list(ctx.applicationContext), bike.raw) else 0.0)
            }
            state = s
            estimateKm = est
            now = System.currentTimeMillis()
            loaded = true
        }
    }

    // Android 13+: ask once, the first time this screen is opened, so an overdue notification can show.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showBaselineDialog by remember { mutableStateOf(false) }
    var serviceTarget by remember { mutableStateOf<MaintenanceItem?>(null) }

    OcmScreen {
        Text(stringResource(R.string.maintenance_title), style = OcmType.screenTitle, color = Ocm.colors.text)
        val currentState = state
        val currentBike = bike
        when {
            !loaded -> {}
            currentBike == null || items.isEmpty() -> OcmCard {
                Text(stringResource(R.string.maintenance_no_schedule), style = OcmType.body, color = Ocm.colors.text2)
            }
            currentState == null -> OcmCard {
                Text(stringResource(R.string.maintenance_set_baseline_prompt), style = OcmType.body, color = Ocm.colors.text2)
                PrimaryButton(stringResource(R.string.maintenance_set_odometer), Icons.Rounded.Speed, onClick = { showBaselineDialog = true })
            }
            else -> {
                OcmCard {
                    Text(stringResource(R.string.maintenance_odometer_label), style = OcmType.body, color = Ocm.colors.text2)
                    Text(stringResource(R.string.maintenance_odometer_value, estimateKm), style = OcmType.stat, color = Ocm.colors.text)
                    PrimaryButton(stringResource(R.string.maintenance_set_odometer), Icons.Rounded.Speed, onClick = { showBaselineDialog = true })
                }
                val visible = MaintenanceEstimate.visibleItems(items, currentState.items)
                val byCategory = visible.groupBy { it.category }
                for ((category, categoryItems) in byCategory) {
                    SectionLabel(category)
                    OcmCard {
                        val sorted = categoryItems.sortedBy {
                            statusRank(MaintenanceEstimate.status(it, currentState.items[it.id], estimateKm, now, currentState.trackingStartedAtMs))
                        }
                        for (item in sorted) {
                            ItemRow(item, currentState, estimateKm, now, onClick = { serviceTarget = item })
                        }
                    }
                }
            }
        }
    }

    if (showBaselineDialog) {
        BaselineDialog(
            initialKm = estimateKm,
            onDismiss = { showBaselineDialog = false },
            onConfirm = { km ->
                if (bike != null) {
                    MaintenanceStore.setBaseline(ctx.applicationContext, bike.raw, km, System.currentTimeMillis())
                    reloadTick++
                    onChanged()
                }
                showBaselineDialog = false
            },
        )
    }
    serviceTarget?.let { item ->
        ServiceConfirmDialog(
            item = item,
            onDismiss = { serviceTarget = null },
            onConfirm = {
                if (bike != null) {
                    MaintenanceStore.markServiced(ctx.applicationContext, bike.raw, item.id, estimateKm, System.currentTimeMillis())
                    reloadTick++
                    onChanged()
                }
                serviceTarget = null
            },
        )
    }
}

private fun statusRank(status: MaintenanceStatus): Int = when (status) {
    MaintenanceStatus.OVERDUE -> 0
    MaintenanceStatus.DUE_SOON -> 1
    MaintenanceStatus.OK -> 2
}

@Composable
private fun ItemRow(item: MaintenanceItem, state: MaintenanceState, estimateKm: Double, nowMs: Long, onClick: () -> Unit) {
    val itemState = state.items[item.id]
    val status = MaintenanceEstimate.status(item, itemState, estimateKm, nowMs, state.trackingStartedAtMs)
    val (kind, label) = when (status) {
        MaintenanceStatus.OK -> StatusKind.LIVE to stringResource(R.string.maintenance_status_ok)
        MaintenanceStatus.DUE_SOON -> StatusKind.BUSY to stringResource(R.string.maintenance_status_due_soon)
        MaintenanceStatus.OVERDUE -> StatusKind.ERROR to stringResource(R.string.maintenance_status_overdue)
    }
    val detail = dueDetailText(item, itemState, estimateKm, nowMs, state.trackingStartedAtMs)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = OcmType.title, color = Ocm.colors.text)
                Text(item.action, style = OcmType.body, color = Ocm.colors.text2)
            }
            StatusChip(label, kind)
        }
        if (detail != null) Text(detail, style = OcmType.body, color = Ocm.colors.text2)
    }
}

@Composable
private fun dueDetailText(item: MaintenanceItem, itemState: ItemState?, estimateKm: Double, nowMs: Long, trackingStartedAtMs: Long): String? {
    val remKm = MaintenanceEstimate.remainingKm(item, itemState, estimateKm)
    val remDays = MaintenanceEstimate.remainingDays(item, itemState, trackingStartedAtMs, nowMs)
    // Show the axis that's closest to due relative to its due-soon window, so the text explains the pill
    // (e.g. 250 km left on a 2-year item reads "in 250 km", not "in 600 days"). Items always have one axis.
    val pick = listOfNotNull(
        remKm?.let { Triple("km", it, it / MaintenanceEstimate.DUE_SOON_KM) },
        remDays?.let { Triple("days", it.toDouble(), it.toDouble() / MaintenanceEstimate.DUE_SOON_DAYS) },
    ).minByOrNull { it.third }?.let { it.first to it.second } ?: return null
    return if (pick.first == "km") {
        val km = pick.second
        if (km >= 0) stringResource(R.string.maintenance_due_in_km, km.toInt()) else stringResource(R.string.maintenance_overdue_km, (-km).toInt())
    } else {
        val days = pick.second.toLong()
        if (days >= 0) stringResource(R.string.maintenance_due_in_days, days.toInt()) else stringResource(R.string.maintenance_overdue_days, (-days).toInt())
    }
}

@Composable
private fun BaselineDialog(initialKm: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (initialKm > 0) initialKm.toInt().toString() else "") }
    val parsed = text.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maintenance_odometer_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.maintenance_odometer_dialog_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConfirm(it) } }, enabled = parsed != null && parsed >= 0) {
                Text(stringResource(R.string.maintenance_set_odometer))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}

@Composable
private fun ServiceConfirmDialog(item: MaintenanceItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maintenance_mark_serviced_title)) },
        text = { Text(stringResource(R.string.maintenance_mark_serviced_message, item.name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.maintenance_status_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}

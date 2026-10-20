// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.TripStore
import dev.zanderp.opencfmoto.fuel.FuelEntry
import dev.zanderp.opencfmoto.fuel.FuelEstimate
import dev.zanderp.opencfmoto.fuel.FuelSpecs
import dev.zanderp.opencfmoto.fuel.FuelStatus
import dev.zanderp.opencfmoto.fuel.FuelStore
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The range colours shared with the dash side panel: amber when low, red when nearly empty. */
private val LOW = Color(0xFFFBBF24)
private val CRITICAL = Color(0xFFF87171)

/** Me ▸ Fuel: estimated range, km/L, spend, and the fill-up log (tap an entry to delete it). */
@Composable
fun FuelScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val bike: SavedBike? = remember { BikeMemory.selected(ctx) }
    val hasSpec = remember(bike) { FuelSpecs.forModel(bike?.model) != null }
    var reloadTick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(FuelStatus.EMPTY) }
    var entries by remember { mutableStateOf(emptyList<FuelEntry>()) }
    var loaded by remember { mutableStateOf(false) }

    // Reload whenever the screen comes back: a ride saved while the phone was pocketed changes the range.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, bike, reloadTick) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (bike == null) { loaded = true; return@repeatOnLifecycle }
            val (list, s) = withContext(Dispatchers.IO) {
                val app = ctx.applicationContext
                val list = FuelStore.list(app, bike.raw)
                list to FuelEstimate.status(
                    list, TripStore.list(app), bike.raw, FuelSpecs.forModel(bike.model), System.currentTimeMillis(), TimeZone.getDefault(),
                )
            }
            entries = list.reversed()
            status = s
            loaded = true
        }
    }

    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FuelEntry?>(null) }
    val c = Ocm.colors

    OcmScreen {
        Text(stringResource(R.string.fuel_title), style = OcmType.screenTitle, color = c.text)
        when {
            !loaded -> {}
            bike == null -> OcmCard { Text(stringResource(R.string.fuel_no_bike), style = OcmType.body, color = c.text2) }
            else -> {
                OcmCard {
                    Text(stringResource(R.string.fuel_range_label), style = OcmType.body, color = c.text2)
                    val range = status.rangeKm
                    if (range != null) {
                        val color = when {
                            range < FuelEstimate.CRITICAL_RANGE_KM -> CRITICAL
                            range < FuelEstimate.LOW_RANGE_KM -> LOW
                            else -> c.text
                        }
                        Text(stringResource(R.string.fuel_range_value, range.toInt()), style = OcmType.stat, color = color)
                        Text(
                            stringResource(
                                R.string.fuel_range_detail, one(status.litresLeft ?: 0.0), one(status.tankL ?: 0.0), (status.kmSinceFill ?: 0.0).toInt(),
                            ),
                            style = OcmType.body, color = c.text2,
                        )
                    } else {
                        Text(
                            stringResource(if (hasSpec) R.string.fuel_range_prompt else R.string.fuel_range_no_spec),
                            style = OcmType.body, color = c.text2,
                        )
                    }
                    PrimaryButton(stringResource(R.string.fuel_add), Icons.Rounded.LocalGasStation, onClick = { showAdd = true })
                }
                status.kmPerLitre?.let { kmL ->
                    OcmCard {
                        Text(stringResource(R.string.fuel_economy_label), style = OcmType.body, color = c.text2)
                        Text(stringResource(R.string.fuel_economy_value, one(kmL)), style = OcmType.stat, color = c.text)
                        Text(
                            stringResource(if (status.measured) R.string.fuel_economy_measured else R.string.fuel_economy_spec),
                            style = OcmType.body, color = c.text2,
                        )
                        status.costPerKm?.let { Text(stringResource(R.string.fuel_cost_per_km, two(it)), style = OcmType.body, color = c.text) }
                        if (status.monthLitres > 0) {
                            Text(
                                if (status.monthCost > 0) stringResource(R.string.fuel_this_month, one(status.monthLitres), money(status.monthCost))
                                else stringResource(R.string.fuel_this_month_litres, one(status.monthLitres)),
                                style = OcmType.body, color = c.text,
                            )
                        }
                    }
                }
                if (entries.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.fuel_fillups))
                    OcmCard {
                        for (e in entries) EntryRow(e, onClick = { deleteTarget = e })
                    }
                }
                Text(stringResource(R.string.fuel_rides_note), style = OcmType.body, color = c.text2)
            }
        }
    }

    if (showAdd) {
        AddDialog(
            onDismiss = { showAdd = false },
            onConfirm = { litres, cost, full ->
                if (bike != null) {
                    val now = System.currentTimeMillis()
                    FuelStore.add(ctx.applicationContext, bike.raw, FuelEntry(now.toString(), now, litres, cost, full))
                    reloadTick++
                    onChanged()
                }
                showAdd = false
            },
        )
    }
    deleteTarget?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.fuel_delete_title)) },
            text = { Text(stringResource(R.string.fuel_delete_message, one(e.litres), whenText(e.atMs))) },
            confirmButton = {
                TextButton(onClick = {
                    if (bike != null) {
                        FuelStore.delete(ctx.applicationContext, bike.raw, e.id)
                        reloadTick++
                        onChanged()
                    }
                    deleteTarget = null
                }) { Text(stringResource(R.string.fuel_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.dash_cancel)) } },
        )
    }
}

@Composable
private fun EntryRow(e: FuelEntry, onClick: () -> Unit) {
    val c = Ocm.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.fuel_entry, one(e.litres), stringResource(if (e.fullTank) R.string.fuel_full else R.string.fuel_partial)),
            style = OcmType.title, color = c.text,
        )
        Text(whenText(e.atMs) + (e.cost?.let { " · " + money(it) } ?: ""), style = OcmType.body, color = c.text2)
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onConfirm: (litres: Double, cost: Double?, full: Boolean) -> Unit) {
    var litresText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    val litres = litresText.replace(',', '.').toDoubleOrNull()
    val cost = costText.replace(',', '.').toDoubleOrNull()
    val ok = litres != null && litres > 0 && litres <= 60 && (costText.isBlank() || (cost != null && cost >= 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fuel_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = litresText, onValueChange = { litresText = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.fuel_dialog_litres)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = costText, onValueChange = { costText = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(stringResource(R.string.fuel_dialog_cost)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(Modifier.fillMaxWidth().clickable { full = !full }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = full, onCheckedChange = { full = it })
                    Text(stringResource(R.string.fuel_dialog_full))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ok) onConfirm(litres!!, cost?.takeIf { costText.isNotBlank() }, full) }, enabled = ok) {
                Text(stringResource(R.string.fuel_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}

private fun one(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
private fun two(v: Double) = String.format(Locale.getDefault(), "%.2f", v)
private fun money(v: Double) = String.format(Locale.getDefault(), if (v >= 100) "%,.0f" else "%.2f", v)
private fun whenText(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(ms))

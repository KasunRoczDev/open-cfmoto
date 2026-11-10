// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tpms

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zanderp.opencfmoto.QrScanActivity
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.tpms.PressureUnit
import dev.zanderp.opencfmoto.tpms.TpmsMonitor
import dev.zanderp.opencfmoto.tpms.TpmsNotifier
import dev.zanderp.opencfmoto.tpms.TpmsProtocol
import dev.zanderp.opencfmoto.tpms.TpmsRules
import dev.zanderp.opencfmoto.tpms.TpmsSettings
import dev.zanderp.opencfmoto.tpms.Wheel
import dev.zanderp.opencfmoto.tpms.WheelState
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmSpace
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.PrimaryButton
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.SegmentedChoice
import dev.zanderp.opencfmoto.ui.design.Notice
import dev.zanderp.opencfmoto.ui.design.StatusKind
import kotlinx.coroutines.delay
import java.util.Locale

/** The alarm red and stale amber shared with the fuel range colours. */
private val ALARM = Color(0xFFF87171)
private val STALE = Color(0xFFFBBF24)

/** Me ▸ Tyre pressure: live front/rear readings from the BLE valve sensors, pairing and alarm limits. */
@Composable
fun TpmsScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Fast, unfiltered scanning only while this screen is on show.
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> TpmsMonitor.acquire(ctx, "screen", foreground = true)
                Lifecycle.Event.ON_STOP -> TpmsMonitor.release("screen")
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            TpmsMonitor.release("screen")
        }
    }
    val st by TpmsMonitor.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }

    var pairFor by remember { mutableStateOf<Wheel?>(null) }
    var assignSensor by remember { mutableStateOf<TpmsMonitor.Nearby?>(null) }
    var editLimits by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { TpmsMonitor.retry(ctx) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { TpmsMonitor.retry(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val c = Ocm.colors
    val s = st.settings
    val lim = st.limits
    val spec = st.spec
    OcmScreen {
        Text(
            stringResource(R.string.tpms_title), style = OcmType.screenTitle, color = c.text,
            modifier = Modifier.semantics { heading() },
        )
        when (st.problem) {
            TpmsMonitor.Problem.NO_PERMISSION -> {
                Notice(stringResource(R.string.tpms_need_permission), StatusKind.ERROR)
                PrimaryButton(stringResource(R.string.tpms_allow), Icons.Rounded.Bluetooth, onClick = {
                    permLauncher.launch(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                    )
                })
            }
            TpmsMonitor.Problem.BLUETOOTH_OFF -> {
                Notice(stringResource(R.string.tpms_bt_off), StatusKind.ERROR)
                PrimaryButton(stringResource(R.string.tpms_turn_on_bt), Icons.Rounded.Bluetooth, onClick = {
                    runCatching { btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                })
            }
            TpmsMonitor.Problem.NO_BLE -> Notice(stringResource(R.string.tpms_no_ble), StatusKind.ERROR)
            TpmsMonitor.Problem.SCAN_FAILED -> {
                Notice(stringResource(R.string.tpms_scan_failed), StatusKind.ERROR)
                OutlinedButton(onClick = { TpmsMonitor.retry(ctx) }) { Text(stringResource(R.string.tpms_retry)) }
            }
            null -> if (st.scanning) Notice(stringResource(R.string.tpms_listening), StatusKind.LIVE)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(OcmSpace.s)) {
            for (w in Wheel.entries) {
                WheelCard(
                    w, st.wheel(w), lim, spec?.recommendedKpa(w, s.loaded), st.learning == w, now, Modifier.weight(1f),
                    onPair = { pairFor = w },
                )
            }
        }
        Text(stringResource(R.string.tpms_sleep_hint), style = OcmType.body, color = c.text2)

        if (spec != null) {
            SectionLabel(stringResource(R.string.tpms_spec_title, spec.modelName))
            OcmCard {
                val unit = TpmsNotifier.unitLabel(ctx, s.unit)
                fun p(kpa: Double) = s.unit.format(kpa) + " " + unit
                Text(stringResource(R.string.tpms_spec_wheel, stringResource(R.string.tpms_front), spec.frontSize, p(spec.frontKpa)), style = OcmType.body, color = c.text)
                Text(stringResource(R.string.tpms_spec_wheel, stringResource(R.string.tpms_rear), spec.rearSize, p(spec.rearKpa)), style = OcmType.body, color = c.text)
                if (spec.hasLoadedFigures) {
                    Text(stringResource(R.string.tpms_spec_loaded, p(spec.frontLoadedKpa), p(spec.rearLoadedKpa)), style = OcmType.body, color = c.text2)
                }
                Text(stringResource(R.string.tpms_spec_tread, spec.minTread), style = OcmType.body, color = c.text2)
                if (spec.hasLoadedFigures) {
                    ToggleRow(stringResource(R.string.tpms_loaded), stringResource(R.string.tpms_loaded_desc), s.loaded) { on ->
                        TpmsMonitor.updateSettings(ctx, s.copy(loaded = on))
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.tpms_dash_title))
        OcmCard {
            ToggleRow(stringResource(R.string.tpms_dash_toggle), stringResource(R.string.tpms_dash_desc), s.onDash) { on ->
                TpmsMonitor.updateSettings(ctx, s.copy(onDash = on))
            }
            if (s.onDash) DashPreview(st, now)
        }

        SectionLabel(stringResource(R.string.tpms_nearby))
        OcmCard {
            val list = st.nearby
            if (list.isEmpty()) {
                Text(stringResource(R.string.tpms_nearby_empty), style = OcmType.body, color = c.text2)
            } else {
                for (n in list) {
                    val on = Wheel.entries.firstOrNull { st.wheel(it).address.equals(n.address, true) }
                    NearbyRow(n, s, on, onClick = { assignSensor = n })
                }
            }
        }

        SectionLabel(stringResource(R.string.tpms_settings))
        OcmCard {
            Text(stringResource(R.string.tpms_unit), style = OcmType.body, color = c.text2)
            SegmentedChoice(
                PressureUnit.entries.map { TpmsNotifier.unitLabel(ctx, it) }, s.unit.ordinal,
                onSelect = { TpmsMonitor.updateSettings(ctx, s.copy(unit = PressureUnit.entries[it])) },
            )
            Text(stringResource(R.string.tpms_temp_unit), style = OcmType.body, color = c.text2)
            SegmentedChoice(listOf("°C", "°F"), if (s.fahrenheit) 1 else 0, onSelect = { TpmsMonitor.updateSettings(ctx, s.copy(fahrenheit = it == 1)) })
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { editLimits = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tpms_limits), style = OcmType.title, color = c.text)
                    Text(limitsSummary(ctx, lim), style = OcmType.body, color = c.text2)
                    Text(
                        when {
                            s.customLimits -> stringResource(R.string.tpms_limits_custom)
                            spec != null -> stringResource(R.string.tpms_limits_from_spec, spec.modelName)
                            else -> stringResource(R.string.tpms_limits_default)
                        },
                        style = OcmType.caption, color = c.text2,
                    )
                }
            }
            ToggleRow(stringResource(R.string.tpms_alerts), stringResource(R.string.tpms_alerts_desc), s.alerts) { on ->
                TpmsMonitor.updateSettings(ctx, s.copy(alerts = on))
                if (on && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ToggleRow(stringResource(R.string.tpms_background), stringResource(R.string.tpms_background_desc), s.background) { on ->
                TpmsMonitor.updateSettings(ctx, s.copy(background = on))
                if (on && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    pairFor?.let { w ->
        PairDialog(w, st.wheel(w), onDismiss = { pairFor = null })
    }
    assignSensor?.let { n ->
        AlertDialog(
            onDismissRequest = { assignSensor = null },
            title = { Text(stringResource(R.string.tpms_use_sensor, n.reading.pairId ?: n.address)) },
            text = { Text(stringResource(R.string.tpms_use_sensor_desc)) },
            confirmButton = {
                TextButton(onClick = { TpmsMonitor.assign(ctx, Wheel.FRONT, n.address); assignSensor = null }) { Text(stringResource(R.string.tpms_front)) }
            },
            dismissButton = {
                TextButton(onClick = { TpmsMonitor.assign(ctx, Wheel.REAR, n.address); assignSensor = null }) { Text(stringResource(R.string.tpms_rear)) }
            },
        )
    }
    if (editLimits) LimitsDialog(s, lim, spec?.modelName, onDismiss = { editLimits = false })
}

@Composable
private fun WheelCard(
    w: Wheel, ws: WheelState, s: TpmsSettings, recommendedKpa: Double?, learning: Boolean, now: Long, modifier: Modifier, onPair: () -> Unit,
) {
    val c = Ocm.colors
    val ctx = LocalContext.current
    val r = ws.reading
    val alarms = r?.let { TpmsRules.alarms(it, w, s) }.orEmpty()
    val live = TpmsRules.isLive(ws.atMs, now)
    OcmCard(onClick = onPair, modifier = modifier) {
        Text(stringResource(if (w == Wheel.FRONT) R.string.tpms_front else R.string.tpms_rear).uppercase(), style = OcmType.label, color = c.text2)
        if (ws.paired && r != null) {
            val color = when {
                alarms.isNotEmpty() -> ALARM
                !live -> c.text2
                else -> c.text
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.unit.format(r.pressureKpa), style = OcmType.stat, color = color)
                Spacer(Modifier.width(4.dp))
                Text(TpmsNotifier.unitLabel(ctx, s.unit), style = OcmType.body, color = c.text2, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(s.formatTemp(r.tempC) + " · " + String.format(Locale.getDefault(), "%.1f V", r.batteryV), style = OcmType.body, color = c.text)
            if (recommendedKpa != null) {
                val diff = s.unit.fromKpa(r.pressureKpa) - s.unit.fromKpa(recommendedKpa)
                val step = if (s.unit == PressureUnit.BAR) 0.005 else 0.05
                val delta = when {
                    diff > step -> " · +" + s.unit.format(r.pressureKpa - recommendedKpa)
                    diff < -step -> " · −" + s.unit.format(recommendedKpa - r.pressureKpa)
                    else -> ""
                }
                Text(
                    stringResource(R.string.tpms_set_to, s.unit.format(recommendedKpa)) + delta,
                    style = OcmType.caption, color = c.text2,
                )
            }
            if (alarms.isNotEmpty()) {
                Text(alarms.joinToString(" · ") { TpmsNotifier.label(ctx, it) }, style = OcmType.body, color = ALARM)
            }
            Text(
                if (live) stringResource(R.string.tpms_live) else seenAgo(ctx, now - ws.atMs),
                style = OcmType.caption, color = if (live) c.live else STALE,
            )
        } else {
            Text("—", style = OcmType.stat, color = c.text2)
            if (recommendedKpa != null) {
                Text(
                    stringResource(R.string.tpms_set_to, s.unit.format(recommendedKpa) + " " + TpmsNotifier.unitLabel(ctx, s.unit)),
                    style = OcmType.caption, color = c.text2,
                )
            }
            Text(
                when {
                    learning -> stringResource(R.string.tpms_learning)
                    ws.pendingId != null -> stringResource(R.string.tpms_waiting_id, ws.pendingId)
                    ws.paired -> stringResource(R.string.tpms_waiting_first)
                    else -> stringResource(R.string.tpms_not_paired)
                },
                style = OcmType.body, color = if (learning || ws.pendingId != null) STALE else c.text2,
            )
        }
        Text(
            stringResource(if (ws.paired || ws.pendingId != null) R.string.tpms_change else R.string.tpms_pair),
            style = OcmType.title, color = c.accent,
        )
    }
}

@Composable
private fun NearbyRow(n: TpmsMonitor.Nearby, s: TpmsSettings, on: Wheel?, onClick: () -> Unit) {
    val c = Ocm.colors
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (n.reading.pairId?.let { "ID $it" } ?: n.address) +
                    (on?.let { " · " + ctx.getString(if (it == Wheel.FRONT) R.string.tpms_front else R.string.tpms_rear) } ?: ""),
                style = OcmType.title, color = c.text,
            )
            Text(
                s.unit.format(n.reading.pressureKpa) + " " + TpmsNotifier.unitLabel(ctx, s.unit) + " · " + s.formatTemp(n.reading.tempC) +
                    " · " + String.format(Locale.getDefault(), "%.1f V", n.reading.batteryV) + " · " + n.rssi + " dBm",
                style = OcmType.body, color = c.text2,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Ocm.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OcmType.title, color = c.text)
            Text(desc, style = OcmType.body, color = c.text2)
        }
        Spacer(Modifier.width(OcmSpace.s))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
}

@Composable
private fun PairDialog(w: Wheel, ws: WheelState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var idText by remember { mutableStateOf(ws.pendingId ?: "") }
    val qr = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            idText = TpmsProtocol.normalizePairId(res.data?.getStringExtra(QrScanActivity.RESULT_QR))
        }
    }
    val id = TpmsProtocol.normalizePairId(idText)
    val wheelName = stringResource(if (w == Wheel.FRONT) R.string.tpms_front else R.string.tpms_rear)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tpms_pair_title, wheelName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tpms_pair_id_desc), style = OcmType.body)
                OutlinedTextField(
                    value = idText, onValueChange = { idText = it.take(12) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tpms_sensor_id)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { qr.launch(Intent(ctx, QrScanActivity::class.java)) }) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.tpms_scan_qr))
                        }
                    },
                )
                TextButton(onClick = { if (id.length == 6) { TpmsMonitor.pairById(ctx, w, id); onDismiss() } }, enabled = id.length == 6) {
                    Text(stringResource(R.string.tpms_pair_by_id))
                }
                Text(stringResource(R.string.tpms_learn_desc), style = OcmType.body)
                TextButton(onClick = { TpmsMonitor.startLearn(w); onDismiss() }) { Text(stringResource(R.string.tpms_learn)) }
                Text(stringResource(R.string.tpms_pick_desc), style = OcmType.body)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
        dismissButton = {
            if (ws.paired || ws.pendingId != null) {
                TextButton(onClick = { TpmsMonitor.unpair(ctx, w); onDismiss() }) { Text(stringResource(R.string.tpms_unpair)) }
            }
        },
    )
}

@Composable
private fun LimitsDialog(s: TpmsSettings, lim: TpmsSettings, specName: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val u = s.unit
    var fl by remember { mutableStateOf(u.format(lim.frontLowKpa, Locale.US)) }
    var fh by remember { mutableStateOf(u.format(lim.frontHighKpa, Locale.US)) }
    var rl by remember { mutableStateOf(u.format(lim.rearLowKpa, Locale.US)) }
    var rh by remember { mutableStateOf(u.format(lim.rearHighKpa, Locale.US)) }
    var hot by remember { mutableStateOf(s.highTempC.toString()) }
    fun num(t: String) = t.replace(',', '.').toDoubleOrNull()
    val vals = listOf(num(fl), num(fh), num(rl), num(rh))
    val hotC = hot.toIntOrNull()
    val ok = vals.all { it != null && it >= 0 } && vals[0]!! < vals[1]!! && vals[2]!! < vals[3]!! && hotC != null && hotC in 30..120
    val unit = TpmsNotifier.unitLabel(ctx, u)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tpms_limits)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(stringResource(R.string.tpms_front_low, unit), fl, Modifier.weight(1f)) { fl = it }
                    NumField(stringResource(R.string.tpms_front_high, unit), fh, Modifier.weight(1f)) { fh = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(stringResource(R.string.tpms_rear_low, unit), rl, Modifier.weight(1f)) { rl = it }
                    NumField(stringResource(R.string.tpms_rear_high, unit), rh, Modifier.weight(1f)) { rh = it }
                }
                NumField(stringResource(R.string.tpms_hot_limit), hot, Modifier.fillMaxWidth()) { hot = it }
                if (specName != null && s.customLimits) {
                    TextButton(onClick = {
                        TpmsMonitor.updateSettings(ctx, s.copy(customLimits = false))
                        onDismiss()
                    }) { Text(stringResource(R.string.tpms_limits_use_spec, specName)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                TpmsMonitor.updateSettings(
                    ctx,
                    s.copy(
                        frontLowKpa = u.toKpa(vals[0]!!), frontHighKpa = u.toKpa(vals[1]!!),
                        rearLowKpa = u.toKpa(vals[2]!!), rearHighKpa = u.toKpa(vals[3]!!), highTempC = hotC!!,
                        customLimits = true,
                    ),
                )
                onDismiss()
            }) { Text(stringResource(R.string.tpms_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel)) } },
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun limitsSummary(ctx: android.content.Context, s: TpmsSettings): String {
    val u = s.unit
    val unit = TpmsNotifier.unitLabel(ctx, u)
    return ctx.getString(
        R.string.tpms_limits_summary,
        u.format(s.frontLowKpa) + "–" + u.format(s.frontHighKpa) + " " + unit,
        u.format(s.rearLowKpa) + "–" + u.format(s.rearHighKpa) + " " + unit,
        s.formatTemp(s.highTempC),
    )
}

private fun seenAgo(ctx: android.content.Context, ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> ctx.getString(R.string.tpms_seen_min, min)
        min < 48 * 60 -> ctx.getString(R.string.tpms_seen_h, min / 60)
        else -> ctx.getString(R.string.tpms_seen_d, min / (24 * 60))
    }
}

/** One-line summary for the Me tab and the Ride card, e.g. "F 29.2 · R 31.0 psi". Null when nothing is paired. */
fun tyreSummary(ctx: android.content.Context, st: TpmsMonitor.State): String? {
    if (!st.anyPaired && st.wheels.values.none { it.pendingId != null }) return null
    val s = st.settings
    val parts = Wheel.entries.map { w ->
        val letter = ctx.getString(if (w == Wheel.FRONT) R.string.tpms_front_short else R.string.tpms_rear_short)
        letter + " " + (st.wheel(w).reading?.let { s.unit.format(it.pressureKpa) } ?: "–")
    }
    return parts.joinToString(" · ") + " " + TpmsNotifier.unitLabel(ctx, s.unit)
}

/** True when any paired wheel's last reading is out of range. */
fun tyreAlarm(st: TpmsMonitor.State): Boolean =
    Wheel.entries.any { w -> st.wheel(w).reading?.let { TpmsRules.alarms(it, w, st.limits).isNotEmpty() } == true }

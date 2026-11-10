// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.zanderp.opencfmoto.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Listens for the tyre sensors' BLE advertisements and keeps the live state for the screen, the
 * Ride card and the background service. Scanning is reference-counted by "clients": the Tyre
 * pressure screen asks for a fast unfiltered scan (so the nearby list shows everything), the
 * background service for a low-power scan filtered to the sensors' names / service UUID — Android
 * stops unfiltered scans while the screen is off.
 *
 * All state changes happen on the main thread (scan callbacks arrive there).
 */
object TpmsMonitor {
    data class Nearby(val address: String, val reading: TpmsProtocol.Reading, val rssi: Int, val atMs: Long)

    enum class Problem { NO_BLE, BLUETOOTH_OFF, NO_PERMISSION, SCAN_FAILED }

    data class State(
        val settings: TpmsSettings = TpmsSettings(),
        val wheels: Map<Wheel, WheelState> = mapOf(Wheel.FRONT to WheelState(), Wheel.REAR to WheelState()),
        val nearby: List<Nearby> = emptyList(),
        val scanning: Boolean = false,
        val learning: Wheel? = null,
        val problem: Problem? = null,
        /** The selected bike's tyre figures, when we know its model. */
        val spec: TyreSpec? = null,
    ) {
        fun wheel(w: Wheel) = wheels[w] ?: WheelState()
        /** Settings with the limits actually in force (the rider's own, or from [spec]). */
        val limits: TpmsSettings get() = settings.effective(spec)
        val anyPaired get() = wheels.values.any { it.paired }
    }

    private const val NEARBY_TTL_MS = 3 * 60_000L
    private const val LEARN_TIMEOUT_MS = 3 * 60_000L
    private const val PERSIST_EVERY_MS = 30_000L
    private const val LOG_EVERY_MS = 60_000L
    private val KY_SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("000027a5-0000-1000-8000-00805f9b34fb"))
    /** Seen on real "TPMS" sensors next to the name (16-bit UUID 0xA827) — a second hook for the filtered scan. */
    private val TPMS_SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("0000a827-0000-1000-8000-00805f9b34fb"))

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var app: Context? = null
    private val clients = mutableMapOf<String, Boolean>()   // tag → wants foreground (fast, unfiltered) scanning
    private var scanMode: Boolean? = null                    // null = not scanning; true = foreground mode
    private val lastPersist = mutableMapOf<Wheel, Long>()
    private val lastLog = mutableMapOf<String, Long>()
    private val lastAlarms = mutableMapOf<Wheel, Set<TpmsAlarm>>()
    private val endLearn = Runnable { cancelLearn() }

    /** Loads pairing + thresholds once per process. */
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        _state.value = State(
            settings = TpmsStore.settings(c),
            wheels = Wheel.entries.associateWith { TpmsStore.wheel(c, it) },
            spec = specFor(c),
        )
    }

    private fun specFor(c: Context): TyreSpec? =
        runCatching { TyreSpecs.forModel(dev.zanderp.opencfmoto.BikeMemory.selected(c)?.model) }.getOrNull()

    // ---- clients ----------------------------------------------------------------------------

    fun acquire(ctx: Context, tag: String, foreground: Boolean): Unit = onMain {
        init(ctx)
        // The rider may have picked another bike or typed its model in Garage since.
        val spec = specFor(ctx.applicationContext)
        if (spec != _state.value.spec) _state.value = _state.value.copy(spec = spec)
        clients[tag] = foreground
        applyScan()
    }

    fun release(tag: String): Unit = onMain {
        clients.remove(tag)
        if (clients.values.none { it }) cancelLearn()
        applyScan()
    }

    /** Re-check Bluetooth / permission after the rider fixed them. */
    fun retry(ctx: Context): Unit = onMain {
        init(ctx)
        scanMode = null.also { stopScan() }
        applyScan()
    }

    // ---- pairing ----------------------------------------------------------------------------

    fun startLearn(w: Wheel): Unit = onMain {
        main.removeCallbacks(endLearn)
        _state.value = _state.value.copy(learning = w)
        main.postDelayed(endLearn, LEARN_TIMEOUT_MS)
        LogBus.log("[tpms] learning ${w.key}: waiting for an inflated sensor")
    }

    fun cancelLearn(): Unit = onMain {
        main.removeCallbacks(endLearn)
        if (_state.value.learning != null) _state.value = _state.value.copy(learning = null)
    }

    /** ID-mode: the sensor pairs itself when it next advertises this printed ID. */
    fun pairById(ctx: Context, w: Wheel, id: String): Unit = onMain {
        init(ctx)
        val norm = TpmsProtocol.normalizePairId(id)
        if (norm.length != 6) return@onMain
        // A sensor already advertising nearby pairs right away.
        val hit = _state.value.nearby.firstOrNull { it.reading.pairId == norm }
        if (hit != null) assign(ctx, w, hit.address) else setWheel(w, WheelState(pendingId = norm), persist = true)
        LogBus.log("[tpms] ${w.key}: pair by ID $norm${if (hit != null) " (seen nearby)" else " — waiting for it to wake"}")
    }

    fun assign(ctx: Context, w: Wheel, address: String): Unit = onMain {
        init(ctx)
        val near = _state.value.nearby.firstOrNull { it.address == address }
        // A sensor moves wheels instead of being on both.
        Wheel.entries.filter { it != w && _state.value.wheel(it).address.equals(address, true) }.forEach {
            setWheel(it, WheelState(), persist = true)
        }
        setWheel(w, WheelState(address = address, reading = near?.reading, atMs = near?.atMs ?: 0, rssi = near?.rssi), persist = true)
        lastAlarms.remove(w)
        if (_state.value.learning == w) cancelLearn()
        LogBus.log("[tpms] ${w.key} paired to ${near?.reading?.pairId ?: address}")
        TpmsService.sync(ctx)
    }

    fun unpair(ctx: Context, w: Wheel): Unit = onMain {
        init(ctx)
        setWheel(w, WheelState(), persist = true)
        lastAlarms.remove(w)
        TpmsNotifier.clear(ctx, w)
        TpmsService.sync(ctx)
    }

    fun updateSettings(ctx: Context, s: TpmsSettings): Unit = onMain {
        init(ctx)
        TpmsStore.saveSettings(ctx, s)
        _state.value = _state.value.copy(settings = s)
        TpmsService.sync(ctx)
    }

    // ---- scanning ---------------------------------------------------------------------------

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            LogBus.log("[tpms] scan failed: $errorCode")
            scanMode = null
            _state.value = _state.value.copy(scanning = false, problem = Problem.SCAN_FAILED)
        }
    }

    private fun hasScanPermission(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    private fun applyScan() {
        val c = app ?: return
        val want: Boolean? = if (clients.isEmpty()) null else clients.values.any { it }
        if (want == scanMode && (want == null || _state.value.problem == null)) return
        stopScan()
        if (want == null) {
            _state.value = _state.value.copy(scanning = false, problem = null, nearby = emptyList())
            return
        }
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val problem = when {
            adapter == null || !c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> Problem.NO_BLE
            !hasScanPermission(c) -> Problem.NO_PERMISSION
            !adapter.isEnabled -> Problem.BLUETOOTH_OFF
            else -> null
        }
        val scanner = adapter?.bluetoothLeScanner
        if (problem != null || scanner == null) {
            _state.value = _state.value.copy(scanning = false, problem = problem ?: Problem.BLUETOOTH_OFF)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (want) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val filters = if (want) emptyList() else listOf(
            ScanFilter.Builder().setDeviceName("TPMS").build(),
            ScanFilter.Builder().setDeviceName("KY").build(),
            ScanFilter.Builder().setServiceUuid(KY_SERVICE).build(),
            ScanFilter.Builder().setServiceUuid(TPMS_SERVICE).build(),
        )
        try {
            scanner.startScan(filters, settings, callback)
            scanMode = want
            _state.value = _state.value.copy(scanning = true, problem = null)
            LogBus.log("[tpms] scanning (${if (want) "screen, all adverts" else "background, sensors only"})")
        } catch (e: Exception) {
            LogBus.log("[tpms] can't scan: $e")
            _state.value = _state.value.copy(scanning = false, problem = if (e is SecurityException) Problem.NO_PERMISSION else Problem.SCAN_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanMode == null) return
        scanMode = null
        val c = app ?: return
        try {
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    private fun handle(result: ScanResult) {
        val c = app ?: return
        val bytes = result.scanRecord?.bytes
        val name = runCatching { result.scanRecord?.deviceName }.getOrNull()
        val r = TpmsProtocol.decode(bytes, name) ?: return
        val address = result.device?.address ?: return
        val now = System.currentTimeMillis()
        val s0 = _state.value

        if (now - (lastLog[address] ?: 0) > LOG_EVERY_MS) {
            lastLog[address] = now
            LogBus.log(
                "[tpms] ${r.family} ${r.pairId ?: "-"} ${String.format(java.util.Locale.US, "%.0f", r.pressureKpa)} kPa " +
                    "${r.tempC}°C ${r.batteryV} V rssi ${result.rssi} raw ${bytes?.let { TpmsProtocol.toHex(it) }}",
            )
        }

        val nearby = (s0.nearby.filter { it.address != address && now - it.atMs < NEARBY_TTL_MS } + Nearby(address, r, result.rssi, now))
            .sortedByDescending { it.rssi }
        _state.value = s0.copy(nearby = nearby)

        TpmsRules.pairTarget(address, r, s0.wheels, s0.learning)?.let { w ->
            assign(c, w, address)
            return
        }

        for (w in Wheel.entries) {
            val ws = _state.value.wheel(w)
            if (!ws.address.equals(address, ignoreCase = true)) continue
            val persist = now - (lastPersist[w] ?: 0) > PERSIST_EVERY_MS
            setWheel(w, ws.copy(reading = r, atMs = now, rssi = result.rssi), persist)
            if (persist) lastPersist[w] = now
            val alarms = TpmsRules.alarms(r, w, _state.value.limits)
            val prev = lastAlarms[w].orEmpty()
            if (alarms != prev) {
                lastAlarms[w] = alarms
                if (alarms.isNotEmpty()) {
                    TpmsStore.saveWheel(c, w, _state.value.wheel(w))
                    LogBus.log("[tpms] ${w.key} alarm: ${alarms.joinToString()}")
                }
                if (_state.value.settings.alerts) TpmsNotifier.onAlarms(c, w, alarms, prev, r, _state.value.limits)
            }
            TpmsService.onReading(c)
        }
    }

    private fun setWheel(w: Wheel, ws: WheelState, persist: Boolean) {
        _state.value = _state.value.copy(wheels = _state.value.wheels + (w to ws))
        if (persist) app?.let { TpmsStore.saveWheel(it, w, ws) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

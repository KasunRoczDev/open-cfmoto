// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.TripMapActivity
import dev.zanderp.opencfmoto.report.WeekReport
import dev.zanderp.opencfmoto.report.WeeklyReport
import dev.zanderp.opencfmoto.report.WeeklyReportLoader
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.ride.RideSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Trips ▸ Weekly report: distance, eco %, fuel and the best ride, week by week. */
@Composable
fun WeeklyReportScreen() {
    val ctx = LocalContext.current
    val zone = remember { TimeZone.getDefault() }
    val thisWeek = remember { WeeklyReport.weekStart(System.currentTimeMillis(), zone) }
    var week by remember { mutableLongStateOf(thisWeek) }
    var report by remember { mutableStateOf<WeekReport?>(null) }
    LaunchedEffect(week) {
        report = null
        report = withContext(Dispatchers.IO) { WeeklyReportLoader.load(ctx, week) }
    }
    val c = Ocm.colors

    OcmScreen {
        Text(stringResource(R.string.report_title), style = OcmType.screenTitle, color = c.text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { week = WeeklyReport.shiftWeeks(week, -1, zone) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.report_prev_week), tint = c.text)
            }
            Text(
                if (week == thisWeek) stringResource(R.string.report_this_week) else rangeText(week, zone),
                style = OcmType.title, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { week = WeeklyReport.shiftWeeks(week, 1, zone) }, enabled = week < thisWeek) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.report_next_week),
                    tint = if (week < thisWeek) c.text else c.text2.copy(alpha = 0.3f),
                )
            }
        }
        val r = report ?: return@OcmScreen
        if (r.rides.isEmpty()) {
            OcmCard { Text(stringResource(R.string.report_no_rides), style = OcmType.body, color = c.text2) }
            return@OcmScreen
        }
        OcmCard {
            Text(stringResource(R.string.report_distance), style = OcmType.body, color = c.text2)
            Text(stringResource(R.string.report_km, one(r.km)), style = OcmType.stat, color = c.text)
            Text(
                stringResource(R.string.report_rides_line, r.rides.size, RideSummary.durationText(r.movingMs), r.avgKmh ?: 0),
                style = OcmType.body, color = c.text2,
            )
            r.vsPrevPercent?.let {
                Text(
                    stringResource(if (it >= 0) R.string.report_vs_prev_up else R.string.report_vs_prev_down, kotlin.math.abs(it)),
                    style = OcmType.body, color = c.text,
                )
            }
            DayBars(r.dailyKm)
        }
        OcmCard {
            Text(stringResource(R.string.report_eco), style = OcmType.body, color = c.text2)
            Text(r.ecoPercent?.let { "$it%" } ?: "—", style = OcmType.stat, color = c.accent)
            Text(stringResource(R.string.report_eco_desc), style = OcmType.body, color = c.text2)
            Text(stringResource(R.string.report_top_speed, r.maxKmh), style = OcmType.body, color = c.text)
        }
        if (r.fuelLitres != null || r.fillUps > 0) {
            OcmCard {
                Text(stringResource(R.string.report_fuel), style = OcmType.body, color = c.text2)
                r.fuelLitres?.let { l ->
                    Text(
                        r.fuelCost?.let { stringResource(R.string.report_fuel_used_cost, one(l), money(it)) }
                            ?: stringResource(R.string.report_fuel_used, one(l)),
                        style = OcmType.title, color = c.text,
                    )
                }
                if (r.fillUps > 0) {
                    Text(stringResource(R.string.report_fillups, r.fillUps, money(r.fillUpSpend)), style = OcmType.body, color = c.text2)
                }
            }
        }
        r.best?.let { b ->
            OcmCard(onClick = { TripMapActivity.start(ctx, b.id) }) {
                Text(stringResource(R.string.report_best), style = OcmType.body, color = c.text2)
                Text(dayText(b.startMs), style = OcmType.title, color = c.text)
                Text(
                    stringResource(R.string.report_best_line, one(b.km), RideSummary.durationText(b.movingMs)) +
                        (b.ecoPercent?.let { stringResource(R.string.report_best_eco, it) } ?: ""),
                    style = OcmType.body, color = c.text2,
                )
            }
        }
    }
}

/** Mon..Sun bars, tallest = the week's longest day. */
@Composable
private fun DayBars(km: List<Double>) {
    val c = Ocm.colors
    val max = km.maxOrNull()?.takeIf { it > 0 } ?: return
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val slot = size.width / km.size
        val barW = slot * 0.55f
        km.forEachIndexed { i, v ->
            val h = (size.height * (v / max)).toFloat().coerceAtLeast(if (v > 0) 4f else 2f)
            drawRoundRect(
                color = if (v > 0) c.accent else c.text2.copy(alpha = 0.25f),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        for (d in listOf("M", "T", "W", "T", "F", "S", "S")) {
            Text(d, style = OcmType.label, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
}

private fun one(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
private fun money(v: Double) = String.format(Locale.getDefault(), if (v >= 100) "%,.0f" else "%.2f", v)
private fun dayText(ms: Long) = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
private fun rangeText(weekStart: Long, zone: TimeZone): String {
    val f = SimpleDateFormat("d MMM", Locale.getDefault()).apply { timeZone = zone }
    return f.format(Date(weekStart)) + " – " + f.format(Date(WeeklyReport.shiftWeeks(weekStart, 1, zone) - 1))
}

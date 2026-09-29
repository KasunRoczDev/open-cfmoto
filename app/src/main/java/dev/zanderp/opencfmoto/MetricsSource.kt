// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * GPS feed for the metrics strip. Owns its own listener (TripRecorder only runs when trip logging
 * is on); Android shares the GPS hardware between listeners. Callbacks arrive on the main looper.
 */
class MetricsSource(
    private val ctx: Context,
    private val onFix: (Location) -> Unit,
) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Returns false when permission is missing or GPS is off (the strip then shows `--`). */
    fun start(): Boolean {
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            true
        } catch (_: Exception) {
            // SecurityException, or a provider that vanished between the check and the request.
            false
        }
    }

    fun stop() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) = onFix(location)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.R

/** "Park here" tile: saves the phone's last fix as the single parked pin (same shape as the Map hub's). */
object ParkHere {
    private const val REQ_LOCATION = 41

    /** @return true when a pin was saved. Without permission it asks and returns false (tap again). */
    fun mark(activity: Activity): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return false
        }
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        if (loc == null) {
            Toast.makeText(activity, activity.getString(R.string.gpx_need_gps_mark_parking), Toast.LENGTH_SHORT).show()
            return false
        }
        MapPlaces.setParked(activity, MapPlace("Parked", loc.latitude, loc.longitude, "parking", "Marked from Ride"))
        LogBus.log("→ parked spot saved from the Ride tab")
        Toast.makeText(activity, activity.getString(R.string.ui_parked_saved), Toast.LENGTH_SHORT).show()
        return true
    }
}

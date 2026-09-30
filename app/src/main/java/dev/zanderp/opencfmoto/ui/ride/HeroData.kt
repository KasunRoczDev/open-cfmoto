// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.ride

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MapPlace
import dev.zanderp.opencfmoto.MapPlaces
import dev.zanderp.opencfmoto.TripStore
import java.time.ZoneId

/** What the Ride hero shows besides the connection state. */
data class HeroData(
    val model: String?,
    val photoPath: String?,
    val summary: RideSummary?,
    val parked: MapPlace?,
) {
    companion object {
        val EMPTY = HeroData(null, null, null, null)
        @Volatile private var tripsFailLogged = false

        /** Reads prefs and the saved rides from disk — call off the main thread. */
        fun load(ctx: Context): HeroData {
            val bike = BikeMemory.selected(ctx)
            val summary = try {
                RideSummary.of(TripStore.list(ctx), System.currentTimeMillis(), ZoneId.systemDefault())
            } catch (e: Exception) {
                if (!tripsFailLogged) {
                    tripsFailLogged = true
                    LogBus.log("[RIDE] saved rides unreadable for the hero card: $e")
                }
                null
            }
            return HeroData(bike?.model, bike?.photoPath, summary, MapPlaces.parked(ctx))
        }
    }
}

/** Downsampled decode of the Garage photo (blocking). Null when missing or unreadable. */
fun decodeHeroPhoto(path: String, maxPx: Int = 1080): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    LogBus.log("[RIDE] bike photo unreadable: $e")
    null
}

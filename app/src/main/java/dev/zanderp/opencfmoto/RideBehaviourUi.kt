// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Colours, marker icons and text for the ride-behaviour screens (fixed colours: readable on map tiles). */
object RideBehaviourUi {
    const val TOP = 0xFFD93A3A.toInt()
    const val SLOW = 0xFF0B8C9B.toInt()
    const val STOP = 0xFF5B6773.toInt()
    const val BRAKE = 0xFFF97316.toInt()
    const val ACCEL = 0xFF8B5CF6.toInt()

    fun bandColor(b: SpeedBand): Int = when (b) {
        SpeedBand.SLOW -> 0xFF34D399.toInt()
        SpeedBand.MEDIUM -> 0xFFFBBF24.toInt()
        SpeedBand.FAST -> 0xFFF97316.toInt()
        SpeedBand.VERY_FAST -> 0xFFF87171.toInt()
    }

    /** Round badge with a white ring and a bold [symbol]. */
    fun markerIcon(ctx: Context, symbol: String, color: Int): Drawable {
        val d = ctx.resources.displayMetrics.density
        val size = (26 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        c.drawCircle(r, r, r - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 13 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(symbol, r, r - (text.descent() + text.ascent()) / 2f, text)
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun speedValue(ms: Float, imperial: Boolean): String =
        if (!ms.isFinite()) "—" else (if (imperial) ms * 2.23694f else ms * 3.6f).roundToInt().toString()

    fun speed(ms: Float, imperial: Boolean): String =
        speedValue(ms, imperial) + if (imperial) " mph" else " km/h"

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "$s s"
        }
    }

    fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}

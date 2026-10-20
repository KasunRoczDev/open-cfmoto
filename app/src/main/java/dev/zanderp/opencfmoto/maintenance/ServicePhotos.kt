// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.maintenance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.zanderp.opencfmoto.LogBus
import java.io.File

/**
 * Receipt / job-card photos for [ServiceRecord]s, kept in `filesDir/maintenance_photos/` (so the
 * full backup carries them). Imports are re-encoded to a ≤[MAX_PX] JPEG — a receipt stays readable
 * at a fraction of a 12 MP camera file. ImageDecoder applies the EXIF rotation, so photos stand upright.
 */
object ServicePhotos {
    const val DIR = "maintenance_photos"
    private const val MAX_PX = 1600
    private const val QUALITY = 85

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Copies [uri] in as a new photo and returns its file name, or null if it can't be read. Blocking. */
    fun import(ctx: Context, uri: Uri): String? = try {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, MAX_PX.toDouble() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val name = "svc-${System.currentTimeMillis()}-${(1000..9999).random()}.jpg"
        file(ctx, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bmp.recycle()
        name
    } catch (e: Exception) {
        LogBus.log("[MAINT] photo import failed: $e")
        null
    }
}

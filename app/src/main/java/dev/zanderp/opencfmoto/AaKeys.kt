// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * On-screen pad routing shared by Controls and Dash view. Controls drives the phone Map preview
 * first ([MapInputBridge]) then Android Auto; Dash view drives Android Auto only.
 */
object AaKeys {
    enum class Target { MAP, AA, NONE }

    fun target(mapFirst: Boolean, hasMap: Boolean, hasAa: Boolean): Target = when {
        mapFirst && hasMap -> Target.MAP
        hasAa -> Target.AA
        else -> Target.NONE
    }

    fun noSinkText(mapFirst: Boolean): String =
        if (mapFirst) "Start Android Auto or Map first" else "Connect to the bike first"

    fun key(ctx: Context, code: Int, mapFirst: Boolean) {
        val map = MapInputBridge.keySink
        val aa = AaVideoBridge.keySink
        when (target(mapFirst, map != null, aa != null)) {
            Target.MAP -> map?.invoke(code)
            Target.AA -> aa?.invoke(code)
            Target.NONE -> Toast.makeText(ctx, noSinkText(mapFirst), Toast.LENGTH_SHORT).show()
        }
    }

    fun scroll(ctx: Context, delta: Int, mapFirst: Boolean) {
        val map = MapInputBridge.scrollSink
        val aa = AaVideoBridge.scrollSink
        when (target(mapFirst, map != null, aa != null)) {
            Target.MAP -> map?.invoke(delta)
            Target.AA -> aa?.invoke(delta)
            Target.NONE -> Toast.makeText(ctx, noSinkText(mapFirst), Toast.LENGTH_SHORT).show()
        }
    }

    /** The Assistant needs the mic to hear you — ask for it the first time voice is used. */
    fun ensureMic(activity: Activity, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestCode)
        }
    }
}

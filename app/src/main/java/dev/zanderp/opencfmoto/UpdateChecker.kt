// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK. Automatic check at most once a day before connect (and
 * [UpdateWorker] in the background); failures are silent (bike Wi‑Fi has no internet). Manual check
 * from Me. [ApkUpdater] downloads and installs the asset picked here.
 */
object UpdateChecker {

    /** Set at build time (-PupdateRepo); this fork's repo by default. */
    val REPO: String = BuildConfig.UPDATE_REPO

    private val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "updates"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
        /** Lower-case hex SHA-256 GitHub reports for [apkUrl]; null on older releases. */
        val apkSha256: String? = null,
    ) {
        val downloadUrl: String get() = apkUrl ?: pageUrl
    }

    /**
     * Blocking; call off the main thread. Null = nothing newer / offline / rate-limited.
     * [ignoreInterval]: the daily [UpdateWorker] is its own schedule, so skip the once-a-day gate.
     */
    fun check(context: Context, manual: Boolean, ignoreInterval: Boolean = false): Release? {
        if (!manual && !ignoreInterval && !dueForCheck(context)) return null
        val release = fetch() ?: return null
        markChecked(context)
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return null
        if (!manual && release.version == skipped(context)) return null
        return release
    }

    private fun fetch(): Release? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OpenCfMoto")
        }
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                val apks = buildList {
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        add(Asset(a.optString("name"), a.optString("browser_download_url"), sha256Of(a.optString("digest"))))
                    }
                }
                val apk = pickApk(apks, is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
                Release(
                    version = json.optString("tag_name").ifBlank { json.optString("name") },
                    notes = json.optString("body").trim(),
                    apkUrl = apk?.url,
                    pageUrl = json.optString("html_url", "https://github.com/$REPO/releases"),
                    apkSha256 = apk?.sha256,
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    internal data class Asset(val name: String, val url: String, val sha256: String?)

    /**
     * The APK for this phone: the `armv7` build on 32-bit-only Android, otherwise the first other APK
     * (`OpenCfMoto.apk`). Falls back to any APK so an oddly named release still updates.
     */
    internal fun pickApk(assets: List<Asset>, is64Bit: Boolean): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.isNotBlank() }
        val armv7 = apks.filter { it.name.contains("armv7", ignoreCase = true) }
        val preferred = if (is64Bit) apks - armv7.toSet() else armv7
        return preferred.firstOrNull() ?: apks.firstOrNull()
    }

    /** GitHub's asset `digest` is "sha256:<hex>"; anything else → null (no check possible). */
    internal fun sha256Of(digest: String?): String? =
        digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun dueForCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    private fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun skipped(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)

    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }
}

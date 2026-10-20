// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.backup

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything a rider would hate to lose — rides, fuel log, maintenance, bike photos and every app
 * setting (including the saved bike QR, so its Wi-Fi password) — in one zip they keep themselves.
 * Unlike [dev.zanderp.opencfmoto.SettingsBackup] (bike tuning to share) this is a private, complete
 * copy. Works on plain directories (no Android types), so it's unit-tested on temp folders.
 *
 * Restore merges rides (a ride recorded since the backup survives) and replaces everything else
 * file-by-file. Nothing is deleted. Entries are checked against an allow-list before anything is
 * written, so a crafted zip can't write outside the app's own folders.
 */
object FullBackup {
    const val FORMAT = "opencfmoto.backup"
    const val VERSION = 1
    private const val MANIFEST = "manifest.json"

    /** `filesDir` sub-folders that hold rider data (map tile caches and logs stay out). */
    val FILE_DIRS = listOf("trips", "maintenance", "fuel", "bike_photos")

    /** Third-party or per-install prefs that must not travel between installs. */
    val SKIP_PREFS = setOf(
        "MapboxSharedPreferences.xml",
        "android.app.ActivityThread.IDS.xml",
        "com.google.mlkit.internal.xml",
        "opencfmoto_telemetry.xml",
        "updates.xml",
    )

    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    data class Manifest(
        val exportedAtMs: Long,
        val appVersion: String,
        val trips: Int,
        val fuelLogs: Int,
        val maintenanceLogs: Int,
        val photos: Int,
        val settingsFiles: Int,
    )

    class InvalidBackup(message: String) : Exception(message)

    /** Writes the zip to [out] (not closed) and returns what went in. */
    fun export(filesDir: File, prefsDir: File, out: OutputStream, appVersion: String, nowMs: Long): Manifest {
        val files = FILE_DIRS.associateWith { dir ->
            File(filesDir, dir).listFiles { f -> f.isFile && SAFE_NAME.matches(f.name) }?.sortedBy { it.name }.orEmpty()
        }
        val prefs = prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") && f.name !in SKIP_PREFS && SAFE_NAME.matches(f.name) }
            ?.sortedBy { it.name }.orEmpty()
        val manifest = Manifest(
            exportedAtMs = nowMs,
            appVersion = appVersion,
            trips = files.getValue("trips").count { it.name.endsWith(".json") },
            fuelLogs = files.getValue("fuel").size,
            maintenanceLogs = files.getValue("maintenance").size,
            photos = files.getValue("bike_photos").size,
            settingsFiles = prefs.size,
        )
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(encodeManifest(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((dir, list) in files) for (f in list) put(zip, "files/$dir/${f.name}", f)
        for (f in prefs) put(zip, "shared_prefs/${f.name}", f)
        zip.finish()
        return manifest
    }

    /** The backup's manifest, or throws [InvalidBackup] if [input] isn't an OpenCfMoto backup. */
    fun readManifest(input: InputStream): Manifest {
        val zip = ZipInputStream(input)
        val first = zip.nextEntry ?: throw InvalidBackup("Empty file")
        if (first.name != MANIFEST) throw InvalidBackup("Not an OpenCfMoto backup")
        return decodeManifest(String(zip.readBytes(), Charsets.UTF_8))
    }

    /**
     * Restores [input] into [filesDir] / [prefsDir]. Everything is unpacked to [stagingDir] and
     * checked first; only a fully valid backup touches the real folders.
     */
    fun restore(input: InputStream, filesDir: File, prefsDir: File, stagingDir: File): Manifest {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            val zip = ZipInputStream(input)
            val first = zip.nextEntry ?: throw InvalidBackup("Empty file")
            if (first.name != MANIFEST) throw InvalidBackup("Not an OpenCfMoto backup")
            val manifest = decodeManifest(String(zip.readBytes(), Charsets.UTF_8))
            var total = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (!isAllowedEntry(e.name)) throw InvalidBackup("Unexpected file in backup: ${e.name}")
                val target = File(stagingDir, e.name).apply { parentFile?.mkdirs() }
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        entryBytes += n
                        total += n
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw InvalidBackup("Backup is too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            // Valid — now move into place. Rides merge (same id overwrites); every other file replaces.
            for (dir in FILE_DIRS) {
                val src = File(stagingDir, "files/$dir")
                val dst = File(filesDir, dir).apply { mkdirs() }
                src.listFiles()?.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
            }
            prefsDir.mkdirs()
            File(stagingDir, "shared_prefs").listFiles()?.forEach { it.copyTo(File(prefsDir, it.name), overwrite = true) }
            return manifest
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Only known rider-data folders and safe file names — rejects `..`, absolute paths and anything else. */
    fun isAllowedEntry(name: String): Boolean {
        val parts = name.split('/')
        return when {
            parts.size == 3 && parts[0] == "files" -> parts[1] in FILE_DIRS && SAFE_NAME.matches(parts[2]) && parts[2] != ".." && parts[2] != "."
            parts.size == 2 && parts[0] == "shared_prefs" ->
                SAFE_NAME.matches(parts[1]) && parts[1].endsWith(".xml") && parts[1] !in SKIP_PREFS
            else -> false
        }
    }

    fun suggestedFileName(nowMs: Long): String =
        "opencfmoto-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(nowMs)) + ".zip"

    private fun put(zip: ZipOutputStream, name: String, f: File) {
        zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun encodeManifest(m: Manifest): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exportedAtMs", m.exportedAtMs)
        .put("appVersion", m.appVersion)
        .put("trips", m.trips)
        .put("fuelLogs", m.fuelLogs)
        .put("maintenanceLogs", m.maintenanceLogs)
        .put("photos", m.photos)
        .put("settingsFiles", m.settingsFiles)
        .toString()

    private fun decodeManifest(json: String): Manifest {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: throw InvalidBackup("Backup manifest is unreadable")
        if (o.optString("format") != FORMAT) throw InvalidBackup("Not an OpenCfMoto backup")
        if (o.optInt("version") > VERSION) throw InvalidBackup("Backup is from a newer OpenCfMoto — update the app first")
        return Manifest(
            exportedAtMs = o.optLong("exportedAtMs"),
            appVersion = o.optString("appVersion"),
            trips = o.optInt("trips"),
            fuelLogs = o.optInt("fuelLogs"),
            maintenanceLogs = o.optInt("maintenanceLogs"),
            photos = o.optInt("photos"),
            settingsFiles = o.optInt("settingsFiles"),
        )
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the APK [UpdateChecker] found and installs it in place (no browser, no file manager).
 *
 * Every download is checked before it reaches the installer: GitHub's SHA-256 for the asset, then the
 * archive itself — same package, version not older, signed with the same key as the running app.
 *
 * Android rules this works within: the rider must allow "Install unknown apps" for us once, and the
 * first self-update asks for a tap. From then on we are the installer of record, so on Android 12+
 * later updates go through without a prompt. Never installs mid-ride: replacing the app kills the
 * process, which would drop Android Auto on the dash ([safeToInstallNow]).
 */
object ApkUpdater {

    class UpdateException(message: String) : Exception(message)

    /** "Install unknown apps" granted for this app (Settings → Apps → Special access). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /**
     * Background rule: not projecting, not reconnecting, not logging a ride — replacing the app now
     * loses nothing. (A reconnect may be a Wi‑Fi blip mid-ride, so the worker waits it out.)
     */
    fun safeToInstallNow(): Boolean =
        ConnectionState.phase in setOf(Phase.IDLE, Phase.STOPPED, Phase.ERROR) && !ridingLogged()

    /**
     * Rider tapped Install: refuse only while the dash is live or a ride is being logged. A pending
     * connection attempt is stopped first by [dev.zanderp.opencfmoto.connect.ConnectionController].
     */
    fun manualInstallBlocked(): Boolean =
        ConnectionState.phase == Phase.STREAMING || ConnectionState.phase == Phase.MIRRORING || ridingLogged()

    private fun ridingLogged(): Boolean = BikeRideService.running || TripLogger.current?.recording == true

    /** Blocking; call off the main thread. Returns the verified APK in the cache. */
    fun download(ctx: Context, release: UpdateChecker.Release): File {
        val url = release.apkUrl ?: throw UpdateException("release ${release.version} has no APK")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "update.apk")
        // Pin to a network with internet: while the bike is linked the process is bound to its Wi‑Fi.
        val conn = (AppHttp.internetNetwork()?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "MyMoto-Updater")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode != 200) throw UpdateException("download HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        release.apkSha256?.let { want ->
            if (sha != want) { apk.delete(); throw UpdateException("checksum mismatch") }
        }
        verifyArchive(ctx, apk)
        LogBus.log("[UPDATE] downloaded ${release.version} (${apk.length() / 1024} KB, sha256 ${sha.take(12)}…)")
        return apk
    }

    private fun verifyArchive(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: run { apk.delete(); throw UpdateException("not a valid APK") }
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun fail(why: String): Nothing { apk.delete(); throw UpdateException(why) }
        if (archive.packageName != ctx.packageName) fail("different app (${archive.packageName})")
        if (archive.longVersionCode < installed.longVersionCode) fail("older build (${archive.longVersionCode})")
        if (signers(archive) != signers(installed)) fail("signed with a different key")
    }

    private fun signers(info: PackageInfo): Set<String> {
        val s = info.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { c ->
            MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Hand [apk] to the system installer. [interactive]: the rider is in the app, so if Android needs a
     * confirmation we show it straight away; from the background we post a notification instead.
     */
    fun install(ctx: Context, apk: File, interactive: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(ctx, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_INTERACTIVE, interactive)
            // MUTABLE: the installer fills in the status and, if needed, the confirmation intent.
            val pending = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            LogBus.log("[UPDATE] installing (session $id, ${if (interactive) "in app" else "background"})")
            session.commit(pending.intentSender)
        }
    }
}

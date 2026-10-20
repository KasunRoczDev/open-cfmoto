package dev.zanderp.opencfmoto.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun phone(name: String): Pair<File, File> {
        val root = tmp.newFolder(name)
        return File(root, "files").apply { mkdirs() } to File(root, "shared_prefs").apply { mkdirs() }
    }

    private fun write(dir: File, path: String, text: String) = File(dir, path).apply { parentFile?.mkdirs(); writeText(text) }

    private fun backupOf(files: File, prefs: File): ByteArray =
        ByteArrayOutputStream().also { FullBackup.export(files, prefs, it, "2.0.18", 1_000L) }.toByteArray()

    @Test
    fun roundTripRestoresRiderDataAndSkipsCachesAndPerInstallPrefs() {
        val (files, prefs) = phone("old")
        write(files, "trips/1.json", "trip1")
        write(files, "trips/2.json", "trip2")
        write(files, "fuel/b1.json", "fuel")
        write(files, "maintenance/b1.json", "maint")
        write(files, "bike_photos/b1.jpg", "jpg")
        write(files, "osmdroid/tiles.db", "cache")
        write(files, "last_session.log", "log")
        write(prefs, "opencfmoto_bike.xml", "<map/>")
        write(prefs, "opencfmoto_telemetry.xml", "<uuid/>")

        val zip = backupOf(files, prefs)
        val m = FullBackup.readManifest(ByteArrayInputStream(zip))
        assertEquals(2, m.trips)
        assertEquals(1, m.fuelLogs)
        assertEquals(1, m.photos)
        assertEquals(1, m.settingsFiles)

        val (newFiles, newPrefs) = phone("new")
        FullBackup.restore(ByteArrayInputStream(zip), newFiles, newPrefs, File(newFiles.parentFile, "staging"))
        assertEquals("trip2", File(newFiles, "trips/2.json").readText())
        assertEquals("fuel", File(newFiles, "fuel/b1.json").readText())
        assertEquals("jpg", File(newFiles, "bike_photos/b1.jpg").readText())
        assertEquals("<map/>", File(newPrefs, "opencfmoto_bike.xml").readText())
        assertFalse(File(newFiles, "osmdroid/tiles.db").exists())
        assertFalse(File(newFiles, "last_session.log").exists())
        assertFalse(File(newPrefs, "opencfmoto_telemetry.xml").exists())
        assertFalse(File(newFiles.parentFile, "staging").exists())
    }

    @Test
    fun restoreKeepsRidesRecordedSinceTheBackupAndReplacesTheRest() {
        val (files, prefs) = phone("old")
        write(files, "trips/1.json", "old trip")
        write(files, "fuel/b1.json", "backup fuel")
        val zip = backupOf(files, prefs)

        val (now, nowPrefs) = phone("now")
        write(now, "trips/9.json", "ride after backup")
        write(now, "fuel/b1.json", "phone fuel")
        FullBackup.restore(ByteArrayInputStream(zip), now, nowPrefs, File(now.parentFile, "staging"))
        assertEquals("ride after backup", File(now, "trips/9.json").readText())
        assertEquals("old trip", File(now, "trips/1.json").readText())
        assertEquals("backup fuel", File(now, "fuel/b1.json").readText())
    }

    @Test
    fun rejectsAnythingThatIsNotABackupWithoutTouchingThePhone() {
        val (files, prefs) = phone("p")
        write(files, "trips/keep.json", "mine")
        val staging = File(files.parentFile, "staging")

        val random = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { it.putNextEntry(ZipEntry("hello.txt")); it.write(1) }
        }.toByteArray()
        assertThrows(FullBackup.InvalidBackup::class.java) { FullBackup.restore(ByteArrayInputStream(random), files, prefs, staging) }
        assertThrows(FullBackup.InvalidBackup::class.java) { FullBackup.restore(ByteArrayInputStream("nope".toByteArray()), files, prefs, staging) }
        assertEquals("mine", File(files, "trips/keep.json").readText())
    }

    @Test
    fun aBadEntryAbortsTheWholeRestore() {
        val (files, prefs) = phone("p")
        val good = backupOf(tmp.newFolder("src").let { File(it, "files").apply { mkdirs(); write(this, "trips/1.json", "t") } }, prefs)
        // Re-pack the good backup with an extra path-traversal entry at the end.
        val evil = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                val src = java.util.zip.ZipInputStream(ByteArrayInputStream(good))
                while (true) {
                    val e = src.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name)); z.write(src.readBytes()); z.closeEntry()
                }
                z.putNextEntry(ZipEntry("files/trips/../../evil.sh")); z.write(1); z.closeEntry()
            }
        }.toByteArray()
        assertThrows(FullBackup.InvalidBackup::class.java) {
            FullBackup.restore(ByteArrayInputStream(evil), files, prefs, File(files.parentFile, "staging"))
        }
        assertFalse(File(files, "trips/1.json").exists())
        assertFalse(File(files.parentFile.parentFile, "evil.sh").exists())
    }

    @Test
    fun allowListOnlyCoversRiderData() {
        assertTrue(FullBackup.isAllowedEntry("files/trips/1792418032360.json"))
        assertTrue(FullBackup.isAllowedEntry("shared_prefs/opencfmoto_bike.xml"))
        assertFalse(FullBackup.isAllowedEntry("files/osmdroid/tiles.db"))
        assertFalse(FullBackup.isAllowedEntry("files/trips/../x"))
        assertFalse(FullBackup.isAllowedEntry("/etc/passwd"))
        assertFalse(FullBackup.isAllowedEntry("shared_prefs/opencfmoto_telemetry.xml"))
        assertFalse(FullBackup.isAllowedEntry("shared_prefs/x.txt"))
        assertFalse(FullBackup.isAllowedEntry("files/trips/sub/1.json"))
    }

    @Test
    fun suggestedNameCarriesTheDate() {
        // The date uses the JVM's zone, so check the shape rather than the exact day.
        assertTrue(Regex("opencfmoto-backup-\\d{4}-\\d{2}-\\d{2}\\.zip").matches(FullBackup.suggestedFileName(1_792_418_032_360L)))
    }
}

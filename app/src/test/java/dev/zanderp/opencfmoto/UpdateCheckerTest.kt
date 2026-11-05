package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.UpdateChecker.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    private val arm64 = Asset("OpenCfMoto.apk", "https://x/OpenCfMoto.apk", null)
    private val armv7 = Asset("OpenCfMoto.armv7.apk", "https://x/OpenCfMoto.armv7.apk", null)
    private val notes = Asset("checksums.txt", "https://x/checksums.txt", null)

    @Test
    fun sixtyFourBitPhoneGetsMainApkWhateverTheOrder() {
        assertEquals(arm64, UpdateChecker.pickApk(listOf(armv7, notes, arm64), is64Bit = true))
        assertEquals(arm64, UpdateChecker.pickApk(listOf(arm64, armv7), is64Bit = true))
    }

    @Test
    fun thirtyTwoBitPhoneGetsArmv7() {
        assertEquals(armv7, UpdateChecker.pickApk(listOf(arm64, armv7), is64Bit = false))
    }

    @Test
    fun fallsBackToAnyApk() {
        assertEquals(arm64, UpdateChecker.pickApk(listOf(arm64), is64Bit = false))
        assertEquals(armv7, UpdateChecker.pickApk(listOf(armv7), is64Bit = true))
        assertNull(UpdateChecker.pickApk(listOf(notes), is64Bit = true))
        assertNull(UpdateChecker.pickApk(emptyList(), is64Bit = true))
    }

    @Test
    fun readsGithubSha256Digest() {
        val hex = "ee3a8d46e3ffac3f56ab6e0af842f4769e48b7747de5a7563e6928648542c2bc"
        assertEquals(hex, UpdateChecker.sha256Of("sha256:$hex"))
        assertEquals(hex, UpdateChecker.sha256Of("SHA256:${hex.uppercase()}"))
        assertNull(UpdateChecker.sha256Of(null))
        assertNull(UpdateChecker.sha256Of(""))
        assertNull(UpdateChecker.sha256Of("sha1:abc"))
        assertNull(UpdateChecker.sha256Of("sha256:nothex"))
    }
}

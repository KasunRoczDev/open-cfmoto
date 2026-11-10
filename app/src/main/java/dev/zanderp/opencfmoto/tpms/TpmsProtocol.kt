// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 KasunRoczDev and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.tpms

/**
 * Decoder for the stick-on / valve-cap Bluetooth TPMS sensors sold with the "HR TPMS" app. The
 * sensors never take a connection: every reading is a BLE advertisement, so the phone only scans.
 *
 * Two advertisement families exist (both handled by the official app):
 *
 * **"TPMS" family** (current sensors, paired by a 6-hex-digit ID printed on the sensor / its QR):
 * ```
 * 05 08 'T' 'P' 'M' 'S'          shortened local name "TPMS"
 * 0C FF m0..m10                  manufacturer data, 11 bytes
 *   m2      battery   = m2 × 0.01 + 1.2 V
 *   m3      temp      = m3 − 50 °C
 *   m4 m5   pressure  = big-endian, kPa absolute ≈ +100 → gauge kPa = raw − 100 (0 when ≤ 100)
 *   m6..m8  pair ID   = "%02X%02X%02X"
 * ```
 *
 * **"KY" family** (older sensors, paired by MAC address only), a fixed 17-byte head:
 * ```
 * 03 03 A5 27                    16-bit service UUID 0x27A5
 * 03 08 'K' 'Y'                  shortened local name "KY"
 * 08 r9 r10                      next AD (length 8)
 * r11       battery   = r11 / 10 V
 * r12       temp      = signed °C
 * r13 r14   pressure  = big-endian psi × 10, absolute → gauge psi = raw / 10 − 14.6 (raw < 148 = flat)
 * r15 r16   check     = CRC-16 (poly 0x8005, init 0xFFFF, no reflection) of r0..r14, each byte of the
 *                       CRC then remapped through the vendor's [CRC_HI] / [CRC_LO] tables
 * ```
 * Pure Kotlin — unit-tested without Android.
 */
object TpmsProtocol {
    enum class Family { TPMS, KY }

    /** One decoded advertisement. [pressureKpa] is gauge pressure (0 = flat / not mounted). */
    data class Reading(
        val family: Family,
        val pressureKpa: Double,
        val tempC: Int,
        val batteryV: Double,
        /** 6 upper-case hex digits for the TPMS family, null for KY (paired by address only). */
        val pairId: String?,
    )

    private val TPMS_SIGNATURE = byteArrayOf(0x05, 0x08, 0x54, 0x50, 0x4D, 0x53, 0x0C, 0xFF.toByte())
    private val KY_HEAD = byteArrayOf(0x03, 0x03, 0xA5.toByte(), 0x27, 0x03, 0x08, 0x4B, 0x59, 0x08)

    const val PSI_PER_KPA = 0.1450377

    /**
     * Decodes a raw scan record ([android.bluetooth.le.ScanRecord.getBytes]). [deviceName] is the
     * name Android resolved for the advertiser, used as a fallback signal like the official app.
     * Returns null for anything that isn't a valid sensor packet.
     */
    fun decode(record: ByteArray?, deviceName: String? = null): Reading? {
        if (record == null || record.isEmpty()) return null
        decodeKy(record)?.let { return it }
        return decodeTpms(record, deviceName)
    }

    private fun decodeTpms(record: ByteArray, deviceName: String?): Reading? {
        val named = localName(record) == "TPMS" || deviceName == "TPMS" || indexOf(record, TPMS_SIGNATURE) >= 0
        if (!named) return null
        val m = manufacturerData(record) ?: return null
        if (m.size < 9) return null
        val volts = (m[2].u() * 0.01) + 1.2
        val temp = m[3].u() - 50
        val raw = m[4].u() * 256 + m[5].u()
        val kpa = if (raw > 100) (raw - 100).toDouble() else 0.0
        val id = String.format(java.util.Locale.US, "%02X%02X%02X", m[6].u(), m[7].u(), m[8].u())
        return Reading(Family.TPMS, kpa, temp, round1(volts), id)
    }

    private fun decodeKy(r: ByteArray): Reading? {
        if (r.size < 17) return null
        for (i in KY_HEAD.indices) if (r[i] != KY_HEAD[i]) return null
        val crc = crc16(r, 0, 15)
        if (r[15] != CRC_HI[(crc shr 8) and 0xFF] || r[16] != CRC_LO[crc and 0xFF]) return null
        var raw = r[13].u() shl 8 or r[14].u()
        if (raw < 148) raw = 146
        val psi = raw / 10.0 - 14.6
        val kpa = if (psi > 0) psi / PSI_PER_KPA else 0.0
        return Reading(Family.KY, round1(kpa), r[12].toInt(), r[11].u() / 10.0, null)
    }

    /** CRC-16 with poly 0x8005, init 0xFFFF, MSB first, no final XOR (CRC-16/CMS). */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val b = data[i].u()
            for (bit in 7 downTo 0) {
                val inBit = (b shr bit) and 1
                val top = (crc shr 15) and 1
                crc = (crc shl 1) and 0xFFFF
                if (inBit xor top == 1) crc = crc xor 0x8005
            }
        }
        return crc
    }

    /** First non-empty local name AD (0x08 shortened / 0x09 complete), like the official app. */
    fun localName(record: ByteArray): String? {
        var i = 0
        while (i < record.size) {
            val len = record[i].u()
            if (len == 0 || i + 1 >= record.size) return null
            val type = record[i + 1].u()
            val start = i + 2
            val n = len - 1
            if (n > 0 && start + n <= record.size && (type == 0x08 || type == 0x09)) {
                val s = String(record, start, n, Charsets.UTF_8)
                if (s.isNotEmpty()) return s
            }
            i += len + 1
        }
        return null
    }

    /** Payload of the first manufacturer-specific AD (0xFF), company ID included. */
    fun manufacturerData(record: ByteArray): ByteArray? {
        var i = 0
        while (i < record.size) {
            val len = record[i].u()
            if (len == 0 || i + 1 >= record.size) return null
            val type = record[i + 1].u()
            val start = i + 2
            val n = len - 1
            if (n > 0 && start + n <= record.size && type == 0xFF) return record.copyOfRange(start, start + n)
            i += len + 1
        }
        return null
    }

    /** Keeps only hex digits, upper-cased; a longer string (a QR payload) keeps its last 6. */
    fun normalizePairId(text: String?): String {
        val hex = text.orEmpty().replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        return if (hex.length > 6) hex.takeLast(6) else hex
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { String.format(java.util.Locale.US, "%02X", it.u()) }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun Byte.u(): Int = toInt() and 0xFF
    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private fun table(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Vendor CRC byte remap tables, copied verbatim from the official app. */
    val CRC_HI: ByteArray = table(
        "3fd9f5d6915579a44bd3bcc5364cb99b98abdd34717b01be350e709c1a8fdc6699a27fc4d4fd237a5d4861a1d2f1031003dcc3915cc8c708e5484123d52d2d0d" +
            "f8eb156f94de57311c1368eb6d6d77d6179f4d60c6d4b7a0d8db14bb99e382271aee5406313a8a9f8c2cb9a132ffd0fa8debac1da81e5ed3af6e30972d1b39d9" +
            "25527e17bd61307a7674232499ecab33a1701b6962cca6609b6fb07cc6733d6d41a13256b003126595c10e9bd44a380037d6ef57f73f76555f4b4adfc4d52cc1" +
            "423a8b5e96b98ff199c3d194ef60fc29d030f3edeb2e653a93fb750aa956be21b1db2a2998dd134f83e076805c879fb627e845e68420ec6275aa034e0af5b634",
    )
    val CRC_LO: ByteArray = table(
        "112c6709967aa1b0a18c25f20d4eda54773547eac18888fa084e411356407cef4d6a4f1fb6adbaf001144d0d96aaf950c3b75cea86aaba5e5b0966004ee1f5ff" +
            "e0b21b527bdd64f3b0ceeeb13390c11f2101e109c9594babc2e92445e94b3b6842a095896f543e6a992ef1aedabe787ad637e32e9cb167283b2057bce9b8fe66" +
            "3368cfac32b1aea49e89be762fa34b5a2c056b7216a00d881d3e4712b1cfc4d95481824257eba0ee197ba47d39a68f736d10e1d617cee7fe05279b299533e672" +
            "fe5aa77b5b0822778de2c4ae30c8d1d140985eb05b1d70f0d982391bf8a8c5b5f5306bc79601175125a89a4698e7815b0dac1311136312da7fbe788024ad1bf0",
    )
}

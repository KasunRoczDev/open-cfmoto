// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Rides logged off the bike's own classic Bluetooth (the calls / music link, not the Wi-Fi
 * projection): which bonded device is the bike, and how long a dropout may last before the ride
 * ends. Pure — unit-tested. The service side is [BikeRideService].
 */
object BikeBtRide {
    /** A dropout shorter than this (dash reboot, interference) keeps the same ride going. */
    const val DISCONNECT_GRACE_MS = 2 * 60_000L

    /**
     * The bike among [bonded] (mac to name): a name equal to one of [bikeNames] — CFMoto dashes use
     * the same "CFMOTO-xxxxxx" for their Bluetooth name and Wi-Fi SSID — else the only bonded device
     * whose name starts with "CFMOTO". Null when there's none, or several and none matches by name.
     */
    fun autoPick(bonded: List<Pair<String, String>>, bikeNames: Collection<String?>): Pair<String, String>? {
        val wanted = bikeNames.mapNotNull { it?.trim()?.lowercase()?.takeIf { n -> n.isNotEmpty() } }.toSet()
        bonded.firstOrNull { it.second.trim().lowercase() in wanted }?.let { return it }
        return bonded.filter { it.second.trim().startsWith(CFMOTO_PREFIX, ignoreCase = true) }.singleOrNull()
    }

    /** Same Bluetooth address, ignoring case; false when either is unknown. */
    fun isBike(mac: String?, bikeMac: String?): Boolean =
        mac != null && bikeMac != null && mac.equals(bikeMac, ignoreCase = true)

    private const val CFMOTO_PREFIX = "CFMOTO"
}

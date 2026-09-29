// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Arrow family drawn in the strip's turn cell. */
enum class TurnKind { STRAIGHT, SLIGHT, NORMAL, SHARP, U_TURN, ROUNDABOUT, MERGE, FORK, RAMP_ON, RAMP_OFF, DEPART, DESTINATION, UNKNOWN }

enum class TurnSide { LEFT, RIGHT, NONE }

/** The next maneuver from Android Auto navigation (phase 2). Distance is raw meters; formatting is ours. */
data class NextTurn(
    val kind: TurnKind,
    val side: TurnSide,
    val exitNumber: Int? = null,
    val distanceM: Int? = null,
    val road: String? = null,
)

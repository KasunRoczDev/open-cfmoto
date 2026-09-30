// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** One road lane: the arrows painted on it and whether Maps says to use it. */
data class Lane(val arrows: List<Pair<TurnKind, TurnSide>>, val highlighted: Boolean)

/**
 * Everything the Directions band can show, as Android Auto last sent it. Parts Maps doesn't send stay
 * null/empty and are simply not drawn. [etaSeconds] is "seconds to arrival" at the time of the message.
 */
data class Directions(
    val turn: NextTurn?,
    val then: NextTurn? = null,
    val lanes: List<Lane> = emptyList(),
    val etaText: String? = null,
    val etaSeconds: Long? = null,
    val remainingM: Int? = null,
) {
    companion object {
        val NONE = Directions(null)
    }
}

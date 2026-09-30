// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType

@Composable
fun TripsTab(onSavedRides: () -> Unit, onTripComputer: () -> Unit) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_tab_trips), style = OcmType.screenTitle, color = c.text)
        OcmCard(onClick = onSavedRides) {
            Text(stringResource(R.string.ui_trips_saved), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_trips_saved_desc), style = OcmType.body, color = c.text2)
        }
        OcmCard(onClick = onTripComputer) {
            Text(stringResource(R.string.ui_trips_computer), style = OcmType.title, color = c.text)
            Text(stringResource(R.string.ui_trips_computer_desc), style = OcmType.body, color = c.text2)
        }
    }
}

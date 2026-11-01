// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.NavCard
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SectionLabel

@Composable
fun TripsTab(onSavedRides: () -> Unit, onTripComputer: () -> Unit, onWeeklyReport: () -> Unit) {
    val c = Ocm.colors
    OcmScreen {
        Text(
            stringResource(R.string.ui_tab_trips), style = OcmType.screenTitle, color = c.text,
            modifier = Modifier.semantics { heading() },
        )
        SectionLabel(stringResource(R.string.ui_trips_section_history))
        NavCard(stringResource(R.string.ui_trips_saved), stringResource(R.string.ui_trips_saved_desc), Icons.Rounded.History, onSavedRides)
        NavCard(stringResource(R.string.ui_trips_report), stringResource(R.string.ui_trips_report_desc), Icons.Rounded.BarChart, onWeeklyReport)
        SectionLabel(stringResource(R.string.ui_trips_section_live))
        NavCard(stringResource(R.string.ui_trips_computer), stringResource(R.string.ui_trips_computer_desc), Icons.Rounded.Speed, onTripComputer)
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SettingRow

@Composable
fun MeTab(
    onGarage: () -> Unit, onSettings: () -> Unit, onLogs: () -> Unit, onReport: () -> Unit,
    onUpdate: () -> Unit, onAbout: () -> Unit, onDonate: () -> Unit,
) {
    val c = Ocm.colors
    OcmScreen {
        Text(stringResource(R.string.ui_tab_me), style = OcmType.screenTitle, color = c.text)
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_garage), stringResource(R.string.ui_me_garage_desc), Icons.Rounded.Garage, onGarage)
            SettingRow(stringResource(R.string.ui_me_settings), stringResource(R.string.ui_me_settings_desc), Icons.Rounded.Settings, onSettings)
        }
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_logs), stringResource(R.string.ui_me_logs_desc), Icons.AutoMirrored.Rounded.Article, onLogs)
            SettingRow(stringResource(R.string.ui_me_report), icon = Icons.Rounded.BugReport, onClick = onReport)
            SettingRow(stringResource(R.string.ui_me_update), icon = Icons.Rounded.SystemUpdate, onClick = onUpdate)
        }
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_about), icon = Icons.Rounded.Info, onClick = onAbout)
            SettingRow(stringResource(R.string.ui_me_donate), icon = Icons.Rounded.Favorite, onClick = onDonate)
        }
        Text(stringResource(R.string.ui_version, BuildConfig.VERSION_NAME), style = OcmType.body, color = c.text2)
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.tabs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.zanderp.opencfmoto.BuildConfig
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.maintenance.MaintenanceSummary
import dev.zanderp.opencfmoto.ui.design.Ocm
import dev.zanderp.opencfmoto.ui.design.OcmCard
import dev.zanderp.opencfmoto.ui.design.OcmScreen
import dev.zanderp.opencfmoto.ui.design.OcmType
import dev.zanderp.opencfmoto.ui.design.SectionLabel
import dev.zanderp.opencfmoto.ui.design.SettingRow

@Composable
fun MeTab(
    onGarage: () -> Unit, onSettings: () -> Unit, onForest: () -> Unit, onMaintenance: () -> Unit,
    maintenance: MaintenanceSummary,
    onFuel: () -> Unit, fuelRangeKm: Double?,
    onBackup: () -> Unit, lastBackupText: String?,
    onLogs: () -> Unit, onUpdate: () -> Unit, onAbout: () -> Unit,
) {
    val c = Ocm.colors
    val maintenanceSubtitle = when {
        !maintenance.hasSchedule -> stringResource(R.string.maintenance_subtitle_no_schedule)
        !maintenance.hasBaseline -> stringResource(R.string.maintenance_subtitle_setup)
        maintenance.dueCount > 0 -> stringResource(R.string.maintenance_subtitle_due, maintenance.dueCount)
        else -> stringResource(R.string.maintenance_subtitle_ok)
    }
    // Grouped by what the rider is looking for: the bike, riding, their data, the app, then help.
    OcmScreen {
        Text(
            stringResource(R.string.ui_tab_me), style = OcmType.screenTitle, color = c.text,
            modifier = Modifier.semantics { heading() },
        )
        SectionLabel(stringResource(R.string.ui_me_section_bike))
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_garage), stringResource(R.string.ui_me_garage_desc), Icons.Rounded.Garage, onGarage)
            SettingRow(
                stringResource(R.string.ui_me_maintenance), maintenanceSubtitle, Icons.Rounded.Build, onMaintenance,
                badge = maintenance.dueCount.takeIf { it > 0 }?.toString(),
            )
            SettingRow(
                stringResource(R.string.ui_me_fuel),
                fuelRangeKm?.let { stringResource(R.string.fuel_subtitle_range, it.toInt()) } ?: stringResource(R.string.fuel_subtitle_setup),
                Icons.Rounded.LocalGasStation, onFuel,
            )
        }
        SectionLabel(stringResource(R.string.ui_me_section_riding))
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_forest), stringResource(R.string.ui_me_forest_desc), Icons.Rounded.Park, onForest)
        }
        SectionLabel(stringResource(R.string.ui_me_section_data))
        OcmCard {
            SettingRow(
                stringResource(R.string.ui_me_backup),
                lastBackupText?.let { stringResource(R.string.ui_me_backup_last, it) } ?: stringResource(R.string.ui_me_backup_never),
                Icons.Rounded.Backup, onBackup,
            )
        }
        SectionLabel(stringResource(R.string.ui_me_section_app))
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_settings), stringResource(R.string.ui_me_settings_desc), Icons.Rounded.Settings, onSettings)
            SettingRow(stringResource(R.string.ui_me_update), icon = Icons.Rounded.SystemUpdate, onClick = onUpdate)
        }
        SectionLabel(stringResource(R.string.ui_me_section_support))
        OcmCard {
            SettingRow(stringResource(R.string.ui_me_logs), stringResource(R.string.ui_me_logs_desc), Icons.AutoMirrored.Rounded.Article, onLogs)
            SettingRow(stringResource(R.string.ui_me_about), icon = Icons.Rounded.Info, onClick = onAbout)
        }
        Text(stringResource(R.string.ui_version, BuildConfig.VERSION_NAME), style = OcmType.caption, color = c.text2)
        Text(stringResource(R.string.ui_community_note), style = OcmType.caption, color = c.text2)
    }
}

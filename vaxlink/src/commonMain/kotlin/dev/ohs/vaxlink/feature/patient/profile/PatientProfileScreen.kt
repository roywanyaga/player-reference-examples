/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.vaxlink.feature.patient.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.patient_profile_household
import dev.ohs.vaxlink.generated.resources.profile_due_next
import dev.ohs.vaxlink.generated.resources.profile_link_past
import dev.ohs.vaxlink.generated.resources.profile_link_past_detail
import dev.ohs.vaxlink.generated.resources.profile_link_schedule
import dev.ohs.vaxlink.generated.resources.profile_link_schedule_detail
import dev.ohs.vaxlink.generated.resources.profile_link_schedule_none
import dev.ohs.vaxlink.generated.resources.profile_link_summary
import dev.ohs.vaxlink.generated.resources.profile_link_summary_detail
import dev.ohs.vaxlink.generated.resources.profile_nothing_due
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The patient's overview: the header and actions, the family, what has been given, what is due next
 * and any open referral. The schedule, the past activity and the full summary open from here
 * through [onOpenSchedule], [onOpenPast] and [onOpenSummary]; a view with no callback shows no
 * link.
 */
@Composable
fun PatientProfileScreen(
  patientId: String,
  onBack: () -> Unit,
  onHouseholdClick: ((String) -> Unit)? = null,
  onOpenSchedule: (() -> Unit)? = null,
  onOpenPast: (() -> Unit)? = null,
  onOpenSummary: (() -> Unit)? = null,
  today: LocalDate = rememberToday(),
  actions: @Composable (ProfileUiState) -> Unit = {},
) {
  val registry = LocalViewRegistry.current
  val headerRenderer =
    remember(registry) { registry.componentRenderer<PatientSummaryState>(ViewTypeCS.PatientHeader) }

  ProfileFrame(patientId, onBack) { s ->
    item(key = "patient_header") { headerRenderer.Render(s.patient!!, RenderOptions()) }
    s.household?.let { household ->
      item(key = "household") { HouseholdRow(household, onHouseholdClick) }
    }
    item(key = "actions") { actions(s) }
    careSection(s.relationships, "family", always = true)
    careSection(s.immunizations, "immunizations", always = true)
    item(key = "due_next") { DueNextSection(s, today) }
    careSection(s.referrals, "referrals", always = false)
    if (onOpenSchedule != null) {
      item(key = "link_schedule") {
        val overdue = s.overdue(today).size
        val ahead = s.openTasks.size - overdue
        ProfileLink(
          title = stringResource(Res.string.profile_link_schedule),
          detail =
            if (s.openTasks.isEmpty()) stringResource(Res.string.profile_link_schedule_none)
            else stringResource(Res.string.profile_link_schedule_detail, overdue, ahead),
          onClick = onOpenSchedule,
        )
      }
    }
    if (onOpenPast != null) {
      item(key = "link_past") {
        ProfileLink(
          title = stringResource(Res.string.profile_link_past),
          detail = stringResource(Res.string.profile_link_past_detail, s.past().size),
          onClick = onOpenPast,
        )
      }
    }
    if (onOpenSummary != null) {
      item(key = "link_summary") {
        ProfileLink(
          title = stringResource(Res.string.profile_link_summary),
          detail = stringResource(Res.string.profile_link_summary_detail),
          onClick = onOpenSummary,
        )
      }
    }
  }
}

/** Overdue doses and visits, then the earliest contact still ahead in each plan. */
@Composable
private fun DueNextSection(state: ProfileUiState, today: LocalDate) {
  val registry = LocalViewRegistry.current
  val title = stringResource(Res.string.profile_due_next)
  val nothingDue = stringResource(Res.string.profile_nothing_due)
  val section =
    remember(title, nothingDue) {
      SectionCardLayoutRenderer<PatientCareTaskState>(
        title,
        PLAN_SECTION_ICON,
        config = SectionCardConfig(collapsible = true),
        emptyLabel = nothingDue,
      )
    }
  val row =
    remember(registry) { registry.componentRenderer<PatientCareTaskState>(ViewTypeCS.CareItem) }
  section.Render(items = state.dueNext(today), component = row, onItemClick = {})
}

/** Where the patient lives; tapping opens the household when the screen allows it. */
@Composable
private fun HouseholdRow(household: HouseholdListState, onClick: ((String) -> Unit)?) {
  val id = household.householdId
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .then(if (onClick != null && id != null) Modifier.clickable { onClick(id) } else Modifier)
        .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.Home,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text =
        stringResource(
          Res.string.patient_profile_household,
          listOfNotNull(household.householdName, household.village).joinToString(", "),
        ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

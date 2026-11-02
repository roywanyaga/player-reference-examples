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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.CareItemConfig
import dev.ohs.player.generated.state.PatientFacilityVisitState
import dev.ohs.player.generated.state.PatientFollowUpState
import dev.ohs.player.generated.state.PatientReferralOutcomeState
import dev.ohs.player.generated.state.PatientReferralState
import dev.ohs.player.generated.state.PatientTreatmentState
import dev.ohs.player.generated.state.PatientVitalState
import dev.ohs.vaxlink.feature.component.common.StatusChipData
import dev.ohs.vaxlink.feature.component.common.StatusRow
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.care_arrived_on
import dev.ohs.vaxlink.generated.resources.care_due_on
import dev.ohs.vaxlink.generated.resources.care_given_on
import dev.ohs.vaxlink.generated.resources.care_opd_visit
import dev.ohs.vaxlink.generated.resources.care_referral_title
import dev.ohs.vaxlink.generated.resources.care_referred_on
import dev.ohs.vaxlink.generated.resources.care_seen_on
import dev.ohs.vaxlink.generated.resources.care_status_done
import dev.ohs.vaxlink.generated.resources.care_status_seen
import dev.ohs.vaxlink.generated.resources.care_status_waiting
import dev.ohs.vaxlink.generated.resources.work_due
import dev.ohs.vaxlink.generated.resources.work_seen_at_facility
import dev.ohs.vaxlink.generated.resources.work_waiting_at_facility
import dev.ohs.vaxlink.generated.resources.work_withdrawn
import dev.ohs.vaxlink.util.calendarDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

class ReferralItemRenderer : ComponentRenderer<PatientReferralState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientReferralState, config: CareItemConfig, options: RenderOptions) {
    StatusRow(
      title = stringResource(Res.string.care_referral_title),
      modifier = options.modifier,
      subtitle =
        listOfNotNull(
            dated(config, Res.string.care_referred_on, item.referredOn.calendarDate()),
            item.referralPriority?.replaceFirstChar { it.uppercaseChar() },
            item.referralNote,
          )
          .joinToString(" · "),
      status =
        when (item.referralState) {
          "active" -> pending(stringResource(Res.string.work_waiting_at_facility))
          "completed" -> done(stringResource(Res.string.work_seen_at_facility))
          "revoked" -> ended(stringResource(Res.string.work_withdrawn))
          else -> null
        },
    )
  }
}

class ReferralOutcomeItemRenderer : ComponentRenderer<PatientReferralOutcomeState, CareItemConfig> {
  @Composable
  override fun Render(
    item: PatientReferralOutcomeState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    StatusRow(
      title = item.outcomeText.orEmpty(),
      modifier = options.modifier,
      subtitle = dated(config, Res.string.care_seen_on, item.outcomeOn.calendarDate()),
    )
  }
}

class TreatmentItemRenderer : ComponentRenderer<PatientTreatmentState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientTreatmentState, config: CareItemConfig, options: RenderOptions) {
    StatusRow(
      title = item.medicine.orEmpty(),
      modifier = options.modifier,
      subtitle = dated(config, Res.string.care_given_on, item.prescribedOn.calendarDate()),
    )
  }
}

class FollowUpItemRenderer : ComponentRenderer<PatientFollowUpState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientFollowUpState, config: CareItemConfig, options: RenderOptions) {
    val due = item.dueOn.calendarDate()
    StatusRow(
      title = item.followUpReason.orEmpty(),
      modifier = options.modifier,
      subtitle = dated(config, Res.string.care_due_on, due),
      status =
        when (item.followUpState) {
          "requested" -> pending(stringResource(Res.string.work_due, due.orEmpty()))
          "completed" -> done(stringResource(Res.string.care_status_done))
          else -> null
        },
    )
  }
}

class FacilityVisitItemRenderer : ComponentRenderer<PatientFacilityVisitState, CareItemConfig> {
  @Composable
  override fun Render(
    item: PatientFacilityVisitState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    StatusRow(
      title = item.visitReason ?: stringResource(Res.string.care_opd_visit),
      modifier = options.modifier,
      subtitle = dated(config, Res.string.care_arrived_on, item.arrivedOn.calendarDate()),
      status =
        when (item.visitState) {
          "arrived" -> pending(stringResource(Res.string.care_status_waiting))
          "finished" -> done(stringResource(Res.string.care_status_seen))
          else -> null
        },
    )
  }
}

class VitalItemRenderer : ComponentRenderer<PatientVitalState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientVitalState, config: CareItemConfig, options: RenderOptions) {
    StatusRow(
      title = listOfNotNull(item.vitalName, item.vitalValue, item.vitalUnit).joinToString(" "),
      modifier = options.modifier,
      subtitle = if (config.showDate != false) item.measuredOn.calendarDate() else null,
    )
  }
}

@Composable
private fun dated(config: CareItemConfig, label: StringResource, date: String?): String? =
  date?.takeIf { config.showDate != false }?.let { stringResource(label, it) }

@Composable
private fun pending(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.tertiaryContainer,
    MaterialTheme.colorScheme.onTertiaryContainer,
  )

@Composable
private fun done(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.onPrimaryContainer,
  )

@Composable
private fun ended(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.surfaceVariant,
    MaterialTheme.colorScheme.onSurfaceVariant,
  )

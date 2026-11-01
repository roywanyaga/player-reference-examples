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
import dev.ohs.player.generated.state.PatientAllergyState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientConditionState
import dev.ohs.player.generated.state.PatientImmunizationState
import dev.ohs.player.generated.state.PatientMedicationStatementState
import dev.ohs.player.generated.state.PatientRelationshipState
import dev.ohs.vaxlink.feature.component.common.StatusChipData
import dev.ohs.vaxlink.feature.component.common.StatusRow
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.care_given_on
import dev.ohs.vaxlink.generated.resources.care_status_done
import dev.ohs.vaxlink.generated.resources.immunization_foreign_dose
import dev.ohs.vaxlink.generated.resources.immunization_lao_dose
import dev.ohs.vaxlink.generated.resources.immunization_lot
import dev.ohs.vaxlink.generated.resources.immunization_not_verified
import dev.ohs.vaxlink.generated.resources.immunization_verified
import dev.ohs.vaxlink.generated.resources.name_unknown
import dev.ohs.vaxlink.generated.resources.record_recorded_on
import dev.ohs.vaxlink.generated.resources.task_due_window
import dev.ohs.vaxlink.generated.resources.task_overdue
import dev.ohs.vaxlink.generated.resources.work_due
import dev.ohs.vaxlink.util.calendarDate
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource

/**
 * An immunisation as recorded: the vaccine as the card named it, when and which lot, whether the
 * clinic has verified it, and the Lao schedule dose it counts for, or a note that it is a foreign
 * dose outside the schedule.
 */
class ImmunizationItemRenderer : ComponentRenderer<PatientImmunizationState, CareItemConfig> {
  @Composable
  override fun Render(
    item: PatientImmunizationState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    val given =
      item.givenOn
        .calendarDate()
        ?.takeIf { config.showDate != false }
        ?.let { stringResource(Res.string.care_given_on, it) }
    val lot = item.lotNumber?.let { stringResource(Res.string.immunization_lot, it) }
    val dose =
      item.laoDoseCode?.let {
        stringResource(Res.string.immunization_lao_dose, item.laoDoseDisplay ?: it)
      } ?: stringResource(Res.string.immunization_foreign_dose)
    StatusRow(
      title = item.vaccineName ?: item.preQualCode ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle = listOfNotNull(given, lot, dose).joinToString(" · "),
      status =
        if (item.verified == true) done(stringResource(Res.string.immunization_verified))
        else pending(stringResource(Res.string.immunization_not_verified)),
    )
  }
}

class ConditionItemRenderer : ComponentRenderer<PatientConditionState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientConditionState, config: CareItemConfig, options: RenderOptions) {
    StatusRow(
      title = item.conditionName ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle =
        listOfNotNull(
            item.recordedOn
              .calendarDate()
              ?.takeIf { config.showDate != false }
              ?.let { stringResource(Res.string.record_recorded_on, it) },
            item.clinicalStatus?.replaceFirstChar { it.uppercaseChar() },
          )
          .joinToString(" · ")
          .ifBlank { null },
    )
  }
}

class AllergyItemRenderer : ComponentRenderer<PatientAllergyState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientAllergyState, config: CareItemConfig, options: RenderOptions) {
    StatusRow(
      title = item.allergyName ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle =
        listOfNotNull(
            item.allergyRecordedOn
              .calendarDate()
              ?.takeIf { config.showDate != false }
              ?.let { stringResource(Res.string.record_recorded_on, it) },
            item.allergyCriticality?.replaceFirstChar { it.uppercaseChar() },
          )
          .joinToString(" · ")
          .ifBlank { null },
      accentColor = if (item.allergyCriticality == "high") MaterialTheme.colorScheme.error else null,
    )
  }
}

class MedicationStatementItemRenderer :
  ComponentRenderer<PatientMedicationStatementState, CareItemConfig> {
  @Composable
  override fun Render(
    item: PatientMedicationStatementState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    StatusRow(
      title = item.statementMedicine ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle =
        listOfNotNull(
            item.statementOn
              .calendarDate()
              ?.takeIf { config.showDate != false }
              ?.let { stringResource(Res.string.record_recorded_on, it) },
            item.statementStatus?.replaceFirstChar { it.uppercaseChar() },
          )
          .joinToString(" · ")
          .ifBlank { null },
    )
  }
}

/** A scheduled dose: its window, and whether it is done, due or overdue. */
class CareTaskItemRenderer(
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
) : ComponentRenderer<PatientCareTaskState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientCareTaskState, config: CareItemConfig, options: RenderOptions) {
    val from = item.dueFrom.calendarDate()
    val by = item.dueBy.calendarDate()
    StatusRow(
      title = item.taskTitle ?: item.taskDoseCode ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle =
        when {
          config.showDate == false -> null
          from != null && by != null -> stringResource(Res.string.task_due_window, from, by)
          by != null -> stringResource(Res.string.work_due, by)
          else -> null
        },
      status =
        when {
          item.taskStatus == "completed" -> done(stringResource(Res.string.care_status_done))
          item.taskStatus == "requested" && item.isOverdue(today()) ->
            overdue(stringResource(Res.string.task_overdue))
          item.taskStatus == "requested" -> pending(stringResource(Res.string.work_due, by ?: ""))
          else -> null
        },
    )
  }
}

/** True once the due window has closed without the dose being given. */
fun PatientCareTaskState.isOverdue(today: LocalDate): Boolean =
  dueBy.calendarDate()?.let { LocalDate.parse(it) < today } == true

/** A parent or the head of household, as the child's record names them. */
class RelationshipItemRenderer : ComponentRenderer<PatientRelationshipState, CareItemConfig> {
  @Composable
  override fun Render(
    item: PatientRelationshipState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    StatusRow(
      title = item.relatedName ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle = item.relationshipLabel ?: item.relationshipCode,
    )
  }
}

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
private fun overdue(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.errorContainer,
    MaterialTheme.colorScheme.onErrorContainer,
  )

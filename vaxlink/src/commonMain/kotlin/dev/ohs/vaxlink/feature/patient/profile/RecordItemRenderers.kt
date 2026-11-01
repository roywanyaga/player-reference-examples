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
import androidx.compose.ui.Modifier
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.CareItemConfig
import dev.ohs.player.generated.state.HouseholdCareTaskState
import dev.ohs.player.generated.state.PatientAllergyState
import dev.ohs.player.generated.state.PatientCarePlanState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientConditionState
import dev.ohs.player.generated.state.PatientImmunizationState
import dev.ohs.player.generated.state.PatientMedicationStatementState
import dev.ohs.player.generated.state.PatientRelationshipState
import dev.ohs.vaxlink.feature.component.common.StatusChipData
import dev.ohs.vaxlink.feature.component.common.StatusRow
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.care_given_on
import dev.ohs.vaxlink.generated.resources.care_plan_active
import dev.ohs.vaxlink.generated.resources.care_plan_period
import dev.ohs.vaxlink.generated.resources.care_plan_since
import dev.ohs.vaxlink.generated.resources.care_status_done
import dev.ohs.vaxlink.generated.resources.immunization_foreign_dose
import dev.ohs.vaxlink.generated.resources.immunization_lao_dose
import dev.ohs.vaxlink.generated.resources.immunization_lot
import dev.ohs.vaxlink.generated.resources.immunization_not_verified
import dev.ohs.vaxlink.generated.resources.immunization_verified
import dev.ohs.vaxlink.generated.resources.name_unknown
import dev.ohs.vaxlink.generated.resources.record_recorded_on
import dev.ohs.vaxlink.generated.resources.task_due_window
import dev.ohs.vaxlink.generated.resources.task_missed
import dev.ohs.vaxlink.generated.resources.task_overdue
import dev.ohs.vaxlink.generated.resources.task_plan_ended
import dev.ohs.vaxlink.generated.resources.work_due
import dev.ohs.vaxlink.util.calendarDate
import dev.ohs.vaxlink.workflow.PlanScheduler
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
    TaskRow(
      title = item.taskTitle ?: item.taskDoseCode,
      status = item.taskStatus,
      statusReason = item.taskStatusReason,
      dueFrom = item.dueFrom.calendarDate(),
      dueBy = item.dueBy.calendarDate(),
      showDate = config.showDate != false,
      today = today(),
      modifier = options.modifier,
    )
  }
}

/** A household-level task, such as the WASH check, rendered like a patient's. */
class HouseholdTaskItemRenderer(
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
) : ComponentRenderer<HouseholdCareTaskState, CareItemConfig> {
  @Composable
  override fun Render(
    item: HouseholdCareTaskState,
    config: CareItemConfig,
    options: RenderOptions,
  ) {
    TaskRow(
      title = item.taskTitle ?: item.taskDoseCode,
      status = item.taskStatus,
      statusReason = item.taskStatusReason,
      dueFrom = item.dueFrom.calendarDate(),
      dueBy = item.dueBy.calendarDate(),
      showDate = config.showDate != false,
      today = today(),
      modifier = options.modifier,
    )
  }
}

/**
 * One schedule task: done, due, overdue, or cancelled with the reason the scheduler gave (missed
 * while the plan ran, or the plan ended before it came due).
 */
@Composable
private fun TaskRow(
  title: String?,
  status: String?,
  statusReason: String?,
  dueFrom: String?,
  dueBy: String?,
  showDate: Boolean,
  today: LocalDate,
  modifier: Modifier,
) {
  val overdue = dueBy?.let { LocalDate.parse(it) < today } == true
  StatusRow(
    title = title ?: stringResource(Res.string.name_unknown),
    modifier = modifier,
    subtitle =
      when {
        !showDate -> null
        dueFrom != null && dueBy != null ->
          stringResource(Res.string.task_due_window, dueFrom, dueBy)
        dueBy != null -> stringResource(Res.string.work_due, dueBy)
        else -> null
      },
    status =
      when {
        status == "completed" -> done(stringResource(Res.string.care_status_done))
        status == "requested" && overdue -> overdue(stringResource(Res.string.task_overdue))
        status == "requested" -> pending(stringResource(Res.string.work_due, dueBy ?: ""))
        status == "cancelled" && statusReason == PlanScheduler.TASK_MISSED ->
          overdue(stringResource(Res.string.task_missed))
        status == "cancelled" -> ended(stringResource(Res.string.task_plan_ended))
        else -> null
      },
  )
}

/** A programme the patient is enrolled in: ANC, PNC or family planning, with its dates. */
class CarePlanItemRenderer : ComponentRenderer<PatientCarePlanState, CareItemConfig> {
  @Composable
  override fun Render(item: PatientCarePlanState, config: CareItemConfig, options: RenderOptions) {
    val from = item.startedOn.calendarDate()
    val to = item.endedOn.calendarDate()
    StatusRow(
      title =
        item.carePlanTitle
          ?: item.carePlanPlan?.substringAfterLast('/')
          ?: stringResource(Res.string.name_unknown),
      modifier = options.modifier,
      subtitle =
        when {
          from != null && to != null -> stringResource(Res.string.care_plan_period, from, to)
          from != null -> stringResource(Res.string.care_plan_since, from)
          else -> null
        },
      status =
        when (item.carePlanStatus) {
          "active" -> pending(stringResource(Res.string.care_plan_active))
          "completed" -> done(stringResource(Res.string.care_status_done))
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
private fun ended(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.surfaceVariant,
    MaterialTheme.colorScheme.onSurfaceVariant,
  )

@Composable
private fun overdue(label: String) =
  StatusChipData(
    label,
    MaterialTheme.colorScheme.errorContainer,
    MaterialTheme.colorScheme.onErrorContainer,
  )

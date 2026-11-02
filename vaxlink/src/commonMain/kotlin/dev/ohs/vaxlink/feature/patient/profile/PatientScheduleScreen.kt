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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.profile_link_schedule_none
import dev.ohs.vaxlink.generated.resources.profile_none_recorded
import dev.ohs.vaxlink.generated.resources.schedule_contact_birth
import dev.ohs.vaxlink.generated.resources.schedule_contact_months
import dev.ohs.vaxlink.generated.resources.schedule_contact_weeks
import dev.ohs.vaxlink.generated.resources.schedule_done_count
import dev.ohs.vaxlink.generated.resources.schedule_given_count
import dev.ohs.vaxlink.generated.resources.schedule_title
import dev.ohs.vaxlink.generated.resources.task_plan_anc
import dev.ohs.vaxlink.generated.resources.task_plan_fp
import dev.ohs.vaxlink.generated.resources.task_plan_lao_schedule
import dev.ohs.vaxlink.generated.resources.task_plan_pnc
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.Contact
import dev.ohs.vaxlink.workflow.ContactUnit
import dev.ohs.vaxlink.workflow.FP_INJECTABLE_PLAN
import dev.ohs.vaxlink.workflow.FP_LONGTERM_PLAN
import dev.ohs.vaxlink.workflow.FP_ORAL_PLAN
import dev.ohs.vaxlink.workflow.LAO_SCHEDULE_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import dev.ohs.vaxlink.workflow.ScheduleCatalogue
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * Everything scheduled for the patient, plan by plan. The Lao immunisation schedule is grouped by
 * schedule contact (birth, 6 weeks, 10 weeks and so on) as the Lao card lays it out: the next
 * contact opens expanded, finished contacts collapse to a count. Other plans list their visits.
 */
@Composable
fun PatientScheduleScreen(
  patientId: String,
  onBack: () -> Unit,
  today: LocalDate = rememberToday(),
) {
  ProfileFrame(patientId, onBack) { s ->
    item(key = "heading") { ViewHeading(stringResource(Res.string.schedule_title)) }
    if (s.careTasks.isEmpty()) {
      item(key = "empty") {
        Text(
          stringResource(Res.string.profile_link_schedule_none),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    val overdue = s.overdue(today).toSet()
    val byPlan = s.careTasks.groupBy { s.planOf(it) }
    val plans = byPlan.keys.sortedWith(compareBy({ it != LAO_SCHEDULE_PLAN }, { it }))
    plans.forEach { plan ->
      val tasks = byPlan.getValue(plan)
      if (plan == LAO_SCHEDULE_PLAN) laoContacts(s, tasks, overdue) else planSection(s, plan, tasks)
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.laoContacts(
  state: ProfileUiState,
  tasks: List<PatientCareTaskState>,
  overdue: Set<PatientCareTaskState>,
) {
  item(key = "plan:lao") {
    Text(
      stringResource(Res.string.task_plan_lao_schedule),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
  }
  val byContact = tasks.groupBy { state.offsetOf(it) }.toSortedMap()
  val next = byContact.entries.firstOrNull { (_, group) -> group.any { it.isOpen } }?.key
  byContact.forEach { (days, group) ->
    item(key = "contact:$days") {
      val registry = LocalViewRegistry.current
      val title = contactLabel(ScheduleCatalogue.contactOf(days))
      val given = group.count { it.taskStatus == "completed" }
      val count = stringResource(Res.string.schedule_given_count, given, group.size)
      val none = stringResource(Res.string.profile_none_recorded)
      val open = days == next || group.any { it in overdue }
      val section =
        remember(title, count, open) {
          SectionCardLayoutRenderer<PatientCareTaskState>(
            title,
            PLAN_SECTION_ICON,
            config = SectionCardConfig(collapsible = true),
            emptyLabel = none,
            initiallyExpanded = open,
            countLabel = count,
          )
        }
      val row =
        remember(registry) { registry.componentRenderer<PatientCareTaskState>(ViewTypeCS.CareItem) }
      section.Render(items = group, component = row, onItemClick = {})
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.planSection(
  state: ProfileUiState,
  plan: String,
  tasks: List<PatientCareTaskState>,
) {
  item(key = "plan:$plan") {
    val registry = LocalViewRegistry.current
    val title = planTitle(plan, state)
    val none = stringResource(Res.string.profile_none_recorded)
    val done = tasks.count { it.taskStatus == "completed" }
    val count = stringResource(Res.string.schedule_done_count, done, tasks.size)
    val section =
      remember(title, count) {
        SectionCardLayoutRenderer<PatientCareTaskState>(
          title,
          PLAN_SECTION_ICON,
          config = SectionCardConfig(collapsible = true),
          emptyLabel = none,
          countLabel = count,
        )
      }
    val row =
      remember(registry) { registry.componentRenderer<PatientCareTaskState>(ViewTypeCS.CareItem) }
    section.Render(items = tasks, component = row, onItemClick = {})
  }
}

@Composable
private fun planTitle(plan: String, state: ProfileUiState): String =
  when (plan) {
    LAO_SCHEDULE_PLAN -> stringResource(Res.string.task_plan_lao_schedule)
    ANC_PLAN -> stringResource(Res.string.task_plan_anc)
    PNC_PLAN -> stringResource(Res.string.task_plan_pnc)
    FP_INJECTABLE_PLAN,
    FP_ORAL_PLAN,
    FP_LONGTERM_PLAN -> stringResource(Res.string.task_plan_fp)
    else ->
      state.placements.values.firstOrNull { it.planUrl == plan }?.planTitle
        ?: plan.substringAfterLast('/').ifBlank { plan }
  }

/** A schedule contact as the Lao card names it. */
@Composable
internal fun contactLabel(contact: Contact): String =
  when (contact.unit) {
    ContactUnit.Birth -> stringResource(Res.string.schedule_contact_birth)
    ContactUnit.Weeks -> stringResource(Res.string.schedule_contact_weeks, contact.count)
    ContactUnit.Months -> stringResource(Res.string.schedule_contact_months, contact.count)
  }

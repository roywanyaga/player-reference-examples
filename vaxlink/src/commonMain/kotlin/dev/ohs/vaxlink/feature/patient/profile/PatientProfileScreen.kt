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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.registry.layoutRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.patient_profile_back
import dev.ohs.vaxlink.generated.resources.patient_profile_default_name
import dev.ohs.vaxlink.generated.resources.patient_profile_household
import dev.ohs.vaxlink.generated.resources.patient_profile_not_found
import dev.ohs.vaxlink.generated.resources.profile_none_recorded
import dev.ohs.vaxlink.generated.resources.task_plan_anc
import dev.ohs.vaxlink.generated.resources.task_plan_fp
import dev.ohs.vaxlink.generated.resources.task_plan_lao_schedule
import dev.ohs.vaxlink.generated.resources.task_plan_pnc
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.FP_INJECTABLE_PLAN
import dev.ohs.vaxlink.workflow.FP_LONGTERM_PLAN
import dev.ohs.vaxlink.workflow.FP_ORAL_PLAN
import dev.ohs.vaxlink.workflow.LAO_SCHEDULE_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Test tag of the profile's scrolling list, so tests can scroll a section into view. */
const val PROFILE_LIST_TAG = "patient_profile_list"

/**
 * The summary profile: header, actions, the household the patient lives in, and one section per
 * kind of record, each extracted by its own ViewJoinMap. Schedule tasks are grouped by the plan
 * that created them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientProfileScreen(
  patientId: String,
  onBack: () -> Unit,
  onHouseholdClick: ((String) -> Unit)? = null,
  actions: @Composable (ProfileUiState) -> Unit = {},
) {
  val viewModel =
    koinViewModel<PatientProfileViewModel>(key = patientId) { parametersOf(patientId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val registry = LocalViewRegistry.current

  val headerRenderer =
    remember(registry) { registry.componentRenderer<PatientSummaryState>(ViewTypeCS.PatientHeader) }
  val patient = state?.patient
  val defaultPatientName = stringResource(Res.string.patient_profile_default_name)
  val patientName =
    remember(patient, defaultPatientName) {
      listOfNotNull(patient?.givenName, patient?.familyName).joinToString(" ").ifBlank {
        defaultPatientName
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(patientName) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(Res.string.patient_profile_back),
              tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
          ),
      )
    }
  ) { padding ->
    val s = state
    if (s == null) {
      Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    if (s.patient == null) {
      Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.patient_profile_not_found))
      }
      return@Scaffold
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).testTag(PROFILE_LIST_TAG),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "patient_header") { headerRenderer.Render(s.patient, RenderOptions()) }
      s.household?.let { household ->
        item(key = "household") { HouseholdRow(household, onHouseholdClick) }
      }
      item(key = "actions") { actions(s) }
      careSection(s.relationships, "family", always = true)
      careSection(s.immunizations, "immunizations", always = true)
      careSection(s.conditions, "conditions", always = true)
      careSection(s.vitals, "observations", always = true)
      careSection(s.treatments, "medications", always = true)
      careSection(s.medicationStatements, "medicationStatements", always = false)
      careSection(s.allergies, "allergies", always = true)
      careSection(s.visits, "encounters", always = true)
      careSection(s.carePlans, "programmes", always = false)
      taskSections(s.careTasks)
      careSection(s.referrals, "referrals", always = true)
      careSection(s.outcomes, "outcomes", always = false)
      careSection(s.followUps, "followUps", always = false)
    }
  }
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

/** A section through the registered renderers; [always] keeps it on screen when empty. */
private inline fun <reified T : Any> LazyListScope.careSection(
  items: List<T>,
  key: String,
  always: Boolean,
) {
  if (items.isEmpty() && !always) return
  item(key = key) {
    val registry = LocalViewRegistry.current
    val section = remember(registry) { registry.layoutRenderer<T>(ViewTypeCS.SectionCard) }
    val row = remember(registry) { registry.componentRenderer<T>(ViewTypeCS.CareItem) }
    section.Render(items = items, component = row, onItemClick = {})
  }
}

/** One section per plan, the Lao schedule first and always present. */
private fun LazyListScope.taskSections(tasks: List<PatientCareTaskState>) {
  val byPlan = tasks.groupBy { it.taskPlan ?: "" }
  val plans = listOf(LAO_SCHEDULE_PLAN) + (byPlan.keys - LAO_SCHEDULE_PLAN).sorted()
  plans.forEach { plan ->
    item(key = "tasks:$plan") {
      val registry = LocalViewRegistry.current
      val title =
        when (plan) {
          LAO_SCHEDULE_PLAN -> stringResource(Res.string.task_plan_lao_schedule)
          ANC_PLAN -> stringResource(Res.string.task_plan_anc)
          PNC_PLAN -> stringResource(Res.string.task_plan_pnc)
          FP_INJECTABLE_PLAN,
          FP_ORAL_PLAN,
          FP_LONGTERM_PLAN -> stringResource(Res.string.task_plan_fp)
          else -> plan.substringAfterLast('/').ifBlank { plan }
        }
      val noneRecorded = stringResource(Res.string.profile_none_recorded)
      val section =
        remember(title, noneRecorded) {
          SectionCardLayoutRenderer<PatientCareTaskState>(
            title,
            PLAN_SECTION_ICON,
            config = SectionCardConfig(collapsible = true),
            emptyLabel = noneRecorded,
          )
        }
      val row =
        remember(registry) { registry.componentRenderer<PatientCareTaskState>(ViewTypeCS.CareItem) }
      section.Render(items = byPlan[plan].orEmpty(), component = row, onItemClick = {})
    }
  }
}

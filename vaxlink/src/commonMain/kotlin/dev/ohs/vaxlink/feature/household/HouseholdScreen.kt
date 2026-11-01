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
package dev.ohs.vaxlink.feature.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.state.HouseholdCareTaskState
import dev.ohs.player.generated.state.HouseholdHeaderState
import dev.ohs.player.generated.state.HouseholdMemberState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.household_add_member
import dev.ohs.vaxlink.generated.resources.household_back
import dev.ohs.vaxlink.generated.resources.household_members
import dev.ohs.vaxlink.generated.resources.household_not_found
import dev.ohs.vaxlink.generated.resources.household_record_wash_check
import dev.ohs.vaxlink.generated.resources.household_register_newborn
import dev.ohs.vaxlink.generated.resources.household_tasks
import dev.ohs.vaxlink.generated.resources.name_unknown
import dev.ohs.vaxlink.generated.resources.profile_none_recorded
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The household: its header, the members and the two ways of adding one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
  householdId: String,
  onBack: () -> Unit,
  onMemberClick: (String) -> Unit,
  onAddMember: () -> Unit,
  onRegisterNewborn: () -> Unit,
  onRecordWashCheck: () -> Unit,
) {
  val viewModel = koinViewModel<HouseholdViewModel>(key = householdId) { parametersOf(householdId) }
  val screenState by viewModel.uiState.collectAsStateWithLifecycle()
  val registry = LocalViewRegistry.current
  val headerRenderer =
    remember(registry) {
      registry.componentRenderer<HouseholdHeaderState>(ViewTypeCS.HouseholdHeader)
    }
  val memberRenderer =
    remember(registry) { registry.componentRenderer<HouseholdMemberState>(ViewTypeCS.MemberItem) }
  val membersTitle = stringResource(Res.string.household_members)
  val noneRecorded = stringResource(Res.string.profile_none_recorded)
  val membersSection =
    remember(membersTitle, noneRecorded) {
      SectionCardLayoutRenderer<HouseholdMemberState>(
        membersTitle,
        Icons.Filled.Person,
        config = SectionCardConfig(collapsible = false),
        emptyLabel = noneRecorded,
      )
    }
  val taskRenderer =
    remember(registry) { registry.componentRenderer<HouseholdCareTaskState>(ViewTypeCS.CareItem) }
  val tasksTitle = stringResource(Res.string.household_tasks)
  val tasksSection =
    remember(tasksTitle, noneRecorded) {
      SectionCardLayoutRenderer<HouseholdCareTaskState>(
        tasksTitle,
        Icons.AutoMirrored.Filled.List,
        config = SectionCardConfig(collapsible = true),
        emptyLabel = noneRecorded,
      )
    }
  val loaded = (screenState as? HouseholdScreenState.Loaded)?.state

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(loaded?.household?.householdName ?: stringResource(Res.string.name_unknown))
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(Res.string.household_back),
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
    when (val s = screenState) {
      HouseholdScreenState.Loading ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      is HouseholdScreenState.Loaded -> {
        val state = s.state
        if (state == null) {
          Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.household_not_found))
          }
          return@Scaffold
        }
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(padding),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item(key = "header") { headerRenderer.Render(state.household, RenderOptions()) }
          item(key = "actions") {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(onClick = onRegisterNewborn, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.household_register_newborn))
              }
              OutlinedButton(onClick = onAddMember, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.household_add_member))
              }
            }
          }
          item(key = "members") {
            membersSection.Render(
              items = state.members,
              component = memberRenderer,
              onItemClick = { member -> member.memberId?.let(onMemberClick) },
            )
          }
          // Household-level care: the WASH check the eCHIS plan schedules monthly on the Group.
          item(key = "tasks") {
            tasksSection.Render(items = state.tasks, component = taskRenderer, onItemClick = {})
          }
          item(key = "wash") {
            OutlinedButton(onClick = onRecordWashCheck, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(Res.string.household_record_wash_check))
            }
          }
        }
      }
    }
  }
}

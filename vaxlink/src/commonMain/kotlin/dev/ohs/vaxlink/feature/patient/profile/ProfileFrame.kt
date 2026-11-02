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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.player.client.registry.componentRenderer
import dev.ohs.player.client.registry.layoutRenderer
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.patient_profile_back
import dev.ohs.vaxlink.generated.resources.patient_profile_default_name
import dev.ohs.vaxlink.generated.resources.patient_profile_not_found
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Test tag of every profile view's scrolling list, so tests can scroll a section into view. */
const val PROFILE_LIST_TAG = "patient_profile_list"

/** Today in the device's time zone, fixed for the life of the screen. */
@Composable
internal fun rememberToday(): LocalDate = remember {
  Clock.System.todayIn(TimeZone.currentSystemDefault())
}

/**
 * The frame every profile view shares: the app bar named for the patient, the loading and not-found
 * states, and a scrolling list the view fills from the patient's [ProfileUiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileFrame(
  patientId: String,
  onBack: () -> Unit,
  body: LazyListScope.(ProfileUiState) -> Unit,
) {
  val viewModel =
    koinViewModel<PatientProfileViewModel>(key = patientId) { parametersOf(patientId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val defaultName = stringResource(Res.string.patient_profile_default_name)
  val patient = state?.patient
  val name =
    remember(patient, defaultName) {
      listOfNotNull(patient?.givenName, patient?.familyName).joinToString(" ").ifBlank {
        defaultName
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(name) },
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
      body(s)
    }
  }
}

/** A view's heading, under the app bar. */
@Composable
internal fun ViewHeading(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 4.dp),
  )
}

/** A row that opens another profile view. */
@Composable
internal fun ProfileLink(title: String, detail: String, onClick: () -> Unit) {
  Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
        Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** A section through the registered renderers; [always] keeps it on screen when empty. */
internal inline fun <reified T : Any> LazyListScope.careSection(
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

/** A section of the full summary: always listed, and collapsed to its heading while empty. */
internal inline fun <reified T : Any> LazyListScope.summarySection(
  items: List<T>,
  key: String,
  spec: SectionSpec,
) {
  item(key = key) {
    val registry = LocalViewRegistry.current
    val row = remember(registry) { registry.componentRenderer<T>(ViewTypeCS.CareItem) }
    val section =
      remember(spec) {
        SectionCardLayoutRenderer<T>(
          spec.title,
          spec.icon,
          config = SectionCardConfig(collapsible = true),
          emptyLabel = NONE_RECORDED,
          initiallyExpanded = items.isNotEmpty(),
        )
      }
    section.Render(items = items, component = row, onItemClick = {})
  }
}

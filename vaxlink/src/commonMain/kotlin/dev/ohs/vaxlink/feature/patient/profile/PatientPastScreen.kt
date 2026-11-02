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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.vaxlink.feature.component.common.StatusChipData
import dev.ohs.vaxlink.feature.component.common.StatusRow
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.past_empty
import dev.ohs.vaxlink.generated.resources.past_filter_all
import dev.ohs.vaxlink.generated.resources.past_kind_follow_up
import dev.ohs.vaxlink.generated.resources.past_kind_immunisation
import dev.ohs.vaxlink.generated.resources.past_kind_medication
import dev.ohs.vaxlink.generated.resources.past_kind_observation
import dev.ohs.vaxlink.generated.resources.past_kind_programme
import dev.ohs.vaxlink.generated.resources.past_kind_referral
import dev.ohs.vaxlink.generated.resources.past_kind_task
import dev.ohs.vaxlink.generated.resources.past_kind_visit
import dev.ohs.vaxlink.generated.resources.past_title
import org.jetbrains.compose.resources.stringResource

/**
 * What has already happened to the patient, newest first, as one timeline. The chips filter it by
 * kind of event, listing only the kinds the patient has.
 */
@Composable
fun PatientPastScreen(patientId: String, onBack: () -> Unit) {
  var filter by rememberSaveable { mutableStateOf("") }
  ProfileFrame(patientId, onBack) { s ->
    val entries = s.past()
    val kinds = PastKind.entries.filter { kind -> entries.any { it.kind == kind } }
    val shown = entries.filter { filter.isEmpty() || it.kind.name == filter }
    item(key = "heading") { ViewHeading(stringResource(Res.string.past_title)) }
    if (kinds.size > 1) {
      item(key = "filters") {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = filter.isEmpty(),
            onClick = { filter = "" },
            label = { Text(stringResource(Res.string.past_filter_all)) },
          )
          kinds.forEach { kind ->
            FilterChip(
              selected = filter == kind.name,
              onClick = { filter = kind.name },
              label = { Text(kind.label()) },
            )
          }
        }
      }
    }
    if (shown.isEmpty()) {
      item(key = "empty") {
        Text(
          stringResource(Res.string.past_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    items(shown.size, key = { "past:$it" }) { index ->
      val entry = shown[index]
      val kind = entry.kind.label()
      StatusRow(
        title = entry.title.ifBlank { kind },
        subtitle = listOfNotNull(entry.date, entry.detail.ifBlank { null }).joinToString(" · "),
        status =
          StatusChipData(
            kind,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
          ),
      )
    }
  }
}

@Composable
private fun PastKind.label(): String =
  stringResource(
    when (this) {
      PastKind.Immunisation -> Res.string.past_kind_immunisation
      PastKind.Visit -> Res.string.past_kind_visit
      PastKind.Observation -> Res.string.past_kind_observation
      PastKind.Medication -> Res.string.past_kind_medication
      PastKind.Referral -> Res.string.past_kind_referral
      PastKind.FollowUp -> Res.string.past_kind_follow_up
      PastKind.Programme -> Res.string.past_kind_programme
      PastKind.Task -> Res.string.past_kind_task
    }
  )

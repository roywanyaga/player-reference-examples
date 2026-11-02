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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.follow_up_book
import dev.ohs.vaxlink.generated.resources.follow_up_booked
import dev.ohs.vaxlink.generated.resources.follow_up_cancel
import dev.ohs.vaxlink.generated.resources.follow_up_date_label
import dev.ohs.vaxlink.generated.resources.follow_up_invalid_date
import dev.ohs.vaxlink.generated.resources.follow_up_title
import dev.ohs.vaxlink.workflow.ClinicImportService
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The clinic books the next visit: a Task due at the start of the child's earliest upcoming
 * schedule window (editable), which also closes the referral that brought the child in.
 */
@Composable
fun BookFollowUpAction(
  patientId: String,
  user: UserContext,
  profile: ProfileUiState,
  modifier: Modifier = Modifier,
  service: ClinicImportService = koinInject(),
) {
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var booked by remember { mutableStateOf(false) }
  var dateText by remember { mutableStateOf("") }
  var invalid by remember { mutableStateOf(false) }
  val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

  LaunchedEffect(patientId) {
    dateText =
      (service.earliestUpcomingWindowStart(patientId, today) ?: today.plus(30, DateTimeUnit.DAY))
        .toString()
  }

  OutlinedButton(onClick = { open = true }, enabled = !booked, modifier = modifier.fillMaxWidth()) {
    Text(
      stringResource(
        if (booked || (!profile.referred && profile.followUps.isNotEmpty()))
          Res.string.follow_up_booked
        else Res.string.follow_up_book
      )
    )
  }

  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(stringResource(Res.string.follow_up_title)) },
      text = {
        OutlinedTextField(
          value = dateText,
          onValueChange = {
            dateText = it
            invalid = false
          },
          label = { Text(stringResource(Res.string.follow_up_date_label)) },
          isError = invalid,
          supportingText = { if (invalid) Text(stringResource(Res.string.follow_up_invalid_date)) },
          singleLine = true,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
            if (date == null) {
              invalid = true
            } else {
              scope.launch {
                service.bookFollowUp(patientId, date, user)
                booked = true
                open = false
              }
            }
          }
        ) {
          Text(stringResource(Res.string.follow_up_book))
        }
      },
      dismissButton = {
        TextButton(onClick = { open = false }) { Text(stringResource(Res.string.follow_up_cancel)) }
      },
    )
  }
}

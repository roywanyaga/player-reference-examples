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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.referral_cancel
import dev.ohs.vaxlink.generated.resources.referral_close
import dev.ohs.vaxlink.generated.resources.referral_close_confirm
import dev.ohs.vaxlink.generated.resources.referral_close_hint
import dev.ohs.vaxlink.generated.resources.referral_close_title
import dev.ohs.vaxlink.generated.resources.referral_refer_to_clinic
import dev.ohs.vaxlink.generated.resources.referral_referred
import dev.ohs.vaxlink.generated.resources.referral_withdraw
import dev.ohs.vaxlink.generated.resources.referral_withdraw_confirm
import dev.ohs.vaxlink.generated.resources.referral_withdraw_hint
import dev.ohs.vaxlink.generated.resources.referral_withdraw_title
import dev.ohs.vaxlink.workflow.ReferralService
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * J2: "Refer to clinic" on a child whose record carries an unverified immunisation. Once the
 * referral is open the button reads "Referred" and does nothing more; the header chip tracks the
 * same state.
 */
@Composable
fun ReferToClinicAction(
  patientId: String,
  user: UserContext,
  profile: ProfileUiState,
  modifier: Modifier = Modifier,
  referrals: ReferralService = koinInject(),
) {
  if (!profile.needsVerification && !profile.referred) return
  val scope = rememberCoroutineScope()
  var sending by remember { mutableStateOf(false) }
  val referred = profile.referred
  OutlinedButton(
    onClick = {
      sending = true
      scope.launch {
        try {
          referrals.refer(patientId, user)
        } finally {
          sending = false
        }
      }
    },
    enabled = !referred && !sending,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      stringResource(
        if (referred) Res.string.referral_referred else Res.string.referral_refer_to_clinic
      )
    )
  }
}

/** The CHW takes back an open referral, with the reason; shown only while one is open. */
@Composable
fun WithdrawReferralAction(
  patientId: String,
  user: UserContext,
  profile: ProfileUiState,
  modifier: Modifier = Modifier,
  referrals: ReferralService = koinInject(),
) {
  if (!profile.referred) return
  ReasonAction(
    label = stringResource(Res.string.referral_withdraw),
    title = stringResource(Res.string.referral_withdraw_title),
    hint = stringResource(Res.string.referral_withdraw_hint),
    confirm = stringResource(Res.string.referral_withdraw_confirm),
    modifier = modifier,
    onConfirm = { reason -> referrals.withdraw(patientId, reason, user) },
  )
}

/** The clinic closes an open referral without booking a follow-up, noting the outcome. */
@Composable
fun CloseReferralAction(
  patientId: String,
  user: UserContext,
  profile: ProfileUiState,
  modifier: Modifier = Modifier,
  referrals: ReferralService = koinInject(),
) {
  if (!profile.referred) return
  ReasonAction(
    label = stringResource(Res.string.referral_close),
    title = stringResource(Res.string.referral_close_title),
    hint = stringResource(Res.string.referral_close_hint),
    confirm = stringResource(Res.string.referral_close_confirm),
    modifier = modifier,
    onConfirm = { outcome -> referrals.close(patientId, outcome, user) },
  )
}

/** A button that asks for a line of text before it acts; the text is required. */
@Composable
private fun ReasonAction(
  label: String,
  title: String,
  hint: String,
  confirm: String,
  modifier: Modifier,
  onConfirm: suspend (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }
  OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) { Text(label) }
  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(title) },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text(hint) },
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(
          enabled = text.isNotBlank(),
          onClick = {
            val line = text.trim()
            open = false
            text = ""
            scope.launch { onConfirm(line) }
          },
        ) {
          Text(confirm)
        }
      },
      dismissButton = {
        TextButton(onClick = { open = false }) { Text(stringResource(Res.string.referral_cancel)) }
      },
    )
  }
}

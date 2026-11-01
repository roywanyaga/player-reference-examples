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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.referral_refer_to_clinic
import dev.ohs.vaxlink.generated.resources.referral_referred
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

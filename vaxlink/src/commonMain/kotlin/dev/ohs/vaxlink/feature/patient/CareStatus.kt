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
package dev.ohs.vaxlink.feature.patient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.vaxlink.feature.component.common.Chip
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.care_status_follow_up_due
import dev.ohs.vaxlink.generated.resources.care_status_needs_verification
import dev.ohs.vaxlink.generated.resources.care_status_referred
import dev.ohs.vaxlink.generated.resources.work_seen_at_facility
import org.jetbrains.compose.resources.stringResource

/** Where a patient stands in the referral loop, as shown on their list row and header. */
enum class CareStatus {
  /** A foreign immunisation record is on file and no referral has gone to the clinic yet. */
  NeedsVerification,
  Referred,
  SeenAtFacility,
  FollowUpDue,
}

/**
 * An open follow-up comes first; then an open referral; then a foreign record still waiting for a
 * referral; otherwise a completed referral whose follow-up is already done means the loop is
 * closed.
 */
fun PatientSummaryState.careStatus(): CareStatus? =
  when {
    followUpStatus == "requested" -> CareStatus.FollowUpDue
    referralStatus == "active" -> CareStatus.Referred
    unverifiedImmunizationId != null -> CareStatus.NeedsVerification
    referralStatus == "completed" && followUpFocus != "ServiceRequest/$referralId" ->
      CareStatus.SeenAtFacility
    else -> null
  }

@Composable
fun CareStatusChip(status: CareStatus) {
  val (label, color) =
    when (status) {
      CareStatus.NeedsVerification ->
        stringResource(Res.string.care_status_needs_verification) to
          MaterialTheme.colorScheme.tertiary
      CareStatus.Referred ->
        stringResource(Res.string.care_status_referred) to MaterialTheme.colorScheme.error
      CareStatus.SeenAtFacility ->
        stringResource(Res.string.work_seen_at_facility) to MaterialTheme.colorScheme.primary
      CareStatus.FollowUpDue ->
        stringResource(Res.string.care_status_follow_up_due) to MaterialTheme.colorScheme.tertiary
    }
  Chip(label = label, containerColor = color.copy(alpha = 0.12f), contentColor = color)
}

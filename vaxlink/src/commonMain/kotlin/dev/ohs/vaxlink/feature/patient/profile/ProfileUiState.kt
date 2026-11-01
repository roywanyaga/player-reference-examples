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

import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.PatientAllergyState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientConditionState
import dev.ohs.player.generated.state.PatientFacilityVisitState
import dev.ohs.player.generated.state.PatientFollowUpState
import dev.ohs.player.generated.state.PatientImmunizationState
import dev.ohs.player.generated.state.PatientMedicationStatementState
import dev.ohs.player.generated.state.PatientReferralOutcomeState
import dev.ohs.player.generated.state.PatientReferralState
import dev.ohs.player.generated.state.PatientRelationshipState
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.player.generated.state.PatientTreatmentState
import dev.ohs.player.generated.state.PatientVitalState

/** Everything the summary profile shows, one list per section, each extracted by a ViewJoinMap. */
data class ProfileUiState(
  val patient: PatientSummaryState? = null,
  val household: HouseholdListState? = null,
  val relationships: List<PatientRelationshipState> = emptyList(),
  val immunizations: List<PatientImmunizationState> = emptyList(),
  val conditions: List<PatientConditionState> = emptyList(),
  val allergies: List<PatientAllergyState> = emptyList(),
  val medicationStatements: List<PatientMedicationStatementState> = emptyList(),
  val careTasks: List<PatientCareTaskState> = emptyList(),
  val referrals: List<PatientReferralState> = emptyList(),
  val outcomes: List<PatientReferralOutcomeState> = emptyList(),
  val treatments: List<PatientTreatmentState> = emptyList(),
  val followUps: List<PatientFollowUpState> = emptyList(),
  val visits: List<PatientFacilityVisitState> = emptyList(),
  val vitals: List<PatientVitalState> = emptyList(),
) {
  /** True while an Immunization on the record has not been verified by the clinic. */
  val needsVerification: Boolean
    get() = immunizations.any { it.verified != true }

  /** True while a referral to the clinic is open. */
  val referred: Boolean
    get() = referrals.any { it.referralState == "active" }
}

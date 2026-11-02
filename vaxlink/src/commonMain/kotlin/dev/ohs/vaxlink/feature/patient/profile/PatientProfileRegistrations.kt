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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.player.client.registry.ViewRegistry
import dev.ohs.player.client.registry.registerComponent
import dev.ohs.player.client.registry.registerLayout
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.generated.config.CareItemConfig
import dev.ohs.player.generated.config.PatientHeaderConfig
import dev.ohs.player.generated.config.SectionCardConfig
import dev.ohs.player.generated.state.PatientAllergyState
import dev.ohs.player.generated.state.PatientCarePlanState
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
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.feature.component.common.SectionCardLayoutRenderer

/** Shown in a section that has nothing to list. */
const val NONE_RECORDED = "None recorded"

/** A profile section's heading and icon, shared by the registry and the summary page. */
class SectionSpec(val title: String, val icon: ImageVector)

object ProfileSections {
  val Family = SectionSpec("Family", Icons.Filled.Person)
  val Immunisations = SectionSpec("Immunisations", Icons.Filled.CheckCircle)
  val Conditions = SectionSpec("Conditions", Icons.Filled.Warning)
  val Observations = SectionSpec("Observations", Icons.Filled.Info)
  val MedicationsPrescribed = SectionSpec("Medications prescribed", Icons.Filled.Favorite)
  val MedicationsTaken = SectionSpec("Medications taken", Icons.Filled.Favorite)
  val Allergies = SectionSpec("Allergies", Icons.Filled.Warning)
  val Encounters = SectionSpec("Encounters", Icons.Filled.Home)
  val Programmes = SectionSpec("Programmes", Icons.Filled.DateRange)
  val Referrals = SectionSpec("Referrals", Icons.AutoMirrored.Filled.Send)
  val FacilityOutcome = SectionSpec("Facility outcome", Icons.Filled.CheckCircle)
  val FollowUps = SectionSpec("Follow-ups", Icons.Filled.DateRange)
}

fun ViewRegistry.registerPatientProfile() {
  registerComponent<PatientSummaryState, PatientHeaderConfig>(
    ViewTypeCS.PatientHeader,
    PatientHeaderRenderer(),
    PatientHeaderConfig(),
  )
  careSection<PatientRelationshipState>(ProfileSections.Family, RelationshipItemRenderer())
  careSection<PatientImmunizationState>(ProfileSections.Immunisations, ImmunizationItemRenderer())
  careSection<PatientConditionState>(ProfileSections.Conditions, ConditionItemRenderer())
  careSection<PatientVitalState>(ProfileSections.Observations, VitalItemRenderer())
  careSection<PatientTreatmentState>(ProfileSections.MedicationsPrescribed, TreatmentItemRenderer())
  careSection<PatientMedicationStatementState>(
    ProfileSections.MedicationsTaken,
    MedicationStatementItemRenderer(),
  )
  careSection<PatientAllergyState>(ProfileSections.Allergies, AllergyItemRenderer())
  careSection<PatientFacilityVisitState>(ProfileSections.Encounters, FacilityVisitItemRenderer())
  // Schedule tasks are grouped by plan and contact on the schedule screen, which builds the
  // sections itself; only the row renderer is registered here.
  registerComponent<PatientCareTaskState, CareItemConfig>(
    ViewTypeCS.CareItem,
    CareTaskItemRenderer(),
    CareItemConfig(),
  )
  careSection<PatientCarePlanState>(ProfileSections.Programmes, CarePlanItemRenderer())
  careSection<PatientReferralState>(ProfileSections.Referrals, ReferralItemRenderer())
  careSection<PatientReferralOutcomeState>(
    ProfileSections.FacilityOutcome,
    ReferralOutcomeItemRenderer(),
  )
  careSection<PatientFollowUpState>(ProfileSections.FollowUps, FollowUpItemRenderer())
}

/** The icon a plan's task section carries; one per plan, the schedule's by default. */
val PLAN_SECTION_ICON: ImageVector = Icons.AutoMirrored.Filled.List

private inline fun <reified T : Any> ViewRegistry.careSection(
  spec: SectionSpec,
  renderer: ComponentRenderer<T, CareItemConfig>,
) {
  registerComponent<T, CareItemConfig>(ViewTypeCS.CareItem, renderer, CareItemConfig())
  registerLayout<T>(
    ViewTypeCS.SectionCard,
    SectionCardLayoutRenderer(
      spec.title,
      spec.icon,
      config = SectionCardConfig(collapsible = true),
      emptyLabel = NONE_RECORDED,
    ),
  )
}

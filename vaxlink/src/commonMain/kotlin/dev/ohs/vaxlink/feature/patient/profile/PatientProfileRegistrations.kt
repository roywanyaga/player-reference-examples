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

fun ViewRegistry.registerPatientProfile() {
  registerComponent<PatientSummaryState, PatientHeaderConfig>(
    ViewTypeCS.PatientHeader,
    PatientHeaderRenderer(),
    PatientHeaderConfig(),
  )
  careSection<PatientRelationshipState>("Family", Icons.Filled.Person, RelationshipItemRenderer())
  careSection<PatientImmunizationState>(
    "Immunisations",
    Icons.Filled.CheckCircle,
    ImmunizationItemRenderer(),
  )
  careSection<PatientConditionState>("Conditions", Icons.Filled.Warning, ConditionItemRenderer())
  careSection<PatientVitalState>("Observations", Icons.Filled.Info, VitalItemRenderer())
  careSection<PatientTreatmentState>(
    "Medications prescribed",
    Icons.Filled.Favorite,
    TreatmentItemRenderer(),
  )
  careSection<PatientMedicationStatementState>(
    "Medications taken",
    Icons.Filled.Favorite,
    MedicationStatementItemRenderer(),
  )
  careSection<PatientAllergyState>("Allergies", Icons.Filled.Warning, AllergyItemRenderer())
  careSection<PatientFacilityVisitState>(
    "Encounters",
    Icons.Filled.Home,
    FacilityVisitItemRenderer(),
  )
  // Schedule tasks are grouped by plan on the screen, which builds a section per plan; only the
  // row renderer is registered here.
  registerComponent<PatientCareTaskState, CareItemConfig>(
    ViewTypeCS.CareItem,
    CareTaskItemRenderer(),
    CareItemConfig(),
  )
  careSection<PatientReferralState>(
    "Referrals",
    Icons.AutoMirrored.Filled.Send,
    ReferralItemRenderer(),
  )
  careSection<PatientReferralOutcomeState>(
    "Facility outcome",
    Icons.Filled.CheckCircle,
    ReferralOutcomeItemRenderer(),
  )
  careSection<PatientFollowUpState>("Follow-ups", Icons.Filled.DateRange, FollowUpItemRenderer())
}

/** The icon a plan's task section carries; one per plan, the schedule's by default. */
val PLAN_SECTION_ICON: ImageVector = Icons.AutoMirrored.Filled.List

private inline fun <reified T : Any> ViewRegistry.careSection(
  title: String,
  icon: ImageVector,
  renderer: ComponentRenderer<T, CareItemConfig>,
) {
  registerComponent<T, CareItemConfig>(ViewTypeCS.CareItem, renderer, CareItemConfig())
  registerLayout<T>(
    ViewTypeCS.SectionCard,
    SectionCardLayoutRenderer(
      title,
      icon,
      config = SectionCardConfig(collapsible = true),
      emptyLabel = NONE_RECORDED,
    ),
  )
}

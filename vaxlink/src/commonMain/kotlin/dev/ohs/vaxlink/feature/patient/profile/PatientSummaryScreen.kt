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

import androidx.compose.runtime.Composable
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.summary_title
import org.jetbrains.compose.resources.stringResource

/**
 * The full patient summary, the IPS-style record: every kind of clinical record the patient has,
 * each section collapsible and shown collapsed to its heading while empty. The same page serves the
 * community health worker, the clinic and, later, a clinic across the border reading a shared link.
 */
@Composable
fun PatientSummaryScreen(patientId: String, onBack: () -> Unit) {
  ProfileFrame(patientId, onBack) { s ->
    item(key = "heading") { ViewHeading(stringResource(Res.string.summary_title)) }
    summarySection(s.immunizations, "immunizations", ProfileSections.Immunisations)
    summarySection(s.conditions, "conditions", ProfileSections.Conditions)
    summarySection(s.vitals, "observations", ProfileSections.Observations)
    summarySection(s.treatments, "medications", ProfileSections.MedicationsPrescribed)
    summarySection(s.medicationStatements, "medicationStatements", ProfileSections.MedicationsTaken)
    summarySection(s.allergies, "allergies", ProfileSections.Allergies)
    summarySection(s.visits, "encounters", ProfileSections.Encounters)
    summarySection(s.carePlans, "programmes", ProfileSections.Programmes)
    summarySection(s.referrals, "referrals", ProfileSections.Referrals)
    summarySection(s.outcomes, "outcomes", ProfileSections.FacilityOutcome)
    summarySection(s.followUps, "followUps", ProfileSections.FollowUps)
  }
}

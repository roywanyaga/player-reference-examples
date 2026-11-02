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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.vaxlink.feature.questionnaire.QuestionnaireIds
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.programme_enrol_anc
import dev.ohs.vaxlink.generated.resources.programme_enrol_fp
import dev.ohs.vaxlink.generated.resources.programme_record_anc_contact
import dev.ohs.vaxlink.generated.resources.programme_record_fp_visit
import dev.ohs.vaxlink.generated.resources.programme_record_pnc_contact
import dev.ohs.vaxlink.generated.resources.programme_record_pregnancy_outcome
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.FP_INJECTABLE_PLAN
import dev.ohs.vaxlink.workflow.FP_LONGTERM_PLAN
import dev.ohs.vaxlink.workflow.FP_ORAL_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The CHW's programme forms on a woman of reproductive age, as the eCHIS app offers them: the
 * assessments that enrol a plan are always there; a contact form appears once its plan is active.
 */
@Composable
fun ProgrammeActions(
  profile: ProfileUiState,
  onLaunch: (questionnaireId: String) -> Unit,
  modifier: Modifier = Modifier,
  today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
) {
  if (!profile.isWomanOfReproductiveAge(today)) return
  val active = profile.activePlans()
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ProgrammeButton(Res.string.programme_enrol_anc) { onLaunch(QuestionnaireIds.ENROL_ANC) }
    if (ANC_PLAN in active) {
      ProgrammeButton(Res.string.programme_record_anc_contact) {
        onLaunch(QuestionnaireIds.RECORD_ANC_CONTACT)
      }
      ProgrammeButton(Res.string.programme_record_pregnancy_outcome) {
        onLaunch(QuestionnaireIds.RECORD_PREGNANCY_OUTCOME)
      }
    }
    if (PNC_PLAN in active) {
      ProgrammeButton(Res.string.programme_record_pnc_contact) {
        onLaunch(QuestionnaireIds.RECORD_PNC_CONTACT)
      }
    }
    ProgrammeButton(Res.string.programme_enrol_fp) { onLaunch(QuestionnaireIds.ENROL_FP) }
    if (active.any { it in FP_PLANS }) {
      ProgrammeButton(Res.string.programme_record_fp_visit) {
        onLaunch(QuestionnaireIds.RECORD_FP_VISIT)
      }
    }
  }
}

private val FP_PLANS = setOf(FP_INJECTABLE_PLAN, FP_ORAL_PLAN, FP_LONGTERM_PLAN)

/** The plans the patient is actively enrolled in, by canonical url. */
fun ProfileUiState.activePlans(): Set<String> =
  carePlans
    .filter { it.carePlanStatus == "active" }
    .mapNotNull { it.carePlanPlan?.let(::bareUrl) }
    .toSet()

/** Female and between 10 and 49, the eCHIS band for the ANC and FP forms. */
fun ProfileUiState.isWomanOfReproductiveAge(today: LocalDate): Boolean {
  val patient = patient ?: return false
  if (patient.gender != "female") return false
  val born = (patient.birthDate as? FhirDate.Date)?.date ?: return false
  return born <= today.minus(DatePeriod(years = 10)) && born > today.minus(DatePeriod(years = 50))
}

@Composable
private fun ProgrammeButton(label: StringResource, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(label))
  }
}

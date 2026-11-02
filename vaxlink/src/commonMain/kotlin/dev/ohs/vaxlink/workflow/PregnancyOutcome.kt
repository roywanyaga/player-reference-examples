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
package dev.ohs.vaxlink.workflow

import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.util.idOf
import kotlinx.datetime.LocalDate

private const val CONDITION_CLINICAL = "http://terminology.hl7.org/CodeSystem/condition-clinical"

/**
 * Closes what the pregnancy opened: its active Condition is resolved on [date] and its active ANC
 * CarePlan is completed, so the woman leaves the ANC register. Returns whether an ANC plan was
 * active. The Delivery observation that [recordPregnancyOutcome] writes is what lets the scheduler
 * cancel the contacts still ahead and enrol postnatal care.
 */
suspend fun FhirRepository.endPregnancy(patientId: String, date: LocalDate): Boolean {
  all("Condition")
    .filterIsInstance<Condition>()
    .filter {
      it.subject.idOf("Patient") == patientId &&
        it.code?.coding?.any { c -> c.code?.value == PREGNANCY_CONDITION_CODE } == true &&
        it.clinicalStatus?.coding?.any { c -> c.code?.value == "active" } == true
    }
    .forEach {
      upsert(
        it.copy(
          clinicalStatus =
            CodeableConcept(
              coding =
                listOf(
                  Coding(
                    system = Uri(value = CONDITION_CLINICAL),
                    code = Code(value = "resolved"),
                    display = FhirString(value = "Resolved"),
                  )
                )
            ),
          abatement = Condition.Abatement.DateTime(DateTime(value = FhirDateTime.Date(date))),
        )
      )
    }
  val plans =
    all("CarePlan").filterIsInstance<CarePlan>().filter {
      it.subject.idOf("Patient") == patientId &&
        it.status.value == CarePlan.RequestStatus.Active &&
        it.instantiatesCanonical.any { c -> c.value == ANC_PLAN }
    }
  plans.forEach {
    upsert(
      it.copy(
        status = Enumeration(value = CarePlan.RequestStatus.Completed),
        period = (it.period ?: Period()).copy(end = DateTime(value = FhirDateTime.Date(date))),
      )
    )
  }
  return plans.isNotEmpty()
}

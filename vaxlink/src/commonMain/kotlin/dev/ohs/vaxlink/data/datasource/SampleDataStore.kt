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
package dev.ohs.vaxlink.data.datasource

import dev.ohs.fhir.model.r4.AllergyIntolerance
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.MedicationRequest
import dev.ohs.fhir.model.r4.MedicationStatement
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Procedure
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.Task
import dev.ohs.player.client.model.SearchResult
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.util.idOf
import dev.ohs.vaxlink.workflow.FOLLOW_UP_TASK
import dev.ohs.vaxlink.workflow.REFERRAL_CODE

/**
 * Patient list rows: each Patient plus its referral orders (newest first), follow-up tasks (open
 * first) and unverified immunisations. The row's joins read the first of each, mirroring a search
 * with `_revinclude` sorted by the server. Each table is read once and grouped by patient.
 */
suspend fun patientSummarySearchResults(repository: FhirRepository): List<SearchResult<Resource>> {
  val referrals = referralOrdersByPatient(repository)
  val followUps = followUpsByPatient(repository)
  val unverified = unverifiedImmunizationsByPatient(repository)
  return repository.all("Patient").filterIsInstance<Patient>().map {
    summary(it, referrals[it.id].orEmpty(), followUps[it.id].orEmpty(), unverified[it.id].orEmpty())
  }
}

/** One patient's list row; see [patientSummarySearchResults]. */
suspend fun patientSummarySearchResult(
  patientId: String,
  repository: FhirRepository,
): SearchResult<Resource>? {
  val patient = repository.get("Patient", patientId) as? Patient ?: return null
  return summary(
    patient,
    referralOrdersByPatient(repository)[patientId].orEmpty(),
    followUpsByPatient(repository)[patientId].orEmpty(),
    unverifiedImmunizationsByPatient(repository)[patientId].orEmpty(),
  )
}

private fun summary(
  patient: Patient,
  referrals: List<ServiceRequest>,
  followUps: List<Task>,
  unverified: List<Immunization>,
) =
  SearchResult<Resource>(
    resource = patient,
    revIncluded =
      mapOf(
        ("ServiceRequest" to "subject") to
          referrals.sortedByDescending { it.authoredOn?.value?.toString() },
        ("Task" to "subject") to
          followUps.sortedWith(
            compareBy<Task> { it.status.value != Task.TaskStatus.Requested }
              .thenByDescending { it.authoredOn?.value?.toString() }
          ),
        ("Immunization" to "patient") to unverified,
      ),
  )

private suspend fun unverifiedImmunizationsByPatient(repository: FhirRepository) =
  repository
    .all("Immunization")
    .filterIsInstance<Immunization>()
    .filter { it.primarySource?.value != true }
    .groupBy { it.patient.idOf("Patient") }

private suspend fun referralOrdersByPatient(repository: FhirRepository) =
  repository
    .all("ServiceRequest")
    .filterIsInstance<ServiceRequest>()
    .filter { it.isReferralOrder() }
    .groupBy { it.subject.idOf("Patient") }

private suspend fun followUpsByPatient(repository: FhirRepository) =
  repository
    .all("Task")
    .filterIsInstance<Task>()
    .filter { it.isFollowUp() }
    .groupBy { it.`for`.idOf("Patient") }

/** Patient profile: root = Patient, with every care resource that references the patient. */
suspend fun patientProfileSearchResult(
  patientId: String,
  repository: FhirRepository,
): SearchResult<Resource>? {
  val patient = repository.get("Patient", patientId) as? Patient ?: return null
  suspend fun revIncluded(type: String, subject: (Resource) -> Reference?) =
    (type to "subject") to
      repository.all(type).filter { subject(it).idOf("Patient") == patientId }.newestFirst()
  return SearchResult(
    resource = patient,
    revIncluded =
      mapOf(
        revIncluded("ServiceRequest") { (it as ServiceRequest).subject },
        revIncluded("Procedure") { (it as Procedure).subject },
        revIncluded("MedicationRequest") { (it as MedicationRequest).subject },
        revIncluded("Task") { (it as Task).`for` },
        revIncluded("CarePlan") { (it as CarePlan).subject },
        revIncluded("Encounter") { (it as Encounter).subject },
        revIncluded("Observation") { (it as Observation).subject },
        revIncluded("Condition") { (it as Condition).subject },
        revIncluded("MedicationStatement") { (it as MedicationStatement).subject },
        ("Immunization" to "patient") to
          repository
            .all("Immunization")
            .filter { (it as Immunization).patient.idOf("Patient") == patientId }
            .newestFirst(),
        ("AllergyIntolerance" to "patient") to
          repository
            .all("AllergyIntolerance")
            .filter { (it as AllergyIntolerance).patient.idOf("Patient") == patientId }
            .newestFirst(),
        ("RelatedPerson" to "patient") to
          repository.all("RelatedPerson").filter {
            (it as RelatedPerson).patient.idOf("Patient") == patientId
          },
      ),
  )
}

private fun ServiceRequest.isReferralOrder() =
  intent.value == ServiceRequest.RequestIntent.Order &&
    code?.coding?.any { it.code?.value == REFERRAL_CODE } == true

private fun Task.isFollowUp() = code?.coding?.any { it.code?.value == FOLLOW_UP_TASK } == true

private fun List<Resource>.newestFirst() = sortedByDescending {
  when (it) {
    is ServiceRequest -> it.authoredOn?.value
    is MedicationRequest -> it.authoredOn?.value
    is Task -> it.authoredOn?.value
    is Encounter -> it.period?.start?.value
    is Observation -> (it.effective as? Observation.Effective.DateTime)?.value?.value
    is Immunization -> (it.occurrence as? Immunization.Occurrence.DateTime)?.value?.value
    is Condition -> it.recordedDate?.value
    is AllergyIntolerance -> it.recordedDate?.value
    is MedicationStatement -> it.dateAsserted?.value
    else -> null
  }?.toString() ?: it.meta?.lastUpdated?.value?.toString()
}

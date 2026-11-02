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

import dev.ohs.fhir.model.r4.Annotation
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Markdown
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.generateId
import dev.ohs.vaxlink.util.idOf
import kotlin.time.Clock
import kotlin.time.Instant

/** J2: the CHW refers a child whose foreign immunization record the clinic has to verify. */
class ReferralService(
  private val repository: FhirRepository,
  private val now: () -> Instant = { Clock.System.now() },
) {
  /** True while the child has an Immunization nobody has verified. */
  suspend fun needsVerification(patientId: String): Boolean =
    repository.all("Immunization").filterIsInstance<Immunization>().any {
      it.patient.idOf("Patient") == patientId && it.primarySource?.value != true
    }

  /** The open referral, if one is already on its way to the clinic. */
  suspend fun openReferral(patientId: String): ServiceRequest? =
    repository.all("ServiceRequest").filterIsInstance<ServiceRequest>().firstOrNull {
      it.subject.idOf("Patient") == patientId &&
        it.status.value == ServiceRequest.RequestStatus.Active &&
        it.intent.value == ServiceRequest.RequestIntent.Order &&
        it.code?.coding?.any { c -> c.code?.value == REFERRAL_CODE } == true
    }

  /** Creates the referral to the user's facility, or returns the one already open. */
  suspend fun refer(patientId: String, user: UserContext): ServiceRequest {
    openReferral(patientId)?.let {
      return it
    }
    val referral =
      ServiceRequest(
        id = generateId(),
        status = Enumeration(value = ServiceRequest.RequestStatus.Active),
        intent = Enumeration(value = ServiceRequest.RequestIntent.Order),
        priority = Enumeration(value = ServiceRequest.RequestPriority.Routine),
        code =
          CodeableConcept(
            coding =
              listOf(
                Coding(
                  system = Uri(value = "http://snomed.info/sct"),
                  code = Code(value = REFERRAL_CODE),
                  display = FhirString(value = "Patient referral for consultation"),
                )
              )
          ),
        subject = reference("Patient/$patientId"),
        authoredOn = DateTime(value = FhirDateTime.fromString(wholeSeconds())),
        requester = reference("Practitioner/${user.practitionerId}"),
        performer = listOf(reference("Organization/${user.referralOrganizationId}")),
        reasonCode =
          listOf(CodeableConcept(text = FhirString(value = REFERRAL_REASON_FOREIGN_RECORD))),
      )
    repository.upsert(referral)
    return referral
  }

  /**
   * The CHW takes the referral back (the family went elsewhere, or the child was already seen): it
   * is revoked, with the reason on the record. Returns false when there was no open referral.
   */
  suspend fun withdraw(patientId: String, reason: String, user: UserContext): Boolean =
    finish(patientId, ServiceRequest.RequestStatus.Revoked, "Withdrawn: $reason", user)

  /**
   * The clinic closes the referral without booking a follow-up: it is completed, with the outcome
   * on the record. Returns false when there was no open referral.
   */
  suspend fun close(patientId: String, outcome: String, user: UserContext): Boolean =
    finish(patientId, ServiceRequest.RequestStatus.Completed, "Closed: $outcome", user)

  private suspend fun finish(
    patientId: String,
    status: ServiceRequest.RequestStatus,
    text: String,
    user: UserContext,
  ): Boolean {
    val referral = openReferral(patientId) ?: return false
    repository.upsert(
      referral.copy(
        status = Enumeration(value = status),
        note =
          referral.note +
            Annotation(
              author =
                Annotation.Author.Reference(reference("Practitioner/${user.practitionerId}")),
              time = DateTime(value = FhirDateTime.fromString(wholeSeconds())),
              text = Markdown(value = text),
            ),
      )
    )
    return true
  }

  // A fractional second breaks fhir-model's date-time parsing, so stamp whole seconds.
  private fun wholeSeconds() = Instant.fromEpochSeconds(now().epochSeconds).toString()

  private fun reference(value: String) = Reference(reference = FhirString(value = value))
}

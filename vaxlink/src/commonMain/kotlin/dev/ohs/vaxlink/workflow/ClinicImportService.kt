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

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Instant
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.Provenance
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.data.terminology.BundledTerminology
import dev.ohs.vaxlink.generateId
import dev.ohs.vaxlink.hcert.CertificateFingerprint
import dev.ohs.vaxlink.hcert.DoseProposal
import dev.ohs.vaxlink.hcert.DvcFhirMapper
import dev.ohs.vaxlink.hcert.DvcMin
import dev.ohs.vaxlink.hcert.HcertClock
import dev.ohs.vaxlink.hcert.UnverifiedHcert
import dev.ohs.vaxlink.hcert.VerificationEvidence
import dev.ohs.vaxlink.hcert.VerifiedHcert
import dev.ohs.vaxlink.util.idOf
import kotlin.time.Instant as KtInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val CLINIC_FOLLOW_UP_TASK = "clinic-follow-up"
const val TASK_TYPE_SYSTEM = "http://ohs.dev/fhir/CodeSystem/task-type"
const val PROVENANCE_PARTICIPANT_TYPE =
  "http://terminology.hl7.org/CodeSystem/provenance-participant-type"
const val DOSE_CONFIRMED_ACTIVITY = "Schedule dose confirmed by clinician"
const val DOSE_NOT_COUNTED_ACTIVITY = "Foreign dose not counted on the Lao schedule"

/** One dose of the Lao schedule the nurse may choose for a foreign dose the map does not place. */
data class LaoDose(val code: String, val display: String)

/** The nurse's decision for a dose the national map leaves unmatched (BCG). */
sealed interface DoseChoice {
  data class Dose(val dose: LaoDose) : DoseChoice

  data object NotCounted : DoseChoice
}

sealed interface ImportOutcome {
  /** Scanned again after import: nothing changes. */
  data class AlreadyImported(val immunizationId: String) : ImportOutcome

  /** The card belongs to another child; nothing is imported. */
  data class WrongChild(val certificateName: String, val certificateBirthDate: String) :
    ImportOutcome

  data object NoVaccination : ImportOutcome

  /**
   * Verified and stored. [needsConfirmation] is true when the national map has no dose for the
   * vaccine type, so the nurse has to decide (BCG); [walkIn] when there was no CHW record.
   */
  data class Imported(
    val immunization: Immunization,
    val provenance: Provenance,
    val proposal: DoseProposal,
    val walkIn: Boolean,
  ) : ImportOutcome {
    val needsConfirmation: Boolean
      get() = proposal is DoseProposal.Unmatched
  }
}

/**
 * J3, the clinic: a verified certificate becomes part of the domestic record. The Immunization the
 * CHW recorded is updated in place (`primarySource` true, a Provenance naming the issuer, the DSC
 * and the nurse); a walk-in without a CHW record is recorded first. The Lao coding is added
 * automatically when the national map has a target and never otherwise: BCG waits for the nurse,
 * whose decision is itself recorded as a Provenance. Codes as issued are never changed.
 */
class ClinicImportService(
  private val repository: FhirRepository,
  private val terminology: BundledTerminology,
  private val schedule: LaoScheduleService,
  private val recorder: CertificateRecorder,
  private val transactor: Transactor = Transactor { it() },
  private val now: () -> Long = HcertClock::nowEpochSeconds,
  private val readTerminology: suspend (String) -> String = BundledTerminology::readBundled,
) {
  suspend fun import(
    patientId: String,
    hc1: String,
    verified: VerifiedHcert,
    user: UserContext,
  ): ImportOutcome {
    val dvc = verified.dvc ?: return ImportOutcome.NoVaccination
    val patient =
      repository.get("Patient", patientId) as? Patient ?: return ImportOutcome.NoVaccination
    if (!sameChild(dvc, patient)) return ImportOutcome.WrongChild(dvc.n, dvc.dob)
    val fingerprint = CertificateFingerprint.of(hc1)
    var existing = byFingerprint(fingerprint)
    var walkIn = false
    if (existing != null && existing.primarySource?.value == true) {
      return ImportOutcome.AlreadyImported(existing.id!!)
    }
    if (existing == null) {
      walkIn = true
      when (val recorded = recorder.record(patientId, hc1, unverifiedOf(verified))) {
        is RecordOutcome.Recorded -> existing = recorded.immunization
        is RecordOutcome.AlreadyRecorded ->
          existing = repository.get("Immunization", recorded.immunizationId) as Immunization
        RecordOutcome.NoVaccination -> return ImportOutcome.NoVaccination
      }
    }
    val evidence =
      VerificationEvidence(
        issuer = verified.iss,
        dscKid = verified.dsc.kid,
        dscCountry = verified.dsc.country,
        verifier = reference("Practitioner/${user.practitionerId}"),
        verifiedAtEpochSeconds = now(),
      )
    val (imported, provenance) = DvcFhirMapper.markVerified(existing!!, evidence)
    val terms = terminology.get()
    val proposal = terms.doseMap.propose(terms.products.vaccineType(dvc.vx.vp))
    transactor.atomically {
      repository.upsert(imported)
      repository.upsert(provenance)
      schedule.refresh(patient)
    }
    return ImportOutcome.Imported(imported, provenance, proposal, walkIn)
  }

  /** The nurse's decision for an unmatched dose, recorded as a Provenance on the Immunization. */
  suspend fun confirmDose(
    immunizationId: String,
    choice: DoseChoice,
    user: UserContext,
  ): Immunization {
    val immunization =
      repository.get("Immunization", immunizationId) as? Immunization
        ?: error("Immunization $immunizationId is not on this device")
    val updated =
      when (choice) {
        is DoseChoice.Dose ->
          immunization.copy(
            vaccineCode =
              immunization.vaccineCode.copy(
                coding =
                  immunization.vaccineCode.coding.filterNot {
                    it.system?.value == LAO_DOSE_SYSTEM
                  } +
                    Coding(
                      system = Uri(value = LAO_DOSE_SYSTEM),
                      code = Code(value = choice.dose.code),
                      display = FhirString(value = choice.dose.display),
                      extension =
                        listOf(
                          Extension(
                            url = CODING_ORIGIN_EXTENSION,
                            value = Extension.Value.Code(Code(value = "confirmed")),
                          )
                        ),
                    )
              )
          )
        DoseChoice.NotCounted -> immunization
      }
    val provenance =
      Provenance(
        id = generateId(),
        target = listOf(reference("Immunization/$immunizationId")),
        recorded = Instant(value = utcDateTime(now())),
        activity =
          CodeableConcept(
            text =
              FhirString(
                value =
                  if (choice is DoseChoice.Dose) DOSE_CONFIRMED_ACTIVITY
                  else DOSE_NOT_COUNTED_ACTIVITY
              )
          ),
        reason =
          listOf(
            CodeableConcept(
              text =
                FhirString(
                  value =
                    when (choice) {
                      is DoseChoice.Dose -> "Counted as ${choice.dose.display}"
                      DoseChoice.NotCounted -> "Not counted on the Lao schedule"
                    }
                )
            )
          ),
        agent =
          listOf(
            Provenance.Agent(
              type =
                CodeableConcept(
                  coding =
                    listOf(
                      Coding(
                        system = Uri(value = PROVENANCE_PARTICIPANT_TYPE),
                        code = Code(value = "author"),
                        display = FhirString(value = "Author"),
                      )
                    )
                ),
              who = reference("Practitioner/${user.practitionerId}"),
            )
          ),
      )
    transactor.atomically {
      repository.upsert(updated)
      repository.upsert(provenance)
      (repository.get("Patient", immunization.patient.idOf("Patient") ?: "") as? Patient)?.let {
        schedule.refresh(it)
      }
    }
    return updated
  }

  /** Every dose of the Lao schedule, for the confirm dialog. */
  suspend fun laoDoses(): List<LaoDose> {
    val document =
      Json.parseToJsonElement(readTerminology("CodeSystem-lao-epi-schedule-dose.json")).jsonObject
    return document["concept"]?.jsonArray.orEmpty().map { element ->
      val concept = element.jsonObject
      LaoDose(
        code = concept["code"]!!.jsonPrimitive.content,
        display =
          concept["display"]?.jsonPrimitive?.content ?: concept["code"]!!.jsonPrimitive.content,
      )
    }
  }

  /** The start of the child's earliest schedule window that is still ahead, if any. */
  suspend fun earliestUpcomingWindowStart(patientId: String, today: LocalDate): LocalDate? =
    repository
      .all("Task")
      .filterIsInstance<Task>()
      .filter {
        it.`for`.idOf("Patient") == patientId &&
          it.status.value == Task.TaskStatus.Requested &&
          it.instantiatesCanonical?.value != null
      }
      .mapNotNull { it.restriction?.period?.start?.value?.toString()?.let(::localDateOrNull) }
      .filter { it >= today }
      .minOrNull()

  /** Books the next visit and closes the referral that brought the child in. */
  suspend fun bookFollowUp(patientId: String, dueOn: LocalDate, user: UserContext): Task {
    val referral =
      repository.all("ServiceRequest").filterIsInstance<ServiceRequest>().firstOrNull {
        it.subject.idOf("Patient") == patientId &&
          it.status.value == ServiceRequest.RequestStatus.Active &&
          it.code?.coding?.any { c -> c.code?.value == REFERRAL_CODE } == true
      }
    val task =
      Task(
        id = generateId(),
        status = Enumeration(value = Task.TaskStatus.Requested),
        intent = Enumeration(value = Task.TaskIntent.Order),
        code =
          CodeableConcept(
            coding =
              listOf(
                Coding(
                  system = Uri(value = TASK_TYPE_SYSTEM),
                  code = Code(value = CLINIC_FOLLOW_UP_TASK),
                  display = FhirString(value = "Clinic follow-up"),
                )
              ),
            text = FhirString(value = "Clinic follow-up"),
          ),
        description = FhirString(value = "Next clinic visit"),
        `for` = reference("Patient/$patientId"),
        focus = referral?.let { reference("ServiceRequest/${it.id}") },
        authoredOn = DateTime(value = utcDateTime(now())),
        requester = reference("Practitioner/${user.practitionerId}"),
        owner = reference("Organization/${user.organizationId}"),
        restriction =
          Task.Restriction(period = Period(end = DateTime(value = FhirDateTime.Date(dueOn)))),
      )
    transactor.atomically {
      repository.upsert(task)
      referral?.let {
        repository.upsert(
          it.copy(status = Enumeration(value = ServiceRequest.RequestStatus.Completed))
        )
      }
    }
    return task
  }

  private suspend fun byFingerprint(fingerprint: String): Immunization? =
    repository.all("Immunization").filterIsInstance<Immunization>().firstOrNull { imm ->
      imm.identifier.any {
        it.system?.value == HCERT_FINGERPRINT_SYSTEM && it.value?.value == fingerprint
      }
    }

  private fun unverifiedOf(verified: VerifiedHcert) =
    UnverifiedHcert(
      kid = verified.dsc.kid,
      iss = verified.iss,
      iat = verified.iat,
      exp = verified.exp,
      entries = verified.entries,
      diagnostics = verified.diagnostics,
    )

  private fun reference(value: String) = Reference(reference = FhirString(value = value))

  private fun utcDateTime(epochSeconds: Long): FhirDateTime =
    FhirDateTime.DateTime(
      KtInstant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC),
      UtcOffset.ZERO,
    )

  private fun localDateOrNull(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

  companion object {
    /**
     * The card's child must be the open profile: same date of birth, and every token of the
     * certificate's name appears in the patient's name (case-insensitive), so "Chanthala
     * SOUVANNALATH" matches given Chanthala, family SOUVANNALATH in either case.
     */
    fun sameChild(dvc: DvcMin, patient: Patient): Boolean {
      val birthDate = patient.birthDate?.value?.toString() ?: return false
      if (birthDate != dvc.dob) return false
      val patientTokens =
        patient.name
          .flatMap { n -> n.given.map { it.value } + n.family?.value + n.text?.value }
          .filterNotNull()
          .flatMap { it.split(Regex("\\s+")) }
          .map { it.lowercase() }
          .filter { it.isNotBlank() }
          .toSet()
      val certificateTokens =
        dvc.n.split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotBlank() }
      return certificateTokens.isNotEmpty() && certificateTokens.all { it in patientTokens }
    }
  }
}

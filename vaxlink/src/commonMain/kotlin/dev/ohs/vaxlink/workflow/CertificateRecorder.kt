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
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.data.terminology.BundledTerminology
import dev.ohs.vaxlink.hcert.CertificateFingerprint
import dev.ohs.vaxlink.hcert.DoseProposal
import dev.ohs.vaxlink.hcert.DvcFhirMapper
import dev.ohs.vaxlink.hcert.DvcMappingOptions
import dev.ohs.vaxlink.hcert.HcertClock
import dev.ohs.vaxlink.hcert.UnverifiedHcert

/** What recording a scanned card produced. */
sealed interface RecordOutcome {
  /** The card is already on someone's record; nothing was written. */
  data class AlreadyRecorded(val immunizationId: String) : RecordOutcome

  /** The card carried no vaccination entry. */
  data object NoVaccination : RecordOutcome

  data class Recorded(val immunization: Immunization, val proposal: DoseProposal) : RecordOutcome
}

/**
 * J2: a decoded, unverified certificate becomes an Immunization on the child's record, stored with
 * the PreQual coding exactly as issued and `primarySource` false. When the national map names a Lao
 * dose for the vaccine type (HepB), that coding is added too and marked automatic, and the schedule
 * ticks the dose. When it does not (BCG), the record stands alone as a foreign dose.
 */
class CertificateRecorder(
  private val repository: FhirRepository,
  private val terminology: BundledTerminology,
  private val schedule: LaoScheduleService,
  private val transactor: Transactor = Transactor { it() },
  private val now: () -> Long = HcertClock::nowEpochSeconds,
) {
  suspend fun record(patientId: String, hc1: String, decoded: UnverifiedHcert): RecordOutcome {
    val dvc = decoded.dvc ?: return RecordOutcome.NoVaccination
    val fingerprint = CertificateFingerprint.of(hc1)
    recordedAlready(fingerprint)?.let {
      return RecordOutcome.AlreadyRecorded(it.id!!)
    }
    val terms = terminology.get()
    val mapped =
      DvcFhirMapper.map(
          dvc,
          DvcMappingOptions(
            patientId = patientId,
            certificateFingerprint = fingerprint,
            products = terms.products,
            recordedAtEpochSeconds = now(),
          ),
        )
        .immunization
    val proposal = terms.doseMap.propose(terms.products.vaccineType(dvc.vx.vp))
    val immunization =
      when (proposal) {
        is DoseProposal.Mapped -> mapped.withLaoCoding(proposal, terms.doseMap.targetSystem)
        else -> mapped
      }
    transactor.atomically {
      repository.upsert(immunization)
      (repository.get("Patient", patientId) as? Patient)?.let { schedule.refresh(it) }
    }
    return RecordOutcome.Recorded(immunization, proposal)
  }

  private suspend fun recordedAlready(fingerprint: String): Immunization? =
    repository.all("Immunization").filterIsInstance<Immunization>().firstOrNull { imm ->
      imm.identifier.any {
        it.system?.value == HCERT_FINGERPRINT_SYSTEM && it.value?.value == fingerprint
      }
    }

  private fun Immunization.withLaoCoding(proposal: DoseProposal.Mapped, system: String?) =
    copy(
      vaccineCode =
        vaccineCode.copy(
          coding =
            vaccineCode.coding +
              Coding(
                system = Uri(value = system ?: LAO_DOSE_SYSTEM),
                code = Code(value = proposal.code),
                display = proposal.display?.let { FhirString(value = it) },
                extension =
                  listOf(
                    Extension(
                      url = CODING_ORIGIN_EXTENSION,
                      value = Extension.Value.Code(Code(value = "automatic")),
                    )
                  ),
              )
        )
    )
}

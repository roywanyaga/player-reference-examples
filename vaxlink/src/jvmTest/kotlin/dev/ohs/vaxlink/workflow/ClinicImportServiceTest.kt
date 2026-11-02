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

import dev.ohs.fhir.model.r4.AuditEvent
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Provenance
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.hcert.HcertService
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.data.terminology.BundledTerminology
import dev.ohs.vaxlink.feature.referral.referralInbox
import dev.ohs.vaxlink.hcert.DoseProposal
import dev.ohs.vaxlink.hcert.FailureReason
import dev.ohs.vaxlink.hcert.InMemoryTrustListStorage
import dev.ohs.vaxlink.hcert.ReadResult
import dev.ohs.vaxlink.hcert.TrustListCache
import dev.ohs.vaxlink.hcert.UnverifiedHcert
import dev.ohs.vaxlink.hcert.VerificationFailure
import dev.ohs.vaxlink.hcert.VerificationResult
import dev.ohs.vaxlink.hcert.VerifiedHcert
import dev.ohs.vaxlink.hcert.VerifyStep
import dev.ohs.vaxlink.util.FhirJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** J3 on the worked example: verify, import, decide on BCG, book the follow-up, audit. */
class ClinicImportServiceTest {
  private fun resource(path: String) =
    checkNotNull(ClinicImportServiceTest::class.java.getResource(path)) { path }.readText()

  private val certificates =
    Json.parseToJsonElement(resource("/hcert/dvc-qr-example-XXR.json"))
      .jsonObject["certificates"]!!
      .jsonObject

  private fun hc1(name: String) = certificates[name]!!.jsonObject["hc1"]!!.jsonPrimitive.content

  private val iat = certificates["hepb1"]!!.jsonObject["iat"]!!.jsonPrimitive.long
  private val now = iat + 3600
  private val repository = InMemorySampleFhirRepository()
  private val hcert =
    HcertService(
      TrustListCache(
        InMemoryTrustListStorage(),
        bundled = { resource("/hcert/XXR-DSC-did.json") },
        bundledFetchedAtEpochSeconds = iat,
      )
    )
  private val schedule =
    LaoScheduleService(
      repository,
      operatorFactory = {
        FhirOperator(InMemoryWorkflowRepository(), resolver = BundledProtocols.load())
      },
      protocolsFactory = { BundledProtocols.load() },
      today = { LocalDate(2026, 11, 1) },
    )
  private val recorder =
    CertificateRecorder(repository, BundledTerminology(), schedule, now = { now })
  private val service =
    ClinicImportService(repository, BundledTerminology(), schedule, recorder, now = { now })
  private val nurse = UserContext(AppRole.NURSE, "nurse-1", "facility", "opd")
  private val chw =
    UserContext(AppRole.CHW, "chw-1", "unit", "village", facilityOrganizationId = "facility")
  private val audit = AuditService(repository, deviceId = { "device-1" }, now = { now })

  private suspend fun baby(): Patient =
    FhirJson.instance
      .decodeFromString(
        Resource.serializer(),
        """{"resourceType":"Patient","id":"baby","name":[{"given":["Chanthala"],"family":"Souvannalath"}],
            "gender":"female","birthDate":"2026-10-21"}""",
      )
      .also { repository.upsert(it) } as Patient

  private suspend fun verified(hc1: String): VerifiedHcert =
    assertIs<VerificationResult.Verified>(hcert.verify(hc1, now).result).hcert

  private suspend fun decoded(hc1: String): UnverifiedHcert =
    assertIs<ReadResult.Decoded>(hcert.read(hc1)).hcert

  private suspend fun immunization(): Immunization =
    repository.all("Immunization").filterIsInstance<Immunization>().single()

  private suspend fun provenances() = repository.all("Provenance").filterIsInstance<Provenance>()

  private suspend fun task(dose: String): Task =
    repository.all("Task").filterIsInstance<Task>().single {
      it.instantiatesCanonical?.value?.endsWith("LaoEpi-$dose") == true
    }

  @Test
  fun aChwRecordedHepBIsVerifiedInPlaceWithAProvenance() = runTest {
    baby()
    recorder.record("baby", hc1("hepb1"), decoded(hc1("hepb1")))
    val before = immunization()

    val outcome =
      assertIs<ImportOutcome.Imported>(
        service.import("baby", hc1("hepb1"), verified(hc1("hepb1")), nurse)
      )

    assertEquals(false, outcome.walkIn)
    assertEquals(false, outcome.needsConfirmation)
    val after = immunization()
    assertEquals(before.id, after.id)
    assertEquals(true, after.primarySource?.value)
    assertNull(after.reportOrigin)
    assertEquals(before.vaccineCode, after.vaccineCode, "codes stay as the CHW stored them")
    val provenance = provenances().single()
    assertEquals("Immunization/${after.id}", provenance.target.single().reference?.value)
    assertEquals("Practitioner/nurse-1", provenance.agent.last().who.reference?.value)
    assertTrue(provenance.agent.first().who.display?.value.orEmpty().contains("916ffa567c0f44a2"))
    assertEquals(Task.TaskStatus.Completed, task("hb0").status.value)
  }

  @Test
  fun aSecondScanOfAnImportedCardIsRefused() = runTest {
    baby()
    service.import("baby", hc1("hepb1"), verified(hc1("hepb1")), nurse)
    val again = service.import("baby", hc1("hepb1"), verified(hc1("hepb1")), nurse)
    assertIs<ImportOutcome.AlreadyImported>(again)
    assertEquals(1, repository.all("Immunization").size)
    assertEquals(1, provenances().size)
  }

  @Test
  fun aWalkInBcgIsRecordedAndVerifiedInOneStepAndNeedsTheNurse() = runTest {
    baby()
    val outcome =
      assertIs<ImportOutcome.Imported>(
        service.import("baby", hc1("bcg"), verified(hc1("bcg")), nurse)
      )
    assertEquals(true, outcome.walkIn)
    assertIs<DoseProposal.Unmatched>(outcome.proposal)
    assertTrue(outcome.needsConfirmation)
    val stored = immunization()
    assertEquals(true, stored.primarySource?.value)
    assertEquals(listOf("a3K3X000006MiiCUAS"), stored.vaccineCode.coding.map { it.code?.value })
    assertEquals(23, service.laoDoses().size)
  }

  @Test
  fun confirmingADoseAddsTheCodingAndAProvenanceAndTicksTheTask() = runTest {
    baby()
    val outcome =
      assertIs<ImportOutcome.Imported>(
        service.import("baby", hc1("bcg"), verified(hc1("bcg")), nurse)
      )
    val penta1 = service.laoDoses().single { it.code == "PENTA-1" }

    service.confirmDose(outcome.immunization.id!!, DoseChoice.Dose(penta1), nurse)

    val stored = immunization()
    val lao = stored.vaccineCode.coding.single { it.system?.value == LAO_DOSE_SYSTEM }
    assertEquals("PENTA-1", lao.code?.value)
    assertEquals(
      "confirmed",
      lao.extension.single { it.url == CODING_ORIGIN_EXTENSION }.value?.asCode()?.value?.value,
    )
    assertEquals("a3K3X000006MiiCUAS", stored.vaccineCode.coding.first().code?.value)
    val decision = provenances().single { it.activity?.text?.value == DOSE_CONFIRMED_ACTIVITY }
    assertEquals("Practitioner/nurse-1", decision.agent.single().who.reference?.value)
    assertTrue(decision.reason.single().text?.value.orEmpty().contains("Penta 1"))
    assertEquals(Task.TaskStatus.Completed, task("penta-1").status.value)
  }

  @Test
  fun notCountingAddsOnlyTheProvenance() = runTest {
    baby()
    val outcome =
      assertIs<ImportOutcome.Imported>(
        service.import("baby", hc1("bcg"), verified(hc1("bcg")), nurse)
      )

    service.confirmDose(outcome.immunization.id!!, DoseChoice.NotCounted, nurse)

    assertNull(
      immunization().vaccineCode.coding.firstOrNull { it.system?.value == LAO_DOSE_SYSTEM }
    )
    assertNotNull(provenances().single { it.activity?.text?.value == DOSE_NOT_COUNTED_ACTIVITY })
    assertTrue(
      repository.all("Task").filterIsInstance<Task>().all {
        it.status.value == Task.TaskStatus.Requested
      }
    )
  }

  @Test
  fun anotherChildsCardImportsNothing() = runTest {
    repository.upsert(
      FhirJson.instance.decodeFromString(
        Resource.serializer(),
        """{"resourceType":"Patient","id":"other","name":[{"given":["Bounmy"],"family":"Sengmany"}],
            "gender":"male","birthDate":"2026-10-21"}""",
      )
    )
    val outcome = service.import("other", hc1("hepb1"), verified(hc1("hepb1")), nurse)
    val wrong = assertIs<ImportOutcome.WrongChild>(outcome)
    assertEquals("Chanthala SOUVANNALATH", wrong.certificateName)
    assertEquals("2026-10-21", wrong.certificateBirthDate)
    assertTrue(repository.all("Immunization").isEmpty())
  }

  @Test
  fun everyVerificationAttemptIsAuditedAndAFailureChangesNothing() = runTest {
    baby()
    val success = hcert.verify(hc1("hepb1"), now).result
    audit.recordVerification(success, hc1("hepb1"), nurse, "baby")
    val failure =
      VerificationResult.Failed(
        VerificationFailure(VerifyStep.SIGNATURE, FailureReason.SIGNATURE_INVALID, "tampered")
      )
    audit.recordVerification(failure, hc1("hepb1") + "X", nurse, "baby")

    val events = repository.all("AuditEvent").filterIsInstance<AuditEvent>()
    assertEquals(listOf("0", "4"), events.map { it.outcome?.value?.toString().orEmpty() }.sorted())
    assertTrue(events.all { it.source.observer.identifier?.value?.value == "device-1" })
    assertTrue(events.all { it.agent.single().who?.reference?.value == "Practitioner/nurse-1" })
    assertTrue(events.any { it.outcomeDesc?.value.orEmpty().contains("SIGNATURE_INVALID") })
    assertTrue(repository.all("Immunization").isEmpty(), "a failed attempt imports nothing")
  }

  @Test
  fun theInboxListsOpenReferralsForTheFacilityAndTheFollowUpClosesOne() = runTest {
    baby()
    recorder.record("baby", hc1("hepb1"), decoded(hc1("hepb1")))
    ReferralService(repository).refer("baby", chw)
    val inbox = repository.referralInbox("facility")
    assertEquals(listOf("baby"), inbox.map { it.patientId })
    assertEquals("Chanthala Souvannalath", inbox.single().childName)
    assertEquals(REFERRAL_REASON_FOREIGN_RECORD, inbox.single().reason)
    assertTrue(repository.referralInbox("elsewhere").isEmpty())

    val dueOn = assertNotNull(service.earliestUpcomingWindowStart("baby", LocalDate(2026, 11, 1)))
    val followUp = service.bookFollowUp("baby", dueOn, nurse)

    assertEquals(CLINIC_FOLLOW_UP_TASK, followUp.code?.coding?.single()?.code?.value)
    assertEquals("Organization/facility", followUp.owner?.reference?.value)
    assertEquals(dueOn.toString(), followUp.restriction?.period?.end?.value?.toString())
    val referral = repository.all("ServiceRequest").filterIsInstance<ServiceRequest>().single()
    assertEquals(ServiceRequest.RequestStatus.Completed, referral.status.value)
    assertEquals("ServiceRequest/${referral.id}", followUp.focus?.reference?.value)
    assertTrue(repository.referralInbox("facility").isEmpty())
  }
}

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

import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.vaxlink.data.hcert.HcertService
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.data.terminology.BundledTerminology
import dev.ohs.vaxlink.hcert.DoseProposal
import dev.ohs.vaxlink.hcert.InMemoryTrustListStorage
import dev.ohs.vaxlink.hcert.ReadResult
import dev.ohs.vaxlink.hcert.TrustListCache
import dev.ohs.vaxlink.hcert.UnverifiedHcert
import dev.ohs.vaxlink.util.FhirJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * J2 on the worked example (TEST DATA ONLY, DEV participant XXR): the CHW scans the HepB and BCG
 * cards of a newborn and records them.
 */
class CertificateRecorderTest {
  private fun resource(path: String) =
    checkNotNull(CertificateRecorderTest::class.java.getResource(path)) { path }.readText()

  private val certificates =
    Json.parseToJsonElement(resource("/hcert/dvc-qr-example-XXR.json"))
      .jsonObject["certificates"]!!
      .jsonObject

  private fun hc1(name: String) = certificates[name]!!.jsonObject["hc1"]!!.jsonPrimitive.content

  private val repository = InMemorySampleFhirRepository()
  private val hcert = HcertService(TrustListCache(InMemoryTrustListStorage()))
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
    CertificateRecorder(repository, BundledTerminology(), schedule, now = { 1_792_700_000 })

  private suspend fun baby(): Patient =
    FhirJson.instance
      .decodeFromString(
        Resource.serializer(),
        """{"resourceType":"Patient","id":"baby","name":[{"given":["Chanthala"],"family":"SOUVANNALATH"}],
            "gender":"female","birthDate":"2026-10-21"}""",
      )
      .also { repository.upsert(it) } as Patient

  private suspend fun decoded(hc1: String): UnverifiedHcert =
    assertIs<ReadResult.Decoded>(hcert.read(hc1)).hcert

  private suspend fun task(dose: String): Task =
    repository.all("Task").filterIsInstance<Task>().single {
      it.instantiatesCanonical?.value?.endsWith("LaoEpi-$dose") == true
    }

  @Test
  fun aHepBCardGetsTheLaoCodingAndTicksHb0() = runTest {
    baby()
    val hc1 = hc1("hepb1")

    val outcome = assertIs<RecordOutcome.Recorded>(recorder.record("baby", hc1, decoded(hc1)))

    assertEquals("HB0-24H", assertIs<DoseProposal.Mapped>(outcome.proposal).code)
    val stored = repository.all("Immunization").filterIsInstance<Immunization>().single()
    assertEquals("Patient/baby", stored.patient.reference?.value)
    assertEquals(false, stored.primarySource?.value)
    assertEquals("HEPB-2026-0914", stored.lotNumber?.value)
    val codings = stored.vaccineCode.coding
    assertEquals(2, codings.size)
    assertEquals("a3K3X000006MiiXUAS", codings[0].code?.value)
    assertEquals(LAO_DOSE_SYSTEM to "HB0-24H", codings[1].system?.value to codings[1].code?.value)
    assertEquals(
      "automatic",
      codings[1]
        .extension
        .single { it.url == CODING_ORIGIN_EXTENSION }
        .value
        ?.asCode()
        ?.value
        ?.value,
    )
    assertEquals(Task.TaskStatus.Completed, task("hb0").status.value)
    assertEquals(
      "Immunization/${stored.id}",
      task("hb0").output.single().value.asReference()?.value?.reference?.value,
    )
    assertEquals(19, repository.all("Task").size)
    // The Patient the mapper builds from the card is not stored; the registered child stays.
    assertEquals(listOf("baby"), repository.all("Patient").map { it.id })
  }

  @Test
  fun aBcgCardIsStoredAsAForeignDoseAndTicksNothing() = runTest {
    baby()
    val hc1 = hc1("bcg")

    val outcome = assertIs<RecordOutcome.Recorded>(recorder.record("baby", hc1, decoded(hc1)))

    assertIs<DoseProposal.Unmatched>(outcome.proposal)
    val stored = repository.all("Immunization").filterIsInstance<Immunization>().single()
    assertEquals(listOf("a3K3X000006MiiCUAS"), stored.vaccineCode.coding.map { it.code?.value })
    assertNull(stored.vaccineCode.coding.firstOrNull { it.system?.value == LAO_DOSE_SYSTEM })
    assertTrue(
      repository.all("Task").filterIsInstance<Task>().all {
        it.status.value == Task.TaskStatus.Requested
      }
    )
  }

  @Test
  fun theSameCardIsRefusedTheSecondTime() = runTest {
    baby()
    val hc1 = hc1("hepb1")
    val first = assertIs<RecordOutcome.Recorded>(recorder.record("baby", hc1, decoded(hc1)))

    val second = recorder.record("baby", hc1, decoded(hc1))

    assertEquals(RecordOutcome.AlreadyRecorded(first.immunization.id!!), second)
    assertEquals(1, repository.all("Immunization").size)
  }
}

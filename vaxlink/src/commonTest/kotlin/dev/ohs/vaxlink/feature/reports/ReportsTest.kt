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
package dev.ohs.vaxlink.feature.reports

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.DOSE_CONFIRMED_ACTIVITY
import dev.ohs.vaxlink.workflow.HCERT_FINGERPRINT_SYSTEM
import dev.ohs.vaxlink.workflow.LAO_DOSE_SYSTEM
import dev.ohs.vaxlink.workflow.LAO_SCHEDULE_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import dev.ohs.vaxlink.workflow.REFERRAL_CODE
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ReportsTest {
  private val today = LocalDate(2026, 11, 20)
  private val placements = mapOf("act/penta-1" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 42))

  private suspend fun InMemorySampleFhirRepository.add(json: String) {
    upsert(FhirJson.instance.decodeFromString(Resource.serializer(), json))
  }

  private suspend fun fixture(): InMemorySampleFhirRepository {
    val repo = InMemorySampleFhirRepository()
    repo.add(
      """{"resourceType":"Group","id":"h1","type":"person","actual":true,
      "code":{"coding":[{"system":"http://hl7.org/fhir/group-code","code":"household"}]}}"""
    )
    // Mother, a baby of 20 weeks with no Penta, a baby of 5 weeks, and a four-year-old with Penta.
    repo.add("""{"resourceType":"Patient","id":"mother","birthDate":"1999-03-01"}""")
    repo.add("""{"resourceType":"Patient","id":"zero","birthDate":"2026-06-28"}""")
    repo.add("""{"resourceType":"Patient","id":"newborn","birthDate":"2026-10-14"}""")
    repo.add("""{"resourceType":"Patient","id":"dosed","birthDate":"2022-05-01"}""")
    repo.add(
      """{"resourceType":"Immunization","id":"i-penta","status":"completed",
      "patient":{"reference":"Patient/dosed"},"occurrenceDateTime":"2026-11-03",
      "vaccineCode":{"coding":[{"system":"$LAO_DOSE_SYSTEM","code":"PENTA-1"}]}}"""
    )
    // A foreign BCG, verified at the clinic and still waiting for the nurse's decision.
    repo.add(
      """{"resourceType":"Immunization","id":"i-bcg","status":"completed","primarySource":true,
      "patient":{"reference":"Patient/newborn"},"occurrenceDateTime":"2026-10-14",
      "recorded":"2026-11-02T08:00:00Z",
      "identifier":[{"system":"$HCERT_FINGERPRINT_SYSTEM","value":"abc"}],
      "vaccineCode":{"coding":[{"system":"urn:prequal","code":"BCG"}]}}"""
    )
    // A foreign HepB, already counted by the nurse.
    repo.add(
      """{"resourceType":"Immunization","id":"i-hep","status":"completed","primarySource":true,
      "patient":{"reference":"Patient/newborn"},"occurrenceDateTime":"2026-10-14",
      "recorded":"2026-10-30T08:00:00Z",
      "identifier":[{"system":"$HCERT_FINGERPRINT_SYSTEM","value":"def"}],
      "vaccineCode":{"coding":[{"system":"urn:prequal","code":"HEPB"}]}}"""
    )
    repo.add(
      """{"resourceType":"Provenance","id":"pv","target":[{"reference":"Immunization/i-hep"}],
      "recorded":"2026-11-02T09:00:00Z","activity":{"text":"$DOSE_CONFIRMED_ACTIVITY"},
      "agent":[{"who":{"reference":"Practitioner/n"}}]}"""
    )
    repo.add(
      """{"resourceType":"CarePlan","id":"anc","status":"active","intent":"plan",
      "instantiatesCanonical":["$ANC_PLAN"],"subject":{"reference":"Patient/mother"},
      "period":{"start":"2026-11-04"}}"""
    )
    repo.add(
      """{"resourceType":"CarePlan","id":"pnc","status":"completed","intent":"plan",
      "instantiatesCanonical":["$PNC_PLAN"],"subject":{"reference":"Patient/mother"},
      "period":{"start":"2026-10-01"}}"""
    )
    repo.add(
      """{"resourceType":"Task","id":"t1","status":"requested","intent":"plan",
      "instantiatesCanonical":"act/penta-1","for":{"reference":"Patient/newborn"},
      "restriction":{"period":{"end":"2026-11-30"}}}"""
    )
    repo.add(
      """{"resourceType":"Task","id":"t2","status":"requested","intent":"plan",
      "instantiatesCanonical":"act/penta-1","for":{"reference":"Patient/zero"},
      "restriction":{"period":{"end":"2026-11-10"}}}"""
    )
    repo.add(
      """{"resourceType":"Task","id":"t3","status":"completed","intent":"plan",
      "instantiatesCanonical":"act/penta-1","for":{"reference":"Patient/dosed"},
      "restriction":{"period":{"end":"2026-11-05"}}}"""
    )
    listOf("0" to "ok1", "0" to "ok2", "4" to "bad").forEach { (outcome, id) ->
      repo.add(
        """{"resourceType":"AuditEvent","id":"$id","outcome":"$outcome",
        "type":{"system":"http://dicom.nema.org/resources/ontology/DCM","code":"110114"},
        "recorded":"2026-11-05T10:00:00Z","agent":[{"requestor":true}],
        "source":{"observer":{"display":"app"}}}"""
      )
    }
    fun referral(id: String, status: String, authored: String) =
      """{"resourceType":"ServiceRequest","id":"$id","status":"$status","intent":"order",
        "code":{"coding":[{"system":"http://snomed.info/sct","code":"$REFERRAL_CODE"}]},
        "subject":{"reference":"Patient/newborn"},"authoredOn":"$authored"}"""
    repo.add(referral("r1", "active", "2026-11-02T08:00:00Z"))
    repo.add(referral("r2", "completed", "2026-11-03T08:00:00Z"))
    repo.add(referral("r3", "revoked", "2026-10-03T08:00:00Z"))
    return repo
  }

  @Test
  fun countsThePositionAndTheMonth() = runTest {
    val counts = fixture().reportCounts("2026-11", today, placements)

    assertEquals(1, counts[ReportMetric.Households])
    assertEquals(4, counts[ReportMetric.Patients])
    assertEquals(3, counts[ReportMetric.ChildrenUnderFive])
    // Only the 20-week-old: the newborn is too young, the four-year-old has a Penta dose.
    assertEquals(1, counts[ReportMetric.ZeroDose])
    assertEquals(1, counts[ReportMetric.AncClients])
    assertEquals(1, counts[ReportMetric.AncNew])
    assertEquals(0, counts[ReportMetric.PncClients])
    assertEquals(0, counts[ReportMetric.PncNew])
    assertEquals(1, counts[ReportMetric.DosesGiven])
    assertEquals(2, counts[ReportMetric.DosesDue])
    assertEquals(1, counts[ReportMetric.DosesOverdue])
    assertEquals(1, counts[ReportMetric.CertificatesScanned])
    assertEquals(2, counts[ReportMetric.CertificatesVerified])
    assertEquals(1, counts[ReportMetric.VerificationsFailed])
    assertEquals(1, counts[ReportMetric.ForeignDosesVerified])
    assertEquals(1, counts[ReportMetric.BcgDecisionsPending])
    assertEquals(1, counts[ReportMetric.ReferralsOpen])
    assertEquals(2, counts[ReportMetric.ReferralsSent])
    assertEquals(1, counts[ReportMetric.ReferralsCompleted])
    assertEquals(0, counts[ReportMetric.ReferralsWithdrawn])
  }

  @Test
  fun anotherMonthMovesOnlyTheMonthlyCounters() = runTest {
    val counts = fixture().reportCounts("2026-10", today, placements)

    assertEquals(1, counts[ReportMetric.Households])
    assertEquals(1, counts[ReportMetric.AncClients])
    assertEquals(0, counts[ReportMetric.AncNew])
    assertEquals(1, counts[ReportMetric.PncNew])
    assertEquals(1, counts[ReportMetric.CertificatesScanned])
    assertEquals(0, counts[ReportMetric.CertificatesVerified])
    assertEquals(1, counts[ReportMetric.ReferralsWithdrawn])
    assertEquals(1, counts[ReportMetric.BcgDecisionsPending])
  }

  @Test
  fun everyMetricHasACount() = runTest {
    val counts = InMemorySampleFhirRepository().reportCounts("2026-11", today, placements)
    assertEquals(ReportMetric.entries.toSet(), counts.keys)
    assertEquals(setOf(0), counts.values.toSet())
  }
}

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
package dev.ohs.vaxlink.data.repository

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.vaxlink.feature.patient.CareStatus
import dev.ohs.vaxlink.feature.patient.careStatus
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.util.calendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PatientCareRecordTest {
  private val repository = InMemorySampleFhirRepository()
  private val patients = PatientRepository(repository)

  private suspend fun store(vararg json: String) =
    json.forEach {
      repository.upsert(FhirJson.instance.decodeFromString(Resource.serializer(), it))
    }

  private suspend fun alfie() =
    store(
      """{"resourceType":"Patient","id":"alfie","name":[{"given":["Alfie"],"family":"Sintamei"}]}""",
      """{"resourceType":"ServiceRequest","id":"old","status":"completed","intent":"order","priority":"urgent",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"3457005"}]},
         "subject":{"reference":"Patient/alfie"},"authoredOn":"2026-09-01T08:00:00Z"}""",
      """{"resourceType":"ServiceRequest","id":"new","status":"active","intent":"order","priority":"urgent",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"3457005"}]},
         "subject":{"reference":"Patient/alfie"},"authoredOn":"2026-10-05T08:00:00Z"}""",
      """{"resourceType":"ServiceRequest","id":"proposal","status":"completed","intent":"proposal",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"3457005"}]},
         "subject":{"reference":"Patient/alfie"},"authoredOn":"2026-10-05T08:00:00Z"}""",
      """{"resourceType":"MedicationRequest","id":"amox","status":"active","intent":"proposal",
         "medicationCodeableConcept":{"text":"Amoxicillin 250 mg dispersible"},
         "subject":{"reference":"Patient/alfie"},"authoredOn":"2026-09-01T08:00:00Z",
         "dosageInstruction":[{"text":"Twice daily for 5 days"}]}""",
      """{"resourceType":"Task","id":"fu","status":"requested","intent":"proposal",
         "code":{"coding":[{"system":"http://ohs.dev/fhir/CodeSystem/task-type","code":"iccm-follow-up"}]},
         "description":"Follow up after facility visit: Discharged","for":{"reference":"Patient/alfie"},
         "focus":{"reference":"ServiceRequest/old"},"restriction":{"period":{"end":"2026-09-03"}}}""",
      """{"resourceType":"Procedure","id":"proc","status":"completed","subject":{"reference":"Patient/alfie"},
         "basedOn":[{"reference":"ServiceRequest/old"}],"outcome":{"text":"Discharged"}}""",
      """{"resourceType":"Encounter","id":"enc","status":"finished","class":{"code":"AMB"},
         "subject":{"reference":"Patient/alfie"},"reasonCode":[{"text":"Fast breathing"}],
         "period":{"start":"2026-09-02T09:00:00Z"}}""",
      """{"resourceType":"Observation","id":"spo2","status":"final",
         "code":{"coding":[{"system":"http://loinc.org","code":"59408-5","display":"Oxygen saturation"}]},
         "subject":{"reference":"Patient/alfie"},"effectiveDateTime":"2026-09-02T09:05:00Z",
         "valueQuantity":{"value":95,"unit":"%"}}""",
    )

  @Test
  fun listRowCarriesTheLatestReferralAndOpenFollowUp() = runTest {
    alfie()

    val row = patients.getPatients().single()

    assertEquals("new" to "active", row.referralId to row.referralStatus)
    assertEquals("requested" to "ServiceRequest/old", row.followUpStatus to row.followUpFocus)
  }

  @Test
  fun profileShowsEveryCareActivity() = runTest {
    alfie()

    val profile = patients.getPatientProfile("alfie")

    assertEquals(
      listOf("2026-10-05", "2026-09-01"),
      profile.referrals.map { it.referredOn.calendarDate() },
    )
    assertEquals(listOf("Discharged"), profile.outcomes.map { it.outcomeText })
    assertEquals(listOf("Amoxicillin 250 mg dispersible"), profile.treatments.map { it.medicine })
    assertEquals(listOf("2026-09-03"), profile.followUps.map { it.dueOn.calendarDate() })
    assertEquals(listOf("Fast breathing"), profile.visits.map { it.visitReason })
    assertEquals(
      listOf("Oxygen saturation 95 %"),
      profile.vitals.map { "${it.vitalName} ${it.vitalValue} ${it.vitalUnit}" },
    )
  }

  private suspend fun chanthala() =
    store(
      """{"resourceType":"Patient","id":"baby","name":[{"given":["Chanthala"],"family":"SOUVANNALATH"}],
         "gender":"female","birthDate":"2026-10-21"}""",
      """{"resourceType":"Patient","id":"mali","name":[{"given":["Mali"],"family":"SOUVANNALATH"}],"gender":"female"}""",
      """{"resourceType":"Group","id":"hh","type":"person","actual":true,"active":true,"name":"Souvannalath household",
         "code":{"coding":[{"system":"http://hl7.org/fhir/group-code","code":"household"}]},
         "characteristic":[{"code":{"coding":[{"system":"http://smartregister.org/fhir/CodeSystem/household","code":"village"}]},
           "valueCodeableConcept":{"text":"Ban Nongbuathong"},"exclude":false}],
         "member":[{"entity":{"reference":"Patient/mali"},"extension":[{"url":"http://smartregister.org/fhir/StructureDefinition/group-member-involvement",
           "valueCodeableConcept":{"coding":[{"system":"http://hl7.org/fhir/group-involvement","code":"primary-contact"}]}}]},
           {"entity":{"reference":"Patient/baby"}}]}""",
      """{"resourceType":"RelatedPerson","id":"rp-mother","patient":{"reference":"Patient/baby"},
         "relationship":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-RoleCode","code":"MTH","display":"Mother"}]}],
         "name":[{"text":"Mali SOUVANNALATH"}],
         "extension":[{"url":"https://nawitech.github.io/vaxlink-ig/StructureDefinition/related-patient","valueReference":{"reference":"Patient/mali"}}]}""",
      """{"resourceType":"Immunization","id":"imm-hepb","status":"completed","primarySource":false,"lotNumber":"HEPB-2026-0914",
         "identifier":[{"system":"http://ohs.dev/vaxlink/identifier/hcert-sha256","value":"abc"}],
         "vaccineCode":{"text":"Hepatitis B Vaccine (rDNA) (Paediatric)","coding":[
           {"system":"http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds","code":"a3K3X000006MiiXUAS"},
           {"system":"https://nawitech.github.io/vaxlink-ig/CodeSystem/lao-epi-schedule-dose","code":"HB0-24H","display":"HB0 <24 Hours"}]},
         "patient":{"reference":"Patient/baby"},"occurrenceDateTime":"2026-10-21"}""",
      """{"resourceType":"Immunization","id":"imm-bcg","status":"completed","primarySource":false,"lotNumber":"BCG-2026-0477",
         "vaccineCode":{"text":"BCG Vaccine","coding":[
           {"system":"http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds","code":"a3K3X000006MiiCUAS"}]},
         "patient":{"reference":"Patient/baby"},"occurrenceDateTime":"2026-10-22"}""",
      """{"resourceType":"Task","id":"task-hb0","status":"completed","intent":"plan",
         "instantiatesCanonical":"https://nawitech.github.io/vaxlink-ig/ActivityDefinition/LaoEpi-hb0",
         "code":{"text":"HB0","coding":[{"system":"https://nawitech.github.io/vaxlink-ig/CodeSystem/lao-epi-schedule-dose","code":"HB0-24H"}]},
         "for":{"reference":"Patient/baby"},"restriction":{"period":{"start":"2026-10-21","end":"2026-10-28"}}}""",
      """{"resourceType":"Task","id":"task-penta1","status":"requested","intent":"plan",
         "instantiatesCanonical":"https://nawitech.github.io/vaxlink-ig/ActivityDefinition/LaoEpi-penta-1",
         "code":{"text":"Penta 1"},"for":{"reference":"Patient/baby"},
         "restriction":{"period":{"start":"2026-11-25","end":"2026-12-30"}}}""",
      """{"resourceType":"Condition","id":"cond","clinicalStatus":{"coding":[{"code":"active"}]},
         "code":{"text":"Low birth weight"},"subject":{"reference":"Patient/baby"},"recordedDate":"2026-10-21"}""",
      """{"resourceType":"AllergyIntolerance","id":"allergy","criticality":"high","code":{"text":"Penicillin"},
         "patient":{"reference":"Patient/baby"},"recordedDate":"2026-10-22"}""",
      """{"resourceType":"MedicationStatement","id":"ms","status":"active",
         "medicationCodeableConcept":{"text":"Vitamin K"},"subject":{"reference":"Patient/baby"},"dateAsserted":"2026-10-21"}""",
    )

  @Test
  fun theSummaryProfileCarriesEverySectionForANewborn() = runTest {
    chanthala()

    val profile = patients.getPatientProfile("baby")

    assertEquals(
      "Souvannalath household" to "Ban Nongbuathong",
      profile.household?.householdName to profile.household?.village,
    )
    assertEquals("mali", profile.household?.headId)
    assertEquals(
      listOf("Mali SOUVANNALATH" to "Mother"),
      profile.relationships.map { it.relatedName to it.relationshipLabel },
    )
    assertEquals(listOf("imm-bcg", "imm-hepb"), profile.immunizations.map { it.immunizationId })
    val hepb = profile.immunizations.single { it.immunizationId == "imm-hepb" }
    assertEquals("Hepatitis B Vaccine (rDNA) (Paediatric)", hepb.vaccineName)
    assertEquals("2026-10-21", hepb.givenOn.calendarDate())
    assertEquals("HEPB-2026-0914", hepb.lotNumber)
    assertEquals(false, hepb.verified)
    assertEquals("HB0-24H" to "HB0 <24 Hours", hepb.laoDoseCode to hepb.laoDoseDisplay)
    assertEquals("abc", hepb.certificateFingerprint)
    val bcg = profile.immunizations.single { it.immunizationId == "imm-bcg" }
    assertEquals("a3K3X000006MiiCUAS", bcg.preQualCode)
    assertEquals(null, bcg.laoDoseCode)
    assertEquals(
      listOf("Low birth weight" to "active"),
      profile.conditions.map { it.conditionName to it.clinicalStatus },
    )
    assertEquals(
      listOf("Penicillin" to "high"),
      profile.allergies.map { it.allergyName to it.allergyCriticality },
    )
    assertEquals(listOf("Vitamin K"), profile.medicationStatements.map { it.statementMedicine })
    assertEquals(
      listOf("HB0" to "completed", "Penta 1" to "requested"),
      profile.careTasks.map { it.taskTitle to it.taskStatus },
    )
    assertEquals("2026-12-30", profile.careTasks[1].dueBy.calendarDate())
    assertEquals(true, profile.needsVerification)
    assertEquals(false, profile.referred)
    assertEquals(emptyList(), profile.followUps)
    val row = patients.getPatients().single { it.patientId == "baby" }
    assertEquals(CareStatus.NeedsVerification, row.careStatus())
  }

  @Test
  fun theRegisterReadsEachTableOncePerRefresh() = runTest {
    store(
      """{"resourceType":"Patient","id":"a"}""",
      """{"resourceType":"Patient","id":"b"}""",
      """{"resourceType":"Patient","id":"c"}""",
    )
    val reads = mutableMapOf<String, Int>()
    val counting =
      object : FhirRepository by repository {
        override suspend fun all(resourceType: String): List<Resource> {
          reads[resourceType] = (reads[resourceType] ?: 0) + 1
          return repository.all(resourceType)
        }
      }

    assertEquals(3, PatientRepository(counting).getPatients().size)
    assertEquals(
      mapOf("Patient" to 1, "ServiceRequest" to 1, "Task" to 1, "Immunization" to 1),
      reads.toMap(),
    )
  }
}

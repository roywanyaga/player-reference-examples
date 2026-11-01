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

import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.Integer
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.data.datasource.SampleFamily
import dev.ohs.vaxlink.util.calendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** What each ported eCHIS form produces, in the shape the extraction templates describe. */
class ProgrammeRecordsTest {
  private val today = LocalDate(2026, 11, 1)

  private fun Observation.code() = code.coding.first().code?.value

  private fun Observation.component(linkId: String) =
    component.firstOrNull { it.code.coding.first().code?.value == linkId }?.value

  @Test
  fun anAncHistoryWithItsOutcomeMakesTheEndedPlanTheResolvedPregnancyAndTheContacts() {
    val record = SampleFamily.motherAncHistory(today)

    val plan = record.carePlans.single()
    assertEquals(ANC_PLAN, plan.instantiatesCanonical.single().value)
    assertEquals(CarePlan.RequestStatus.Completed, plan.status.value)
    assertEquals("2026-01-18", plan.period?.start?.value.calendarDate())
    assertEquals("2026-10-25", plan.period?.end?.value.calendarDate())

    val pregnancy = record.resources.filterIsInstance<Condition>().single()
    assertEquals(PREGNANCY_CONDITION_CODE, pregnancy.code?.coding?.single()?.code?.value)
    assertEquals("resolved", pregnancy.clinicalStatus?.coding?.single()?.code?.value)
    assertEquals("Patient/${SampleFamily.MOTHER_ID}", pregnancy.subject.reference?.value)

    val screening = record.observations.single { it.code() == "anc-screening" }
    assertEquals(
      "2026-01-18",
      (screening.component(LMP_LOINC) as Observation.Component.Value.DateTime)
        .value
        .value
        .calendarDate(),
    )
    val contacts = record.observations.filter { it.code() == ANC_CONTACT_CODE }
    assertEquals(4, contacts.size)
    // The last contact on the date the form gives; the earlier ones on the plan's contact dates.
    assertEquals(
      listOf("2026-04-12", "2026-06-07", "2026-07-19", "2026-09-13"),
      contacts.map { it.effective?.asDateTime()?.value?.value.calendarDate() },
    )
    val delivery = record.observations.single { it.code() == DELIVERY_CODE }
    assertEquals("2026-10-25", delivery.effective?.asDateTime()?.value?.value.calendarDate())
    assertEquals(
      "Thailand",
      (delivery.component("delivery-abroad") as Observation.Component.Value.String).value.value,
    )
    assertTrue(record.resources.none { it is ServiceRequest })
  }

  @Test
  fun anOngoingPregnancyKeepsThePlanActiveWithTheLmpFromTheWeeksGiven() {
    val record =
      enrolAnc(
        response(
          "lmp-known" to coding(V2_YES_NO_SYSTEM, "no"),
          "weeks-since-lmp" to int(20),
          "anc-visits" to int(0),
          "referred-for-card" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        "mother",
        today,
      )

    val plan = record.carePlans.single()
    assertEquals(CarePlan.RequestStatus.Active, plan.status.value)
    assertEquals("2026-06-14", plan.period?.start?.value.calendarDate())
    assertNull(plan.period?.end)
    assertEquals(
      "active",
      record.resources
        .filterIsInstance<Condition>()
        .single()
        .clinicalStatus
        ?.coding
        ?.single()
        ?.code
        ?.value,
    )
    assertTrue(record.observations.none { it.code() == ANC_CONTACT_CODE })
    val referral = record.resources.filterIsInstance<ServiceRequest>().single()
    assertEquals(REFERRAL_CODE, referral.code?.coding?.single()?.code?.value)
  }

  @Test
  fun anAncContactCarriesEveryAnswerAsAComponent() {
    val record =
      recordAncContact(
        response(
          "date" to date("2026-09-13"),
          "facility-visit" to coding(V2_YES_NO_SYSTEM, "yes"),
          "muac" to Value.Decimal(Decimal(value = FhirDecimal.fromString("24.5"))),
          "danger-signs-present" to coding(V2_YES_NO_SYSTEM, "yes"),
          "referred" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        "mother",
        today,
      )

    val contact = record.observations.single()
    assertEquals(ANC_CONTACT_CODE, contact.code())
    assertEquals("2026-09-13", contact.effective?.asDateTime()?.value?.value.calendarDate())
    assertEquals(ANC_SCREENING_SYSTEM, contact.component.first().code.coding.single().system?.value)
    assertNotNull(contact.component("muac"))
    assertNull(contact.component("date"))
    assertEquals(
      "yes",
      (contact.component("referred") as Observation.Component.Value.CodeableConcept)
        .value
        .coding
        .single()
        .code
        ?.value,
    )
    assertEquals(1, record.resources.filterIsInstance<ServiceRequest>().size)
  }

  @Test
  fun aPncContactRecordsTheBabysWeightAsAVitalSign() {
    val record =
      recordPncContact(
        response(
          "date" to date("2026-10-27"),
          "baby-weight" to Value.Decimal(Decimal(value = FhirDecimal.fromString("3.2"))),
        ),
        "baby",
        today,
      )

    assertEquals(PNC_CONTACT_CODE, record.observations.first().code())
    val weight = record.observations.single { it.code() == "29463-7" }
    assertEquals("kg", (weight.value as Observation.Value.Quantity).value.unit?.value)
  }

  @Test
  fun theFamilyPlanningMethodPicksThePlan() {
    assertEquals(FP_INJECTABLE_PLAN, fpPlanFor("709457002"))
    assertEquals(FP_ORAL_PLAN, fpPlanFor("169467008"))
    assertEquals(FP_LONGTERM_PLAN, fpPlanFor("312081001"))
    assertNull(fpPlanFor("unknown"))

    val notInterested =
      enrolFp(
        response(
          "interested" to coding(V2_YES_NO_SYSTEM, "no"),
          "method" to coding(SNOMED, "312081001"),
        ),
        "mother",
        today,
      )
    assertTrue(notInterested.carePlans.isEmpty())
    assertEquals(FP_ASSESSMENT_CODE, notInterested.observations.single().code())
  }

  @Test
  fun aFamilyPlanningVisitRecordsTheMethodAndTheInjectionGiven() {
    val record =
      recordFpVisit(
        response(
          "date" to date("2026-10-30"),
          "method" to coding(SNOMED, "709457002", "Injectable"),
          "sayana-press-administered" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        "mother",
        today,
      )

    val visit = record.observations.single { it.code() == FP_VISIT_CODE }
    assertEquals(
      "709457002",
      (visit.component(FP_METHOD_LOINC) as Observation.Component.Value.CodeableConcept)
        .value
        .coding
        .single()
        .code
        ?.value,
    )
    val injection = record.observations.single { it.code() == FP_INJECTION_CODE }
    assertEquals("2026-10-30", injection.effective?.asDateTime()?.value?.value.calendarDate())
  }

  @Test
  fun aWashCheckIsOneObservationOnTheHousehold() {
    val record =
      recordWashCheck(
        response(
          "date" to date("2026-11-01"),
          "safe-water-source" to Value.Boolean(FhirBoolean(value = true)),
          "latrine" to Value.Boolean(FhirBoolean(value = false)),
        ),
        "hh",
        today,
      )

    val check = record.observations.single()
    assertEquals(WASH_CHECK_CODE, check.code())
    assertEquals("Group/hh", check.subject?.reference?.value)
    assertEquals(
      false,
      (check.component("latrine") as Observation.Component.Value.Boolean).value.value,
    )
  }

  private fun response(vararg answers: Pair<String, Value>) =
    QuestionnaireResponse(
      status = Enumeration(value = QuestionnaireResponse.QuestionnaireResponseStatus.Completed),
      item =
        answers.map { (linkId, value) ->
          QuestionnaireResponse.Item(
            linkId = FhirString(value = linkId),
            answer = listOf(QuestionnaireResponse.Item.Answer(value = value)),
          )
        },
    )

  private fun coding(system: String, code: String, display: String? = null) =
    Value.Coding(
      Coding(
        system = Uri(value = system),
        code = Code(value = code),
        display = display?.let { FhirString(value = it) },
      )
    )

  private fun date(value: String) = Value.Date(Date(value = FhirDate.Date(LocalDate.parse(value))))

  private fun int(value: Int) = Value.Integer(Integer(value = value))
}

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
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** The eCHIS household model from the three registration forms. */
class HouseholdRecordsTest {
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

  private fun text(value: String) = Value.String(FhirString(value = value))

  private fun coding(code: String, display: String? = null) =
    Value.Coding(
      Coding(code = Code(value = code), display = display?.let { FhirString(value = it) })
    )

  private fun date(value: String) = Value.Date(Date(value = FhirDate.Date(LocalDate.parse(value))))

  private val householdForm =
    response(
      "household-name" to text("Souvannalath household"),
      "village" to text("Ban Nongbuathong"),
      "head-given-name" to text("Khamsing"),
      "head-family-name" to text("SOUVANNALATH"),
      "head-sex" to coding("male"),
      "head-birth-date" to date("1998-03-14"),
      "head-phone" to text("+856 20 5555 1234"),
    )

  @Test
  fun registeringAHouseholdMakesTheGroupWithTheFatherAsHead() {
    val registration = registerHousehold(householdForm, "org-1")

    val head = registration.head
    assertEquals(
      "Khamsing SOUVANNALATH",
      "${head.name.single().given.single().value} " + "${head.name.single().family?.value}",
    )
    assertEquals(AdministrativeGender.Male, head.gender?.value)
    assertEquals("Organization/org-1", head.managingOrganization?.reference?.value)
    assertEquals("+856 20 5555 1234", head.telecom.single().value?.value)

    val household = registration.household
    assertEquals(Group.GroupType.Person, household.type.value)
    assertEquals(true, household.actual.value)
    assertEquals("Souvannalath household", household.name?.value)
    assertEquals("Organization/org-1", household.managingEntity?.reference?.value)
    assertEquals(
      GROUP_CODE_SYSTEM to "household",
      household.code?.coding?.single()?.let { it.system?.value to it.code?.value },
    )
    val village = household.characteristic.single()
    assertEquals("village", village.code.coding.single().code?.value)
    assertEquals(
      "Ban Nongbuathong",
      assertNotNull(village.value.asCodeableConcept()).value.text?.value,
    )
    assertEquals(listOf(head.id), household.memberIds())
    assertEquals(head.id, household.headId())
    assertTrue(household.member.single().isHead())
  }

  @Test
  fun aMemberJoinsTheHouseholdAndIsRelatedToTheHead() {
    val (household, head) = registerHousehold(householdForm, "org-1")
    val form =
      response(
        "given-name" to text("Mali"),
        "family-name" to text("SOUVANNALATH"),
        "sex" to coding("female"),
        "birth-date" to date("2001-07-22"),
        "relationship-to-head" to coding("SPS", "Spouse"),
      )

    val registration = registerMember(form, household, head, "org-1")

    assertEquals(listOf(head.id, registration.member.id), registration.household.memberIds())
    assertEquals(head.id, registration.household.headId())
    val relation = assertNotNull(registration.relationToHead)
    assertEquals("Patient/${registration.member.id}", relation.patient.reference?.value)
    assertEquals("SPS", relation.relationship.single().coding.single().code?.value)
    assertEquals("Khamsing SOUVANNALATH", relation.name.single().text?.value)
    assertEquals(
      "Patient/${head.id}",
      relation.extension
        .single { it.url == RELATED_PATIENT_EXTENSION }
        .value
        ?.asReference()
        ?.value
        ?.reference
        ?.value,
    )
  }

  @Test
  fun aNewbornLinksBothParentsAndTakesTheMothersFamilyName() {
    val (household, father) = registerHousehold(householdForm, "org-1")
    val mother =
      registerMember(
          response(
            "given-name" to text("Mali"),
            "family-name" to text("SOUVANNALATH"),
            "sex" to coding("female"),
            "relationship-to-head" to coding("SPS"),
          ),
          household,
          father,
          "org-1",
        )
        .member
    val form =
      response(
        "given-name" to text("Chanthala"),
        "sex" to coding("female"),
        "birth-date" to date("2026-10-21"),
      )

    val registration =
      registerNewborn(form, household.withMember(mother.id!!), mother, father, "org-1")

    val child = registration.child
    assertEquals("SOUVANNALATH", child.name.single().family?.value)
    assertEquals("2026-10-21", (child.birthDate?.value as FhirDate.Date).date.toString())
    assertEquals(listOf(father.id, mother.id, child.id), registration.household.memberIds())
    assertEquals(
      listOf("MTH" to "Patient/${mother.id}", "FTH" to "Patient/${father.id}"),
      registration.parents.map { parent ->
        parent.relationship.single().coding.single().code?.value to
          parent.extension
            .single { it.url == RELATED_PATIENT_EXTENSION }
            .value
            ?.asReference()
            ?.value
            ?.reference
            ?.value
      },
    )
    assertTrue(registration.parents.all { it.patient.reference?.value == "Patient/${child.id}" })
  }

  @Test
  fun addingAMemberTwiceKeepsOneEntry() {
    val (household, head) = registerHousehold(householdForm, "org-1")

    assertEquals(household, household.withMember(head.id!!))
  }
}

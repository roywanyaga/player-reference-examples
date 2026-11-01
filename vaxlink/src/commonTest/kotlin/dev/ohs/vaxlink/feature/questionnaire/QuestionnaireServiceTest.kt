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
package dev.ohs.vaxlink.feature.questionnaire

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.workflow.headId
import dev.ohs.vaxlink.workflow.memberIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class QuestionnaireServiceTest {
  private val repository = InMemorySampleFhirRepository()
  private val user = UserContext(AppRole.CHW, "p1", "org-1", null)
  private val service = QuestionnaireService(repository)

  private fun registration(given: String) =
    QuestionnaireResponse(
      status = Enumeration(value = QuestionnaireResponse.QuestionnaireResponseStatus.Completed),
      item =
        listOf(
          QuestionnaireResponse.Item(
            linkId = FhirString(value = "given"),
            answer =
              listOf(
                QuestionnaireResponse.Item.Answer(value = Value.String(FhirString(value = given)))
              ),
          )
        ),
    )

  private fun form(vararg answers: Pair<String, Value>) =
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

  private fun coding(code: String) = Value.Coding(Coding(code = Code(value = code)))

  private suspend fun registerSouvannalath(): Group {
    val questionnaire = service.getQuestionnaire(QuestionnaireIds.REGISTER_HOUSEHOLD)
    val result =
      service.submit(
        questionnaire,
        form(
          "household-name" to text("Souvannalath household"),
          "head-given-name" to text("Khamsing"),
          "head-family-name" to text("SOUVANNALATH"),
          "head-sex" to coding("male"),
        ),
        QuestionnaireLaunchContext(user = user),
      )
    assertEquals("Household registered.", result.successMessage)
    return repository.all("Group").single() as Group
  }

  @Test
  fun registeringAHouseholdStoresTheGroupAndItsHead() = runTest {
    val household = registerSouvannalath()

    val head = repository.all("Patient").single() as Patient
    assertEquals("Khamsing", head.name.single().given.single().value)
    assertEquals(head.id, household.headId())
    assertEquals("Organization/org-1", household.managingEntity?.reference?.value)
  }

  @Test
  fun addingAMemberAndANewbornLinksThemToTheHousehold() = runTest {
    val household = registerSouvannalath()
    val context = QuestionnaireLaunchContext(user = user, householdId = household.id)

    service.submit(
      service.getQuestionnaire(QuestionnaireIds.REGISTER_MEMBER, context),
      form(
        "given-name" to text("Mali"),
        "family-name" to text("SOUVANNALATH"),
        "sex" to coding("female"),
        "relationship-to-head" to coding("SPS"),
      ),
      context,
    )

    val mother =
      repository
        .all("Patient")
        .map { it as Patient }
        .single { it.name.single().given.single().value == "Mali" }
    val father = repository.get("Patient", household.headId()!!) as Patient
    val newbornForm = service.getQuestionnaire(QuestionnaireIds.REGISTER_NEWBORN, context)
    assertEquals(
      listOf(mother.id),
      newbornForm.item
        .single { it.linkId.value == "mother" }
        .answerOption
        .map { it.value.asCoding()?.value?.code?.value },
    )
    assertEquals(
      listOf(father.id),
      newbornForm.item
        .single { it.linkId.value == "father" }
        .answerOption
        .map { it.value.asCoding()?.value?.code?.value },
    )

    val result =
      service.submit(
        newbornForm,
        form(
          "given-name" to text("Chanthala"),
          "sex" to coding("female"),
          "mother" to coding(mother.id!!),
          "father" to coding(father.id!!),
        ),
        context,
      )

    assertEquals("Newborn registered.", result.successMessage)
    val updated = assertNotNull(repository.get("Group", household.id!!) as? Group)
    assertEquals(3, updated.memberIds().size)
    val child =
      repository
        .all("Patient")
        .map { it as Patient }
        .single { it.name.single().given.single().value == "Chanthala" }
    assertEquals("SOUVANNALATH", child.name.single().family?.value)
    val parents = repository.all("RelatedPerson").map { it as RelatedPerson }
    assertEquals(
      setOf("MTH", "FTH"),
      parents
        .filter { it.patient.reference?.value == "Patient/${child.id}" }
        .map { it.relationship.single().coding.single().code?.value }
        .toSet(),
    )
  }

  @Test
  fun aMemberNeedsAHousehold() = runTest {
    val failure =
      assertFailsWith<IllegalStateException> {
        service.submit(
          service.getQuestionnaire(QuestionnaireIds.REGISTER_MEMBER),
          form("given-name" to text("Mali")),
          QuestionnaireLaunchContext(user = user),
        )
      }

    assertEquals("This questionnaire needs a household to register into.", failure.message)
  }

  @Test
  fun submittingRegistrationStoresThePatient() = runTest {
    val questionnaire = service.getQuestionnaire(QuestionnaireIds.PATIENT_REGISTRATION)

    val result =
      service.submit(questionnaire, registration("Brian"), QuestionnaireLaunchContext(user = user))

    assertEquals("Patient registered.", result.successMessage)
    val patient = repository.all("Patient").single() as Patient
    assertEquals("Brian", patient.name.single().given.single().value)
    assertEquals("Organization/org-1", patient.managingOrganization?.reference?.value)
  }

  @Test
  fun registrationNeedsASignedInUser() = runTest {
    val questionnaire = service.getQuestionnaire(QuestionnaireIds.PATIENT_REGISTRATION)

    val failure =
      assertFailsWith<IllegalStateException> {
        service.submit(questionnaire, registration("Brian"), QuestionnaireLaunchContext())
      }

    assertEquals("This questionnaire needs a signed-in user with a role.", failure.message)
    assertEquals(0, repository.all("Patient").size)
  }

  @Test
  fun anUnknownQuestionnaireIsRefused() = runTest {
    val failure = assertFailsWith<IllegalStateException> { service.getQuestionnaire("not-bundled") }

    assertEquals("Questionnaire 'not-bundled' was not found in the app config.", failure.message)
  }
}

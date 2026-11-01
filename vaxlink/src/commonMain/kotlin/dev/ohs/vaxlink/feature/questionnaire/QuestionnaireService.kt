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
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Questionnaire as QuestionnaireR4
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.util.displayName
import dev.ohs.vaxlink.workflow.LaoScheduleService
import dev.ohs.vaxlink.workflow.ProgrammeRecord
import dev.ohs.vaxlink.workflow.Transactor
import dev.ohs.vaxlink.workflow.code
import dev.ohs.vaxlink.workflow.enrolAnc
import dev.ohs.vaxlink.workflow.enrolFp
import dev.ohs.vaxlink.workflow.headId
import dev.ohs.vaxlink.workflow.memberIds
import dev.ohs.vaxlink.workflow.recordAncContact
import dev.ohs.vaxlink.workflow.recordFpVisit
import dev.ohs.vaxlink.workflow.recordPncContact
import dev.ohs.vaxlink.workflow.recordWashCheck
import dev.ohs.vaxlink.workflow.registerHousehold
import dev.ohs.vaxlink.workflow.registerMember
import dev.ohs.vaxlink.workflow.registerNewborn
import dev.ohs.vaxlink.workflow.registrationPatient
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Caller-supplied identifiers describing why/for-whom a questionnaire was launched. */
data class QuestionnaireLaunchContext(
  val patientId: String? = null,
  val user: UserContext? = null,
  val taskId: String? = null,
  /** The household a member or newborn is registered into. */
  val householdId: String? = null,
)

/** Outcome of submitting a QuestionnaireResponse, ready for the UI to render. */
data class QuestionnaireSubmissionResult(val successMessage: String)

object QuestionnaireIds {
  /** Plain patient registration, kept for the clinic roles. */
  const val PATIENT_REGISTRATION = "patient-registration"

  /** A household and its head, in the eCHIS household model. */
  const val REGISTER_HOUSEHOLD = "register-household"

  /** Another member of an existing household, related to the head. */
  const val REGISTER_MEMBER = "register-member"

  /** A newborn in an existing household, linked to the parents chosen from its members. */
  const val REGISTER_NEWBORN = "register-newborn"

  /** The coding system the parent choices on the newborn form use; the code is the Patient id. */
  const val PARENT_CHOICE_SYSTEM = "http://ohs.dev/vaxlink/household-member"

  /** Pregnancy assessment, also used to enter an ANC history with its outcome (eCHIS enrol-anc). */
  const val ENROL_ANC = "enrol-anc"

  /** An ANC contact (eCHIS record-anc-contact). */
  const val RECORD_ANC_CONTACT = "record-anc-contact"

  /** A postnatal contact for the mother or newborn (eCHIS record-pnc-contact). */
  const val RECORD_PNC_CONTACT = "record-pnc-contact"

  /** Family planning assessment that enrols the method's plan (eCHIS enrol-fp). */
  const val ENROL_FP = "enrol-fp"

  /** A family planning follow-up visit (eCHIS record-fp-visit). */
  const val RECORD_FP_VISIT = "record-fp-visit"

  /** The household WASH check, on the Group (eCHIS record-wash-check). */
  const val RECORD_WASH_CHECK = "record-wash-check"

  /** The forms launched from an adult woman's profile. */
  val PROGRAMME_FORMS =
    listOf(ENROL_ANC, RECORD_ANC_CONTACT, RECORD_PNC_CONTACT, ENROL_FP, RECORD_FP_VISIT)
}

/** Bundled Questionnaire JSON, keyed by the id it should be read under. */
private val BUNDLED_QUESTIONNAIRE_PATHS: Map<String, String> =
  mapOf(
    QuestionnaireIds.PATIENT_REGISTRATION to
      "files/protocols/Questionnaire-PatientRegistration.json",
    QuestionnaireIds.REGISTER_HOUSEHOLD to "files/configs/Questionnaire-RegisterHousehold.json",
    QuestionnaireIds.REGISTER_MEMBER to "files/configs/Questionnaire-RegisterMember.json",
    QuestionnaireIds.REGISTER_NEWBORN to "files/configs/Questionnaire-RegisterNewborn.json",
    QuestionnaireIds.ENROL_ANC to "files/configs/Questionnaire-EnrolAnc.json",
    QuestionnaireIds.RECORD_ANC_CONTACT to "files/configs/Questionnaire-RecordAncContact.json",
    QuestionnaireIds.RECORD_PNC_CONTACT to "files/configs/Questionnaire-RecordPncContact.json",
    QuestionnaireIds.ENROL_FP to "files/configs/Questionnaire-EnrolFp.json",
    QuestionnaireIds.RECORD_FP_VISIT to "files/configs/Questionnaire-RecordFpVisit.json",
    QuestionnaireIds.RECORD_WASH_CHECK to "files/configs/Questionnaire-RecordWashCheck.json",
  )

/** Reads bundled Questionnaires and persists what their responses produce via [FhirRepository]. */
class QuestionnaireService(
  private val repository: FhirRepository,
  private val transactor: Transactor = Transactor { it() },
  /** Brings a newborn's Lao schedule up after registration; null skips the schedule. */
  private val schedule: LaoScheduleService? = null,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {

  private val fhirJson = FhirJson.instance

  /** Reads a Questionnaire from the bundled config files. */
  @OptIn(ExperimentalResourceApi::class)
  suspend fun getQuestionnaire(
    id: String,
    launchContext: QuestionnaireLaunchContext = QuestionnaireLaunchContext(),
  ): QuestionnaireR4 {
    val path =
      BUNDLED_QUESTIONNAIRE_PATHS[id]
        ?: error("Questionnaire '$id' was not found in the app config.")
    val json = Res.readBytes(path).decodeToString()
    val questionnaire = fhirJson.decodeFromString(QuestionnaireR4.serializer(), json).copy(id = id)
    return when (id) {
      QuestionnaireIds.REGISTER_NEWBORN -> questionnaire.withParentChoices(launchContext)
      else -> questionnaire
    }
  }

  fun toJson(questionnaire: QuestionnaireR4): String =
    fhirJson.encodeToString(QuestionnaireR4.serializer(), questionnaire)

  suspend fun submit(
    questionnaire: QuestionnaireR4,
    response: QuestionnaireResponse,
    launchContext: QuestionnaireLaunchContext,
  ): QuestionnaireSubmissionResult {
    var result: QuestionnaireSubmissionResult? = null
    transactor.atomically { result = record(questionnaire, response, launchContext) }
    return result!!
  }

  /**
   * Extracts the response's resources and stores them. One branch per bundled questionnaire; the
   * household forms keep the eCHIS linkIds so the content ports between the two platforms.
   */
  private suspend fun record(
    questionnaire: QuestionnaireR4,
    response: QuestionnaireResponse,
    launchContext: QuestionnaireLaunchContext,
  ): QuestionnaireSubmissionResult =
    when (questionnaire.id) {
      QuestionnaireIds.PATIENT_REGISTRATION -> {
        repository.upsert(registrationPatient(response, launchContext.user().organizationId))
        QuestionnaireSubmissionResult("Patient registered.")
      }
      QuestionnaireIds.REGISTER_HOUSEHOLD -> {
        val registration = registerHousehold(response, launchContext.user().organizationId)
        repository.upsert(registration.head)
        repository.upsert(registration.household)
        schedule?.scheduler?.refresh(registration.household)
        QuestionnaireSubmissionResult("Household registered.")
      }
      QuestionnaireIds.REGISTER_MEMBER -> {
        val household = launchContext.household()
        val head = household.headId()?.let { repository.get("Patient", it) as? Patient }
        val registration =
          registerMember(response, household, head, launchContext.user().organizationId)
        repository.upsert(registration.member)
        repository.upsert(registration.household)
        registration.relationToHead?.let { repository.upsert(it) }
        QuestionnaireSubmissionResult("Member added to the household.")
      }
      QuestionnaireIds.REGISTER_NEWBORN -> {
        val household = launchContext.household()
        val mother = response.code("mother")?.let { repository.get("Patient", it) as? Patient }
        val father = response.code("father")?.let { repository.get("Patient", it) as? Patient }
        val registration =
          registerNewborn(response, household, mother, father, launchContext.user().organizationId)
        repository.upsert(registration.child)
        repository.upsert(registration.household)
        registration.parents.forEach { repository.upsert(it) }
        schedule?.refresh(registration.child)
        QuestionnaireSubmissionResult("Newborn registered.")
      }
      QuestionnaireIds.ENROL_ANC -> {
        val patient = launchContext.patient()
        store(enrolAnc(response, patient.id!!, today()), patient, launchContext)
        QuestionnaireSubmissionResult("Antenatal care recorded.")
      }
      QuestionnaireIds.RECORD_ANC_CONTACT -> {
        val patient = launchContext.patient()
        store(recordAncContact(response, patient.id!!, today()), patient, launchContext)
        QuestionnaireSubmissionResult("ANC contact recorded.")
      }
      QuestionnaireIds.RECORD_PNC_CONTACT -> {
        val patient = launchContext.patient()
        store(recordPncContact(response, patient.id!!, today()), patient, launchContext)
        QuestionnaireSubmissionResult("Postnatal contact recorded.")
      }
      QuestionnaireIds.ENROL_FP -> {
        val patient = launchContext.patient()
        store(enrolFp(response, patient.id!!, today()), patient, launchContext)
        QuestionnaireSubmissionResult("Family planning recorded.")
      }
      QuestionnaireIds.RECORD_FP_VISIT -> {
        val patient = launchContext.patient()
        store(recordFpVisit(response, patient.id!!, today()), patient, launchContext)
        QuestionnaireSubmissionResult("Family planning visit recorded.")
      }
      QuestionnaireIds.RECORD_WASH_CHECK -> {
        val household = launchContext.household()
        store(recordWashCheck(response, household.id!!, today()), household, launchContext)
        QuestionnaireSubmissionResult("WASH check recorded.")
      }
      else -> error("No submission handling is defined for questionnaire '${questionnaire.id}'.")
    }

  /** The newborn form offers the household's adults as mother and father. */
  private suspend fun QuestionnaireR4.withParentChoices(
    launchContext: QuestionnaireLaunchContext
  ): QuestionnaireR4 {
    val householdId = launchContext.householdId ?: return this
    val household = repository.get("Group", householdId) as? Group ?: return this
    val adults =
      household
        .memberIds()
        .mapNotNull { repository.get("Patient", it) as? Patient }
        .filter { it.isAdult() }
    fun options(gender: AdministrativeGender) =
      adults
        .filter { it.gender?.value == gender || it.gender == null }
        .map { patient ->
          QuestionnaireR4.Item.AnswerOption(
            value =
              QuestionnaireR4.Item.AnswerOption.Value.Coding(
                Coding(
                  system = Uri(value = QuestionnaireIds.PARENT_CHOICE_SYSTEM),
                  code = Code(value = patient.id!!),
                  display = FhirString(value = patient.displayName()),
                )
              )
          )
        }
    return copy(
      item =
        item.map { item ->
          when (item.linkId.value) {
            "mother" -> item.copy(answerOption = options(AdministrativeGender.Female))
            "father" -> item.copy(answerOption = options(AdministrativeGender.Male))
            else -> item
          }
        }
    )
  }

  private fun Patient.isAdult(): Boolean {
    val born = (birthDate?.value as? FhirDate.Date)?.date ?: return true
    return born <= today().minus(DatePeriod(years = 10))
  }

  /**
   * Stores what a programme form produced, then brings the subject's plans up to date. A referral
   * the form asked for is requested by the signed-in user, which is what the CHW's sync filter
   * matches on.
   */
  private suspend fun store(
    record: ProgrammeRecord,
    subject: Resource,
    launchContext: QuestionnaireLaunchContext,
  ) {
    val requester =
      launchContext.user?.practitionerId?.let {
        Reference(reference = FhirString(value = "Practitioner/$it"))
      }
    record.resources.forEach {
      val stored =
        if (it is ServiceRequest && it.requester == null && requester != null)
          it.copy(requester = requester)
        else it
      repository.upsert(stored)
    }
    schedule?.scheduler?.refresh(subject)
  }

  private suspend fun QuestionnaireLaunchContext.patient(): Patient =
    patientId?.let { repository.get("Patient", it) as? Patient }
      ?: error("This questionnaire needs a patient to record against.")

  private suspend fun QuestionnaireLaunchContext.household(): Group =
    householdId?.let { repository.get("Group", it) as? Group }
      ?: error("This questionnaire needs a household to register into.")
}

private fun QuestionnaireLaunchContext.user(): UserContext =
  user ?: error("This questionnaire needs a signed-in user with a role.")

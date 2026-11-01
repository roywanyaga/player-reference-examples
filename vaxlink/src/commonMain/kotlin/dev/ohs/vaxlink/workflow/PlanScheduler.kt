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

import dev.ohs.fhir.engine.resourceType
import dev.ohs.fhir.model.r4.ActivityDefinition
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RequestGroup
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.generateId
import dev.ohs.vaxlink.util.idOf
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/** One schedulable action of a protocol, with the eCHIS schedule-offset read off it. */
data class ScheduledAction(
  val planUrl: String,
  val actionId: String,
  val activityUrl: String,
  val title: String,
  val code: CodeableConcept,
  /** Codes named in the applicability condition; for the Lao plan, the dose codes. */
  val conditionCodes: List<String>,
  val anchor: String,
  val offset: DatePeriod,
  val windowBefore: DatePeriod,
  val windowAfter: DatePeriod,
) {
  /** The Lao schedule's name for [conditionCodes]: the dose codes an Immunization may carry. */
  val doseCodes: List<String>
    get() = conditionCodes
}

/** A bundled PlanDefinition and the eCHIS plan-level extensions the scheduler honours. */
data class Protocol(
  val url: String,
  val title: String,
  val subjectType: String,
  /** `auto` with an eligibility expression, or `explicit` through an active CarePlan. */
  val enrollmentMode: String,
  val eligibility: String?,
  /** The observation code the anchor moves to for a recurring protocol. */
  val rollingAnchorCode: String?,
  /** Observation codes whose presence ends the plan (the eCHIS discharge-condition). */
  val dischargeCodes: List<String>,
  /** Plans enrolled when this one completes (the eCHIS on-complete extension). */
  val onCompleteEnroll: List<String>,
  val actions: List<ScheduledAction>,
)

/**
 * Runs every bundled protocol for a subject and keeps its Tasks in step with the record. Protocols
 * are written in the eCHIS convention so the same files run in both platforms: the eCHIS
 * `enrollment`, `schedule-offset`, `rolling-anchor`, `discharge-condition` and `on-complete`
 * extensions are read here, and each action's applicability condition is evaluated by fhir-workflow
 * `$apply`, which produces a Task for every action still applicable.
 *
 * For each action: produced and the plan still open means a requested Task (created once); produced
 * but the plan discharged means the Task is cancelled, as missed when its window has passed; not
 * produced means the action is satisfied, so its Task is completed with the record that satisfied
 * it, created completed when the record predates the first run. A recurring action whose anchor
 * moved gets a fresh Task and the old one is completed.
 *
 * Two eCHIS expressions are read structurally rather than evaluated: the auto-enrolment eligibility
 * `Patient.birthDate > (today() - N years)` and a discharge condition of the form `%observations
 * ... code = 'X' ... exists()`. Those are the only shapes the bundled plans use.
 */
class PlanScheduler(
  private val repository: FhirRepository,
  private val operatorFactory: suspend () -> FhirOperator,
  private val protocolsFactory: suspend () -> BundledProtocols,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
  private val lock = Mutex()
  private var operator: FhirOperator? = null
  private var protocols: List<Protocol>? = null

  /** Every plan that applies to [subject]; returns the subject's Tasks across them. */
  suspend fun refresh(subject: Resource): List<Task> {
    val type = subject.resourceType
    val all = protocols().filter { it.subjectType == type }
    return all
      .flatMap { refresh(subject, it) }
      .sortedBy { it.restriction?.period?.end?.value?.toString() }
  }

  suspend fun refresh(subject: Resource, planUrl: String): List<Task> =
    protocols().firstOrNull { it.url == planUrl }?.let { refresh(subject, it) }
      ?: error("No bundled protocol $planUrl")

  /** The subject's Tasks of one plan, or of every plan when [planUrl] is null. */
  suspend fun tasksFor(subjectRef: String, planUrl: String? = null): List<Task> {
    val activities =
      if (planUrl == null) null
      else protocols().firstOrNull { it.url == planUrl }?.actions?.map { it.activityUrl }?.toSet()
    return repository.all("Task").filterIsInstance<Task>().filter {
      it.`for`?.reference?.value == subjectRef &&
        it.instantiatesCanonical?.value != null &&
        (activities == null || it.instantiatesCanonical?.value in activities)
    }
  }

  suspend fun protocols(): List<Protocol> =
    lock.withLock { protocols ?: loadProtocols().also { protocols = it } }

  private suspend fun refresh(subject: Resource, protocol: Protocol): List<Task> {
    val subjectRef = "${subject.resourceType}/${subject.id}"
    val existing =
      tasksFor(subjectRef, protocol.url).associateBy { it.instantiatesCanonical?.value }
    val carePlan = enrolment(subject, subjectRef, protocol) ?: return existing.values.toList()
    val history = History.of(repository, subject, subjectRef)
    val produced = producedActivities(subject, protocol, history)
    val discharged =
      carePlan.status.value == CarePlan.RequestStatus.Completed ||
        (protocol.dischargeCodes.isNotEmpty() &&
          history.observations.any { it.hasCode(protocol.dischargeCodes) })
    if (discharged) completeSuccessors(subject, subjectRef, protocol, history)
    val result = mutableListOf<Task>()
    for (action in protocol.actions) {
      val anchor = anchorDate(action, subject, carePlan, protocol, history) ?: continue
      val window = window(action, anchor)
      val task = existing[action.activityUrl]
      val evidence = history.evidenceFor(action, protocol)
      val isProduced = action.activityUrl in produced
      when {
        isProduced && !discharged -> {
          when {
            task == null ->
              result += create(subjectRef, action, window).also { repository.upsert(it) }
            task.isOpen() && task.windowStart() != window.start -> {
              // A recurring action: the anchor moved, so the old Task is done and a new one is due.
              result += complete(task, evidence).also { repository.upsert(it) }
              result += create(subjectRef, action, window).also { repository.upsert(it) }
            }
            else -> result += task
          }
        }
        isProduced -> {
          when {
            task == null ->
              result +=
                cancel(create(subjectRef, action, window), window).also { repository.upsert(it) }
            task.isOpen() -> result += cancel(task, window).also { repository.upsert(it) }
            else -> result += task
          }
        }
        task != null && task.isOpen() ->
          result += complete(task, evidence).also { repository.upsert(it) }
        task != null -> result += task
        evidence != null || !discharged ->
          result +=
            complete(create(subjectRef, action, window), evidence).also { repository.upsert(it) }
      }
    }
    return result
  }

  /** The CarePlan that enrols the subject, created for an auto plan the subject is eligible for. */
  private suspend fun enrolment(
    subject: Resource,
    subjectRef: String,
    protocol: Protocol,
  ): CarePlan? {
    val plans =
      repository.all("CarePlan").filterIsInstance<CarePlan>().filter {
        it.subject.reference?.value == subjectRef &&
          it.instantiatesCanonical.any { c -> c.value == protocol.url } &&
          it.status.value != CarePlan.RequestStatus.Entered_In_Error &&
          it.status.value != CarePlan.RequestStatus.Revoked
      }
    plans
      .firstOrNull { it.status.value == CarePlan.RequestStatus.Active }
      ?.let {
        return it
      }
    plans.firstOrNull()?.let {
      return it
    }
    if (protocol.enrollmentMode != "auto" || !eligible(subject, protocol.eligibility)) return null
    return CarePlan(
        id = generateId(),
        instantiatesCanonical = listOf(Canonical(value = protocol.url)),
        status = Enumeration(value = CarePlan.RequestStatus.Active),
        intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
        title = FhirString(value = protocol.title),
        subject = Reference(reference = FhirString(value = subjectRef)),
        period = Period(start = DateTime(value = FhirDateTime.Date(anchorOf(subject) ?: today()))),
      )
      .also { repository.upsert(it) }
  }

  private fun eligible(subject: Resource, eligibility: String?): Boolean {
    if (eligibility == null) return true
    val years =
      Regex("""birthDate\s*>\s*\(\s*today\(\)\s*-\s*(\d+)\s*years?\s*\)""")
        .find(eligibility)
        ?.groupValues
        ?.get(1)
        ?.toInt() ?: return true
    val birthDate = (subject as? Patient)?.birthDate?.value?.toLocalDate() ?: return false
    return birthDate > today().minus(DatePeriod(years = years))
  }

  private suspend fun completeSuccessors(
    subject: Resource,
    subjectRef: String,
    protocol: Protocol,
    history: History,
  ) {
    for (successor in protocol.onCompleteEnroll) {
      val already =
        repository.all("CarePlan").filterIsInstance<CarePlan>().any {
          it.subject.reference?.value == subjectRef &&
            it.instantiatesCanonical.any { c -> c.value == successor }
        }
      if (already) continue
      val next = protocols().firstOrNull { it.url == successor } ?: continue
      val start =
        history.observations
          .filter { it.hasCode(protocol.dischargeCodes) }
          .mapNotNull { it.effectiveDate() }
          .maxOrNull() ?: today()
      repository.upsert(
        CarePlan(
          id = generateId(),
          instantiatesCanonical = listOf(Canonical(value = successor)),
          status = Enumeration(value = CarePlan.RequestStatus.Active),
          intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
          title = FhirString(value = next.title),
          subject = Reference(reference = FhirString(value = subjectRef)),
          period = Period(start = DateTime(value = FhirDateTime.Date(start))),
        )
      )
      refresh(subject, next)
    }
  }

  private suspend fun producedActivities(
    subject: Resource,
    protocol: Protocol,
    history: History,
  ): Set<String> {
    val carePlan =
      operator()
        .generateCarePlan(
          protocol.url,
          subject,
          mapOf(
            "immunizations" to collection(history.immunizations),
            "observations" to collection(history.observations),
            "encounters" to collection(history.encounters),
            "questionnaireResponses" to collection(history.responses),
            "serviceRequests" to collection(history.serviceRequests),
          ),
          today(),
        )
    return carePlan.contained
      .filterNot { it is RequestGroup }
      .filterIsInstance<Task>()
      .mapNotNull { it.instantiatesCanonical?.value }
      .toSet()
  }

  private fun anchorDate(
    action: ScheduledAction,
    subject: Resource,
    carePlan: CarePlan,
    protocol: Protocol,
    history: History,
  ): LocalDate? {
    val planStart = carePlan.period?.start?.value?.toLocalDate()
    return when {
      action.anchor == "birthDate" -> anchorOf(subject)
      protocol.rollingAnchorCode != null ->
        history.observations
          .filter { it.hasCode(listOf(protocol.rollingAnchorCode)) }
          .mapNotNull { it.effectiveDate() }
          .maxOrNull() ?: planStart
      else -> planStart
    }
  }

  private fun anchorOf(subject: Resource): LocalDate? =
    (subject as? Patient)?.birthDate?.value?.toLocalDate()

  private fun window(action: ScheduledAction, anchor: LocalDate): Window {
    val due = anchor.plus(action.offset)
    return Window(due.minus(action.windowBefore), due.plus(action.windowAfter))
  }

  private fun create(subjectRef: String, action: ScheduledAction, window: Window): Task =
    Task(
      id = generateId(),
      instantiatesCanonical = Canonical(value = action.activityUrl),
      status = Enumeration(value = Task.TaskStatus.Requested),
      intent = Enumeration(value = Task.TaskIntent.Plan),
      code = action.code.copy(text = FhirString(value = action.title)),
      description = FhirString(value = action.title),
      `for` = Reference(reference = FhirString(value = subjectRef)),
      authoredOn = DateTime(value = FhirDateTime.Date(today())),
      restriction =
        Task.Restriction(
          period =
            Period(
              start = DateTime(value = FhirDateTime.Date(window.start)),
              end = DateTime(value = FhirDateTime.Date(window.end)),
            )
        ),
    )

  private fun complete(task: Task, evidence: Resource?): Task =
    task.copy(
      status = Enumeration(value = Task.TaskStatus.Completed),
      lastModified = DateTime(value = FhirDateTime.Date(today())),
      output =
        listOfNotNull(
          evidence?.let {
            Task.Output(
              type = CodeableConcept(text = FhirString(value = it.resourceType)),
              value =
                Task.Output.Value.Reference(
                  Reference(reference = FhirString(value = "${it.resourceType}/${it.id}"))
                ),
            )
          }
        ),
    )

  private fun cancel(task: Task, window: Window): Task =
    task.copy(
      status = Enumeration(value = Task.TaskStatus.Cancelled),
      lastModified = DateTime(value = FhirDateTime.Date(today())),
      statusReason =
        CodeableConcept(
          text = FhirString(value = if (window.end < today()) TASK_MISSED else TASK_PLAN_ENDED)
        ),
    )

  private suspend fun loadProtocols(): List<Protocol> {
    val bundled = protocolsFactory()
    return bundled.plans().map { plan ->
      val url = plan.url?.value ?: error("A bundled PlanDefinition has no url")
      val enrollment =
        plan.extension.firstOrNull { it.url == ENROLLMENT_EXTENSION }?.extension.orEmpty()
      Protocol(
        url = url,
        title = plan.title?.value ?: url.substringAfterLast('/'),
        subjectType =
          plan.subject?.asCodeableConcept()?.value?.coding?.firstOrNull()?.code?.value ?: "Patient",
        enrollmentMode = enrollment.stringOf("mode") ?: "auto",
        eligibility = enrollment.stringOf("eligibility"),
        rollingAnchorCode =
          plan.extension
            .firstOrNull { it.url == ROLLING_ANCHOR_EXTENSION }
            ?.extension
            ?.stringOf("code"),
        dischargeCodes =
          plan.extension
            .firstOrNull { it.url == DISCHARGE_CONDITION_EXTENSION }
            ?.value
            ?.asString()
            ?.value
            ?.value
            ?.let { codesIn(it) }
            .orEmpty(),
        onCompleteEnroll =
          plan.extension
            .firstOrNull { it.url == ON_COMPLETE_EXTENSION }
            ?.extension
            ?.filter { it.url == "enroll" }
            ?.mapNotNull { it.value?.asString()?.value?.value }
            .orEmpty(),
        actions =
          plan.action.mapNotNull { action ->
            val activityUrl =
              action.definition?.asCanonical()?.value?.value ?: return@mapNotNull null
            val activity =
              bundled.resolve(ResourceType.ActivityDefinition, activityUrl) as? ActivityDefinition
                ?: return@mapNotNull null
            val offset =
              action.extension
                .firstOrNull { it.url == SCHEDULE_OFFSET_EXTENSION }
                ?.extension
                .orEmpty()
            ScheduledAction(
              planUrl = url,
              actionId = action.id ?: activityUrl.substringAfterLast('/'),
              activityUrl = activityUrl,
              title =
                action.title?.value ?: activity.title?.value ?: activityUrl.substringAfterLast('/'),
              code = activity.code ?: CodeableConcept(text = action.title),
              conditionCodes =
                action.condition
                  .mapNotNull { it.expression?.expression?.value }
                  .flatMap { codesIn(it) }
                  .distinct(),
              anchor = offset.stringOf("anchor") ?: "birthDate",
              offset = offset.stringOf("offset")?.let(::parsePeriod) ?: DatePeriod(),
              windowBefore = offset.stringOf("windowBefore")?.let(::parsePeriod) ?: DatePeriod(),
              windowAfter = offset.stringOf("windowAfter")?.let(::parsePeriod) ?: DatePeriod(),
            )
          },
      )
    }
  }

  private suspend fun operator(): FhirOperator =
    lock.withLock { operator ?: operatorFactory().also { operator = it } }

  private data class Window(val start: LocalDate, val end: LocalDate)

  /** The subject's record, as the plans' `%variables` and as the evidence a Task completes with. */
  private class History(
    val immunizations: List<Immunization>,
    val observations: List<Observation>,
    val encounters: List<Encounter>,
    val responses: List<QuestionnaireResponse>,
    val serviceRequests: List<ServiceRequest>,
  ) {
    /**
     * The record that satisfies [action]: the n-th matching observation of a repeated plan, else
     * the latest.
     */
    fun evidenceFor(action: ScheduledAction, protocol: Protocol): Resource? {
      immunizations
        .firstOrNull { it.hasAnyCode(action.conditionCodes) }
        ?.let {
          return it
        }
      val codes = action.conditionCodes.ifEmpty { listOfNotNull(protocol.rollingAnchorCode) }
      val matching =
        observations.filter { it.hasCode(codes) }.sortedBy { it.effectiveDate()?.toString() }
      if (matching.isEmpty()) return null
      val index = protocol.actions.indexOfFirst { it.activityUrl == action.activityUrl }
      return matching.getOrNull(index) ?: matching.last()
    }

    companion object {
      suspend fun of(repository: FhirRepository, subject: Resource, subjectRef: String): History {
        val patientId = (subject as? Patient)?.id
        return History(
          immunizations =
            repository.all("Immunization").filterIsInstance<Immunization>().filter {
              patientId != null && it.patient.idOf("Patient") == patientId
            },
          observations =
            repository.all("Observation").filterIsInstance<Observation>().filter {
              it.subject?.reference?.value == subjectRef
            },
          encounters =
            repository.all("Encounter").filterIsInstance<Encounter>().filter {
              it.subject?.reference?.value == subjectRef
            },
          responses =
            repository
              .all("QuestionnaireResponse")
              .filterIsInstance<QuestionnaireResponse>()
              .filter { it.subject?.reference?.value == subjectRef },
          serviceRequests =
            repository.all("ServiceRequest").filterIsInstance<ServiceRequest>().filter {
              it.subject.reference?.value == subjectRef
            },
        )
      }
    }
  }

  companion object {
    const val TASK_MISSED = "Missed"
    const val TASK_PLAN_ENDED = "Plan ended"

    fun codesIn(expression: String): List<String> =
      Regex("""code\s*=\s*'([A-Za-z0-9.\-]+)'""")
        .findAll(expression)
        .map { it.groupValues[1] }
        .toList()

    fun parsePeriod(text: String): DatePeriod {
      val match =
        Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(text.trim())
          ?: error("Not an ISO date period: $text")
      val (y, m, w, d) = match.destructured
      return DatePeriod(
        years = y.toIntOrNull() ?: 0,
        months = m.toIntOrNull() ?: 0,
        days = (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0),
      )
    }

    private fun collection(resources: List<Resource>) =
      Bundle(
        type = Enumeration(value = Bundle.BundleType.Collection),
        entry = resources.map { Bundle.Entry(resource = it) },
      )

    private fun List<dev.ohs.fhir.model.r4.Extension>.stringOf(url: String): String? =
      firstOrNull { it.url == url }?.value?.asString()?.value?.value

    private fun Task.isOpen() =
      status.value == Task.TaskStatus.Requested || status.value == Task.TaskStatus.Ready

    private fun Task.windowStart(): LocalDate? = restriction?.period?.start?.value?.toLocalDate()

    private fun FhirDateTime.toLocalDate(): LocalDate? =
      when (this) {
        is FhirDateTime.Date -> date
        is FhirDateTime.DateTime -> dateTime.date
        else -> null
      }

    private fun FhirDate.toLocalDate(): LocalDate? = (this as? FhirDate.Date)?.date

    private fun Observation.effectiveDate(): LocalDate? =
      when (val e = effective) {
        is Observation.Effective.DateTime -> e.value.value?.toLocalDate()
        is Observation.Effective.Period -> e.value.start?.value?.toLocalDate()
        else -> null
      } ?: issued?.value?.toLocalDate()

    private fun Observation.hasCode(codes: List<String>) =
      codes.isNotEmpty() && code.coding.any { it.code?.value in codes }

    private fun Immunization.hasAnyCode(codes: List<String>) =
      status.value == Immunization.ImmunizationStatusCodes.Completed &&
        codes.isNotEmpty() &&
        vaccineCode.coding.any { it.code?.value in codes }
  }
}

/** Lao schedule Tasks on a child's record; the Lao plan through the generic [PlanScheduler]. */
class LaoScheduleService(
  private val repository: FhirRepository,
  operatorFactory: suspend () -> FhirOperator,
  private val protocolsFactory: suspend () -> BundledProtocols,
  today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
  val scheduler = PlanScheduler(repository, operatorFactory, protocolsFactory, today)

  /** Runs every plan the child is enrolled in and returns the Lao schedule Tasks. */
  suspend fun refresh(patient: Patient): List<Task> {
    scheduler.refresh(patient)
    return scheduleTasks(patient.id ?: return emptyList())
  }

  suspend fun scheduleTasks(patientId: String): List<Task> =
    scheduler.tasksFor("Patient/$patientId", LAO_SCHEDULE_PLAN).sortedBy {
      it.restriction?.period?.end?.value?.toString()
    }

  suspend fun doses(): List<ScheduledAction> =
    scheduler.protocols().first { it.url == LAO_SCHEDULE_PLAN }.actions

  companion object {
    const val ACTIVITY_PREFIX = "https://nawitech.github.io/vaxlink-ig/ActivityDefinition/LaoEpi-"

    fun doseCodesOf(action: PlanDefinition.Action): List<String> =
      action.condition
        .mapNotNull { it.expression?.expression?.value }
        .flatMap { PlanScheduler.codesIn(it) }
        .distinct()

    fun parsePeriod(text: String): DatePeriod = PlanScheduler.parsePeriod(text)
  }
}

/** Every household's recurring WASH check, enrolled on first sight of the household. */
suspend fun PlanScheduler.refreshHousehold(household: Group): List<Task> = refresh(household)

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

import dev.ohs.fhir.model.r4.ActivityDefinition
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RequestGroup
import dev.ohs.fhir.model.r4.Resource
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

/** One dose of the schedule, as the plan's action describes it. */
data class ScheduledDose(
  val activityUrl: String,
  val title: String,
  val code: CodeableConcept,
  val doseCodes: List<String>,
  val offset: DatePeriod,
  val windowBefore: DatePeriod,
  val windowAfter: DatePeriod,
)

/**
 * The Lao schedule as Tasks on a child's record. The PlanDefinition (from the VaxLink IG, in the
 * eCHIS convention) decides which doses are still due through its FHIRPath conditions over the
 * child's Immunizations; this service turns each one into a stored Task with a due window from the
 * action's schedule-offset extension, keeps existing Tasks, and completes a Task once a completed
 * Immunization carries the dose's Lao coding. Due work is therefore visible offline and syncs like
 * any other resource.
 */
class LaoScheduleService(
  private val repository: FhirRepository,
  private val operatorFactory: suspend () -> FhirOperator,
  private val protocolsFactory: suspend () -> BundledProtocols,
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
  private val lock = Mutex()
  private var operator: FhirOperator? = null
  private var doses: List<ScheduledDose>? = null

  /** Brings the child's schedule Tasks up to date and returns them, newest due first. */
  suspend fun refresh(patient: Patient): List<Task> {
    val patientId = patient.id ?: return emptyList()
    val birthDate = (patient.birthDate?.value as? dev.ohs.fhir.model.r4.FhirDate.Date)?.date
    val existing = scheduleTasks(patientId)
    if (birthDate == null || birthDate < today().minus(DatePeriod(years = 5))) return existing
    val immunizations = immunizationsOf(patientId)
    val produced = producedActivities(patient, immunizations)
    val byActivity = existing.associateBy { it.instantiatesCanonical?.value }
    val result = mutableListOf<Task>()
    for (dose in doses()) {
      val task = byActivity[dose.activityUrl]
      when {
        dose.activityUrl in produced -> {
          result += task ?: create(patientId, birthDate, dose).also { repository.upsert(it) }
        }
        task != null && task.status.value != Task.TaskStatus.Completed -> {
          val given = immunizations.firstOrNull { it.hasDose(dose) }
          result += complete(task, given).also { repository.upsert(it) }
        }
        task != null -> result += task
        // A dose already on the record before the schedule first ran still gets its Task, done,
        // so the schedule shows every dose.
        else ->
          immunizations
            .firstOrNull { it.hasDose(dose) }
            ?.let { given ->
              result +=
                complete(create(patientId, birthDate, dose), given).also { repository.upsert(it) }
            }
      }
    }
    return result.sortedBy { it.restriction?.period?.end?.value?.toString() }
  }

  /** The schedule Tasks already stored for the child. */
  suspend fun scheduleTasks(patientId: String): List<Task> =
    repository.all("Task").filterIsInstance<Task>().filter {
      it.`for`.idOf("Patient") == patientId &&
        it.instantiatesCanonical?.value?.startsWith(ACTIVITY_PREFIX) == true
    }

  suspend fun doses(): List<ScheduledDose> =
    lock.withLock {
      doses
        ?: run {
          val protocols = protocolsFactory()
          val plan =
            protocols.resolve(ResourceType.PlanDefinition, LAO_SCHEDULE_PLAN) as? PlanDefinition
              ?: error("The Lao schedule PlanDefinition is not bundled")
          plan.action
            .mapNotNull { action ->
              val url = action.definition?.asCanonical()?.value?.value ?: return@mapNotNull null
              val activity =
                protocols.resolve(ResourceType.ActivityDefinition, url) as? ActivityDefinition
                  ?: return@mapNotNull null
              val offset =
                action.extension
                  .firstOrNull { it.url == SCHEDULE_OFFSET_EXTENSION }
                  ?.extension
                  .orEmpty()
              fun period(name: String) =
                offset
                  .firstOrNull { it.url == name }
                  ?.value
                  ?.asString()
                  ?.value
                  ?.value
                  ?.let(::parsePeriod) ?: DatePeriod()
              ScheduledDose(
                activityUrl = url,
                title = action.title?.value ?: activity.title?.value ?: url.substringAfterLast('/'),
                code = activity.code ?: CodeableConcept(text = action.title),
                doseCodes = doseCodesOf(action),
                offset = period("offset"),
                windowBefore = period("windowBefore"),
                windowAfter = period("windowAfter"),
              )
            }
            .also { doses = it }
        }
    }

  private suspend fun producedActivities(
    patient: Patient,
    immunizations: List<Immunization>,
  ): Set<String> {
    val carePlan =
      operator()
        .generateCarePlan(
          LAO_SCHEDULE_PLAN,
          patient,
          mapOf("immunizations" to collection(immunizations)),
          today(),
        )
    return carePlan.contained
      .filterNot { it is RequestGroup }
      .filterIsInstance<Task>()
      .mapNotNull { it.instantiatesCanonical?.value ?: activityOfCode(it) }
      .toSet()
  }

  /** A generated Task without instantiatesCanonical is matched to its dose by code. */
  private suspend fun activityOfCode(task: Task): String? {
    val codes = task.code?.coding?.mapNotNull { it.code?.value }.orEmpty()
    return doses().firstOrNull { d -> d.code.coding.any { it.code?.value in codes } }?.activityUrl
  }

  private fun create(patientId: String, birthDate: LocalDate, dose: ScheduledDose): Task {
    val due = birthDate.plus(dose.offset)
    return Task(
      id = generateId(),
      instantiatesCanonical = Canonical(value = dose.activityUrl),
      status = Enumeration(value = Task.TaskStatus.Requested),
      intent = Enumeration(value = Task.TaskIntent.Plan),
      code = dose.code.copy(text = FhirString(value = dose.title)),
      description = FhirString(value = dose.title),
      `for` = Reference(reference = FhirString(value = "Patient/$patientId")),
      authoredOn = DateTime(value = FhirDateTime.Date(today())),
      restriction =
        Task.Restriction(
          period =
            Period(
              start = DateTime(value = FhirDateTime.Date(due.minus(dose.windowBefore))),
              end = DateTime(value = FhirDateTime.Date(due.plus(dose.windowAfter))),
            )
        ),
    )
  }

  private fun complete(task: Task, given: Immunization?): Task =
    task.copy(
      status = Enumeration(value = Task.TaskStatus.Completed),
      lastModified = DateTime(value = FhirDateTime.Date(today())),
      output =
        listOfNotNull(
          given?.let {
            Task.Output(
              type = CodeableConcept(text = FhirString(value = "Immunization")),
              value =
                Task.Output.Value.Reference(
                  Reference(reference = FhirString(value = "Immunization/${it.id}"))
                ),
            )
          }
        ),
    )

  private fun Immunization.hasDose(dose: ScheduledDose) =
    status.value == Immunization.ImmunizationStatusCodes.Completed &&
      vaccineCode.coding.any {
        it.system?.value == LAO_DOSE_SYSTEM && it.code?.value in dose.doseCodes
      }

  private suspend fun immunizationsOf(patientId: String) =
    repository.all("Immunization").filterIsInstance<Immunization>().filter {
      it.patient.idOf("Patient") == patientId
    }

  private suspend fun operator(): FhirOperator =
    lock.withLock { operator ?: operatorFactory().also { operator = it } }

  private fun collection(resources: List<Resource>) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Collection),
      entry = resources.map { Bundle.Entry(resource = it) },
    )

  companion object {
    const val ACTIVITY_PREFIX = "https://nawitech.github.io/vaxlink-ig/ActivityDefinition/LaoEpi-"

    /** The Lao dose codes an action's applicability condition names, read from its FHIRPath. */
    fun doseCodesOf(action: PlanDefinition.Action): List<String> =
      action.condition
        .mapNotNull { it.expression?.expression?.value }
        .flatMap { Regex("code='([A-Z0-9-]+)'").findAll(it).map { m -> m.groupValues[1] }.toList() }
        .distinct()

    /** ISO 8601 date periods as the eCHIS plans write them: P0D, P42D, P6W, P9M, P1Y. */
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
  }
}

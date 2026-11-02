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
package dev.ohs.vaxlink.feature.patient.profile

import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.PatientAllergyState
import dev.ohs.player.generated.state.PatientCarePlanState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientConditionState
import dev.ohs.player.generated.state.PatientFacilityVisitState
import dev.ohs.player.generated.state.PatientFollowUpState
import dev.ohs.player.generated.state.PatientImmunizationState
import dev.ohs.player.generated.state.PatientMedicationStatementState
import dev.ohs.player.generated.state.PatientReferralOutcomeState
import dev.ohs.player.generated.state.PatientReferralState
import dev.ohs.player.generated.state.PatientRelationshipState
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.player.generated.state.PatientTreatmentState
import dev.ohs.player.generated.state.PatientVitalState
import dev.ohs.vaxlink.util.calendarDate
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlinx.datetime.LocalDate

/** Everything the summary profile shows, one list per section, each extracted by a ViewJoinMap. */
data class ProfileUiState(
  val patient: PatientSummaryState? = null,
  val household: HouseholdListState? = null,
  val relationships: List<PatientRelationshipState> = emptyList(),
  val immunizations: List<PatientImmunizationState> = emptyList(),
  val conditions: List<PatientConditionState> = emptyList(),
  val allergies: List<PatientAllergyState> = emptyList(),
  val medicationStatements: List<PatientMedicationStatementState> = emptyList(),
  val careTasks: List<PatientCareTaskState> = emptyList(),
  val carePlans: List<PatientCarePlanState> = emptyList(),
  val referrals: List<PatientReferralState> = emptyList(),
  val outcomes: List<PatientReferralOutcomeState> = emptyList(),
  val treatments: List<PatientTreatmentState> = emptyList(),
  val followUps: List<PatientFollowUpState> = emptyList(),
  val visits: List<PatientFacilityVisitState> = emptyList(),
  val vitals: List<PatientVitalState> = emptyList(),
  /**
   * Where each scheduled activity sits in its plan, from
   * [dev.ohs.vaxlink.workflow.ScheduleCatalogue].
   */
  val placements: Map<String, TaskPlacement> = emptyMap(),
) {
  /** True while an Immunization on the record has not been verified by the clinic. */
  val needsVerification: Boolean
    get() = immunizations.any { it.verified != true }

  /** True while a referral to the clinic is open. */
  val referred: Boolean
    get() = referrals.any { it.referralState == "active" }

  /** The plan a Task belongs to, falling back to its activity when the plan is unknown. */
  fun planOf(task: PatientCareTaskState): String =
    placements[task.activityUrl()]?.planUrl ?: task.activityUrl()

  /** Tasks still to do: not completed, cancelled or otherwise closed. */
  val openTasks: List<PatientCareTaskState>
    get() = careTasks.filter { it.isOpen }

  /** Open Tasks whose window has closed. */
  fun overdue(today: LocalDate): List<PatientCareTaskState> =
    openTasks.filter { it.dueBy.calendarDate()?.let { end -> end < today.toString() } == true }

  /**
   * What the health worker acts on next: everything overdue, then the earliest contact of each plan
   * still ahead. A contact is the set of doses that fall due together.
   */
  fun dueNext(today: LocalDate): List<PatientCareTaskState> {
    val overdue = overdue(today)
    val ahead = openTasks - overdue.toSet()
    val next =
      ahead
        .groupBy { planOf(it) }
        .values
        .flatMap { plan ->
          val first = plan.minOf { offsetOf(it) }
          plan.filter { offsetOf(it) == first }
        }
    return (overdue + next).sortedBy { it.dueBy.calendarDate() }
  }

  /**
   * Days after the plan's anchor that [task] falls due; 0 when its plan is not in the catalogue.
   */
  fun offsetOf(task: PatientCareTaskState): Int = placements[task.activityUrl()]?.offsetDays ?: 0

  /** Everything that has already happened, newest first, for the Past view. */
  fun past(): List<PastEntry> =
    buildList {
        immunizations.forEach {
          add(
            PastEntry(
              it.givenOn.calendarDate(),
              PastKind.Immunisation,
              it.vaccineName.orEmpty(),
              listOfNotNull(it.laoDoseDisplay, it.lotNumber?.let { lot -> "Lot $lot" })
                .joinToString(" · "),
            )
          )
        }
        visits.forEach {
          add(PastEntry(it.arrivedOn.calendarDate(), PastKind.Visit, it.visitReason.orEmpty()))
        }
        vitals.forEach {
          add(
            PastEntry(
              it.measuredOn.calendarDate(),
              PastKind.Observation,
              listOfNotNull(it.vitalName, it.vitalValue, it.vitalUnit).joinToString(" "),
            )
          )
        }
        treatments.forEach {
          add(PastEntry(it.prescribedOn.calendarDate(), PastKind.Medication, it.medicine.orEmpty()))
        }
        referrals.forEach {
          add(
            PastEntry(
              it.referredOn.calendarDate(),
              PastKind.Referral,
              "",
              listOfNotNull(
                  it.referralPriority?.replaceFirstChar { c -> c.uppercaseChar() },
                  it.referralState,
                  it.referralNote,
                )
                .joinToString(" · "),
            )
          )
        }
        outcomes.forEach {
          add(PastEntry(it.outcomeOn.calendarDate(), PastKind.Referral, it.outcomeText.orEmpty()))
        }
        followUps
          .filter { it.followUpState == "completed" }
          .forEach {
            add(PastEntry(it.dueOn.calendarDate(), PastKind.FollowUp, it.followUpReason.orEmpty()))
          }
        carePlans.forEach {
          add(
            PastEntry(it.startedOn.calendarDate(), PastKind.Programme, it.carePlanTitle.orEmpty())
          )
        }
        careTasks
          .filter { it.taskStatus == "completed" }
          .forEach {
            add(PastEntry(it.dueBy.calendarDate(), PastKind.Task, it.taskTitle.orEmpty()))
          }
      }
      .filter { it.date != null }
      .sortedByDescending { it.date }
}

/** The kinds of event the Past view lists and filters by. */
enum class PastKind {
  Immunisation,
  Visit,
  Observation,
  Medication,
  Referral,
  FollowUp,
  Programme,
  Task,
}

/** One dated line of the Past view. */
data class PastEntry(
  val date: String?,
  val kind: PastKind,
  val title: String,
  val detail: String = "",
)

/** A Task is open until it reaches a terminal state. */
val PatientCareTaskState.isOpen: Boolean
  get() = taskStatus !in setOf("completed", "cancelled", "rejected", "failed", "entered-in-error")

private val URL = Regex("""https?://[^\s)|]+""")

/**
 * The ActivityDefinition URL a Task was made from. The extracted column can come back wrapped, as
 * `Canonical(value=https://…)`, so the bare URL is picked out of it.
 */
fun PatientCareTaskState.activityUrl(): String = taskPlan?.let(::bareUrl).orEmpty()

/** A canonical URL as extracted, with any `Canonical(value=…)` wrapper removed. */
fun bareUrl(raw: String): String = URL.find(raw)?.value ?: raw

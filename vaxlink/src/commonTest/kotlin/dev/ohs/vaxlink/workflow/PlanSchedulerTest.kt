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

import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Integer
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.vaxlink.data.datasource.SampleFamily
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.util.calendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The eCHIS programmes through the generic plan runner: ANC, PNC, family planning and WASH. */
class PlanSchedulerTest {
  private val today = LocalDate(2026, 11, 1)
  private val repository = InMemorySampleFhirRepository()
  private val scheduler =
    PlanScheduler(
      repository,
      operatorFactory = {
        FhirOperator(InMemoryWorkflowRepository(), resolver = BundledProtocols.load())
      },
      protocolsFactory = { BundledProtocols.load() },
      today = { today },
    )

  private suspend fun carePlans(planUrl: String) =
    repository.all("CarePlan").filterIsInstance<CarePlan>().filter {
      it.instantiatesCanonical.any { c -> c.value == planUrl }
    }

  private fun List<Task>.byAction(suffix: String) = single {
    it.instantiatesCanonical?.value?.endsWith(suffix) == true
  }

  @Test
  fun anAncHistoryPastItsLmpIsDoneMissedAndEndedByTheDelivery() = runTest {
    SampleFamily.seed(repository, scheduler = null, today)

    scheduler.refresh(SampleFamily.mother)

    val anc = scheduler.tasksFor("Patient/${SampleFamily.MOTHER_ID}", ANC_PLAN)
    assertEquals(8, anc.size)
    // Four contacts were had: the first four Tasks are complete, each pointing at its contact.
    (1..4).forEach { n ->
      val task = anc.byAction("anc-contact-$n")
      assertEquals(Task.TaskStatus.Completed, task.status.value, "contact $n")
      assertTrue(
        task.output
          .single()
          .value
          .asReference()
          ?.value
          ?.reference
          ?.value!!
          .startsWith("Observation/")
      )
    }
    // The plan ended with the delivery: contacts 5 to 7 never happened and their windows closed;
    // contact 8 was due the week of the birth and its window is still open today.
    (5..8).forEach { n ->
      val task = anc.byAction("anc-contact-$n")
      assertEquals(Task.TaskStatus.Cancelled, task.status.value, "contact $n")
      val reason = if (n < 8) PlanScheduler.TASK_MISSED else PlanScheduler.TASK_PLAN_ENDED
      assertEquals(reason, task.statusReason?.text?.value, "contact $n")
    }
    // Contact 1 is due twelve weeks after the LMP, two weeks either side.
    val first = anc.byAction("anc-contact-1")
    assertEquals("2026-03-29", first.restriction?.period?.start?.value.calendarDate())
    assertEquals("2026-04-26", first.restriction?.period?.end?.value.calendarDate())
    assertEquals(CarePlan.RequestStatus.Completed, carePlans(ANC_PLAN).single().status.value)
  }

  @Test
  fun theDeliveryEnrolsPostnatalCareFromTheDeliveryDate() = runTest {
    SampleFamily.seed(repository, scheduler = null, today)

    scheduler.refresh(SampleFamily.mother)

    val pnc = carePlans(PNC_PLAN).single()
    assertEquals(CarePlan.RequestStatus.Active, pnc.status.value)
    assertEquals("2026-10-25", pnc.period?.start?.value.calendarDate())
    val tasks = scheduler.tasksFor("Patient/${SampleFamily.MOTHER_ID}", PNC_PLAN)
    assertEquals(4, tasks.size)
    assertTrue(tasks.all { it.status.value == Task.TaskStatus.Requested })
    // PNC 4 is due six weeks after the birth, a week either side.
    val sixWeeks = tasks.byAction("pnc-pnc-4")
    assertEquals("2026-11-29", sixWeeks.restriction?.period?.start?.value.calendarDate())
    assertEquals("2026-12-13", sixWeeks.restriction?.period?.end?.value.calendarDate())
  }

  @Test
  fun aPostnatalContactCompletesTheFirstPncTask() = runTest {
    SampleFamily.seed(repository, scheduler, today)
    val contact =
      recordPncContact(response("date" to date("2026-10-26")), SampleFamily.MOTHER_ID, today)
    contact.resources.forEach { repository.upsert(it) }

    val tasks = scheduler.refresh(SampleFamily.mother, PNC_PLAN)

    assertEquals(Task.TaskStatus.Completed, tasks.byAction("pnc-pnc-1").status.value)
    assertEquals(3, tasks.count { it.status.value == Task.TaskStatus.Requested })
  }

  @Test
  fun theMethodChoosesTheFamilyPlanningPlanAndTheInjectionSetsTheAnchor() = runTest {
    repository.upsert(SampleFamily.mother)
    val enrolment =
      enrolFp(
        response(
          "date" to date("2026-10-30"),
          "interested" to coding(V2_YES_NO_SYSTEM, "yes"),
          "method" to coding(LOINC, "LA27907-7", "Injectable"),
          "dmpa-administered" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        SampleFamily.MOTHER_ID,
        today,
      )
    enrolment.resources.forEach { repository.upsert(it) }

    val tasks = scheduler.refresh(SampleFamily.mother)

    assertEquals(
      FP_INJECTABLE_PLAN,
      enrolment.carePlans.single().instantiatesCanonical.single().value,
    )
    assertTrue(carePlans(FP_ORAL_PLAN).isEmpty() && carePlans(FP_LONGTERM_PLAN).isEmpty())
    val reinjection = tasks.byAction("fp-injectable-reinjection")
    assertEquals(Task.TaskStatus.Requested, reinjection.status.value)
    // Three months after the injection on 30 October, a week before and two after.
    assertEquals("2027-01-23", reinjection.restriction?.period?.start?.value.calendarDate())
    assertEquals("2027-02-13", reinjection.restriction?.period?.end?.value.calendarDate())
  }

  @Test
  fun aLaterInjectionMovesTheReinjectionTaskAlong() = runTest {
    repository.upsert(SampleFamily.mother)
    enrolFp(
        response(
          "date" to date("2026-07-30"),
          "interested" to coding(V2_YES_NO_SYSTEM, "yes"),
          "method" to coding(LOINC, "LA27907-7", "Injectable"),
          "dmpa-administered" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        SampleFamily.MOTHER_ID,
        today,
      )
      .resources
      .forEach { repository.upsert(it) }
    val first = scheduler.refresh(SampleFamily.mother).byAction("fp-injectable-reinjection")
    recordFpVisit(
        response(
          "date" to date("2026-10-30"),
          "dmpa-administered" to coding(V2_YES_NO_SYSTEM, "yes"),
        ),
        SampleFamily.MOTHER_ID,
        today,
      )
      .resources
      .forEach { repository.upsert(it) }

    val tasks = scheduler.refresh(SampleFamily.mother, FP_INJECTABLE_PLAN)

    assertEquals("2026-10-23", first.restriction?.period?.start?.value.calendarDate())
    assertEquals(2, tasks.size)
    assertEquals(Task.TaskStatus.Completed, tasks.single { it.id == first.id }.status.value)
    val next = tasks.single { it.id != first.id }
    assertEquals(Task.TaskStatus.Requested, next.status.value)
    assertEquals("2027-01-23", next.restriction?.period?.start?.value.calendarDate())
  }

  @Test
  fun aHouseholdIsEnrolledInTheWashCheckOnFirstSight() = runTest {
    repository.upsert(SampleFamily.household)

    val tasks = scheduler.refreshHousehold(SampleFamily.household)

    val plan = carePlans(WASH_PLAN).single()
    assertEquals("Group/${SampleFamily.HOUSEHOLD_ID}", plan.subject.reference?.value)
    val check = tasks.byAction("household-wash-check-wash-check")
    assertEquals("Group/${SampleFamily.HOUSEHOLD_ID}", check.`for`?.reference?.value)
    assertEquals(Task.TaskStatus.Requested, check.status.value)
    // A month after enrolment today, a week before and two after.
    assertEquals("2026-11-24", check.restriction?.period?.start?.value.calendarDate())
    assertEquals("2026-12-15", check.restriction?.period?.end?.value.calendarDate())
  }

  @Test
  fun aWashCheckRollsTheHouseholdTaskForwardAMonth() = runTest {
    repository.upsert(SampleFamily.household)
    val first = scheduler.refreshHousehold(SampleFamily.household).single()
    recordWashCheck(
        response(
          "date" to date("2026-11-20"),
          "latrine" to Value.Boolean(dev.ohs.fhir.model.r4.Boolean(value = true)),
        ),
        SampleFamily.HOUSEHOLD_ID,
        today,
      )
      .resources
      .forEach { repository.upsert(it) }

    val tasks = scheduler.refreshHousehold(SampleFamily.household)

    assertEquals(Task.TaskStatus.Completed, tasks.single { it.id == first.id }.status.value)
    val next = assertNotNull(tasks.singleOrNull { it.id != first.id })
    assertEquals("2026-12-13", next.restriction?.period?.start?.value.calendarDate())
  }

  @Test
  fun theLaoScheduleStillOnlyAppliesToChildren() = runTest {
    repository.upsert(SampleFamily.mother)

    scheduler.refresh(SampleFamily.mother)

    assertTrue(carePlans(LAO_SCHEDULE_PLAN).isEmpty())
    assertTrue(scheduler.tasksFor("Patient/${SampleFamily.MOTHER_ID}", LAO_SCHEDULE_PLAN).isEmpty())
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

  @Suppress("unused") private fun int(value: Int) = Value.Integer(Integer(value = value))
}

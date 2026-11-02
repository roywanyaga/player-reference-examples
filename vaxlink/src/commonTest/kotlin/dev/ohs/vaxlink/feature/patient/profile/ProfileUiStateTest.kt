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

import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientImmunizationState
import dev.ohs.player.generated.state.PatientVitalState
import dev.ohs.vaxlink.workflow.ContactUnit
import dev.ohs.vaxlink.workflow.LAO_SCHEDULE_PLAN
import dev.ohs.vaxlink.workflow.ScheduleCatalogue
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ProfileUiStateTest {
  private val today = LocalDate(2026, 11, 20)

  private fun day(date: String) = FhirDateTime.Date(LocalDate.parse(date))

  private fun task(activity: String, title: String, status: String, from: String, by: String) =
    PatientCareTaskState(
      careTaskId = activity,
      taskTitle = title,
      taskPlan = "act/$activity",
      taskStatus = status,
      dueFrom = day(from),
      dueBy = day(by),
    )

  private val placements =
    mapOf(
      "act/hb0" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 0),
      "act/penta-1" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 42),
      "act/opv-1" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 42),
      "act/penta-2" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 70),
      "act/mr-1" to TaskPlacement(LAO_SCHEDULE_PLAN, "Lao", 270),
    )

  private val state =
    ProfileUiState(
      careTasks =
        listOf(
          task("hb0", "HB0", "completed", "2026-10-14", "2026-10-21"),
          task("penta-1", "Penta 1", "requested", "2026-11-18", "2026-12-23"),
          task("opv-1", "OPV 1", "requested", "2026-11-18", "2026-12-23"),
          task("penta-2", "Penta 2", "requested", "2026-12-16", "2027-01-20"),
          task("mr-1", "MR 1", "requested", "2027-07-11", "2027-09-09"),
        ),
      placements = placements,
    )

  @Test
  fun everyDoseBelongsToTheLaoPlan() {
    assertEquals(setOf(LAO_SCHEDULE_PLAN), state.careTasks.map { state.planOf(it) }.toSet())
  }

  @Test
  fun dueNextIsTheNextContactOnly() {
    val next = state.dueNext(today).mapNotNull { it.taskTitle }
    assertEquals(listOf("OPV 1", "Penta 1"), next.sorted())
  }

  @Test
  fun overdueDosesComeFirstAndStayListed() {
    val late =
      state.copy(
        careTasks = state.careTasks + task("x", "Late", "requested", "2026-10-01", "2026-11-01")
      )
    val next = late.dueNext(today).mapNotNull { it.taskTitle }
    assertEquals("Late", next.first())
    assertTrue("Penta 1" in next)
    assertEquals(1, late.overdue(today).size)
  }

  @Test
  fun completedTasksAreNeverOpen() {
    assertEquals(4, state.openTasks.size)
  }

  @Test
  fun pastListsNewestFirstAndSkipsUndatedEvents() {
    val withHistory =
      ProfileUiState(
        immunizations =
          listOf(PatientImmunizationState(vaccineName = "HepB", givenOn = day("2026-10-21"))),
        vitals =
          listOf(
            PatientVitalState(
              vitalName = "Weight",
              vitalValue = "3.4",
              measuredOn = day("2026-11-02"),
            ),
            PatientVitalState(vitalName = "No date"),
          ),
      )
    val past = withHistory.past()
    assertEquals(listOf("Weight 3.4", "HepB"), past.map { it.title })
    assertEquals(listOf(PastKind.Observation, PastKind.Immunisation), past.map { it.kind })
  }

  @Test
  fun contactsReadAsBirthWeeksAndMonths() {
    assertEquals(ContactUnit.Birth, ScheduleCatalogue.contactOf(0).unit)
    assertEquals(6, ScheduleCatalogue.contactOf(42).count)
    assertEquals(ContactUnit.Weeks, ScheduleCatalogue.contactOf(98).unit)
    assertEquals(9, ScheduleCatalogue.contactOf(270).count)
    assertEquals(12, ScheduleCatalogue.contactOf(365).count)
    assertEquals(ContactUnit.Months, ScheduleCatalogue.contactOf(365).unit)
  }

  @Test
  fun bundledLaoPlanPlacesEveryDoseOnItsContact() = runTest {
    val catalogue = ScheduleCatalogue.load()
    val prefix = "https://nawitech.github.io/vaxlink-ig/ActivityDefinition/LaoEpi-"
    assertEquals(0, catalogue.getValue(prefix + "hb0").offsetDays)
    assertEquals(42, catalogue.getValue(prefix + "penta-1").offsetDays)
    assertEquals(98, catalogue.getValue(prefix + "opv-3").offsetDays)
    assertEquals(365, catalogue.getValue(prefix + "mr-2").offsetDays)
    assertEquals(LAO_SCHEDULE_PLAN, catalogue.getValue(prefix + "je").planUrl)
  }
}

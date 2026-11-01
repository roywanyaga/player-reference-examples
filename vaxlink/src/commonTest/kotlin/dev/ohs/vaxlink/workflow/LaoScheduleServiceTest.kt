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

import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.Task
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.util.calendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The Lao schedule as Tasks on a newborn's record, driven by the bundled PlanDefinition. */
class LaoScheduleServiceTest {
  private val repository = InMemorySampleFhirRepository()
  private val service =
    LaoScheduleService(
      repository,
      operatorFactory = {
        FhirOperator(InMemoryWorkflowRepository(), resolver = BundledProtocols.load())
      },
      protocolsFactory = { BundledProtocols.load() },
      today = { LocalDate(2026, 11, 1) },
    )

  private suspend fun store(json: String): Resource =
    FhirJson.instance.decodeFromString(Resource.serializer(), json).also { repository.upsert(it) }

  private suspend fun newborn() =
    store(
      """{"resourceType":"Patient","id":"baby","name":[{"given":["Chanthala"],"family":"SOUVANNALATH"}],
         "gender":"female","birthDate":"2026-10-25"}"""
    )
      as Patient

  private fun hb0(status: String, code: String = "HB0-24H") =
    """{"resourceType":"Immunization","id":"imm-hb0","status":"$status",
        "vaccineCode":{"coding":[
          {"system":"http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds","code":"a3K3X000006MiiXUAS"},
          {"system":"$LAO_DOSE_SYSTEM","code":"$code","display":"HB0"}]},
        "patient":{"reference":"Patient/baby"},"occurrenceDateTime":"2026-10-25","primarySource":false}"""

  @Test
  fun aNewbornGetsEveryDoseAsARequestedTaskWithItsWindow() = runTest {
    val tasks = service.refresh(newborn())

    assertEquals(19, tasks.size)
    assertTrue(tasks.all { it.status.value == Task.TaskStatus.Requested })
    assertEquals(19, tasks.map { it.instantiatesCanonical?.value }.distinct().size)
    val hb0 = tasks.single { it.instantiatesCanonical?.value?.endsWith("LaoEpi-hb0") == true }
    assertEquals("HB0", hb0.code?.text?.value)
    assertEquals("Patient/baby", hb0.`for`?.reference?.value)
    assertEquals("2026-10-25", hb0.restriction?.period?.start?.value.calendarDate())
    assertEquals("2026-11-01", hb0.restriction?.period?.end?.value.calendarDate())
    val penta1 =
      tasks.single { it.instantiatesCanonical?.value?.endsWith("LaoEpi-penta-1") == true }
    // Birth + 42 days = 2026-12-06; a week before and four weeks after.
    assertEquals("2026-11-29", penta1.restriction?.period?.start?.value.calendarDate())
    assertEquals("2027-01-03", penta1.restriction?.period?.end?.value.calendarDate())
    assertEquals(19, repository.all("Task").size)
  }

  @Test
  fun aRerunKeepsTheStoredTasksInsteadOfDuplicatingThem() = runTest {
    val baby = newborn()
    val first = service.refresh(baby).map { it.id }.toSet()

    val second = service.refresh(baby).map { it.id }.toSet()

    assertEquals(first, second)
    assertEquals(19, repository.all("Task").size)
  }

  @Test
  fun aCompletedImmunizationWithTheLaoCodingCompletesItsTask() = runTest {
    val baby = newborn()
    service.refresh(baby)
    store(hb0("completed"))

    val tasks = service.refresh(baby)

    val hb0 = tasks.single { it.instantiatesCanonical?.value?.endsWith("LaoEpi-hb0") == true }
    assertEquals(Task.TaskStatus.Completed, hb0.status.value)
    assertEquals(
      "Immunization/imm-hb0",
      assertNotNull(hb0.output.single().value.asReference()).value.reference?.value,
    )
    assertEquals(18, tasks.count { it.status.value == Task.TaskStatus.Requested })
    assertEquals(19, repository.all("Task").size)
  }

  @Test
  fun anImmunizationThatIsNotCompletedLeavesTheTaskOpen() = runTest {
    val baby = newborn()
    service.refresh(baby)
    store(hb0("not-done"))

    val hb0 =
      service.refresh(baby).single {
        it.instantiatesCanonical?.value?.endsWith("LaoEpi-hb0") == true
      }

    assertEquals(Task.TaskStatus.Requested, hb0.status.value)
  }

  @Test
  fun thePlanDescribesNineteenDosesWithTheirCodes() = runTest {
    val doses = service.doses()

    assertEquals(19, doses.size)
    assertEquals(listOf("HB0-24H", "HB0-7D"), doses.first().doseCodes)
    assertEquals("Penta 1", doses[1].title)
  }

  @Test
  fun isoPeriodsParse() {
    assertEquals(0, LaoScheduleService.parsePeriod("P0D").days)
    assertEquals(42, LaoScheduleService.parsePeriod("P42D").days)
    assertEquals(42, LaoScheduleService.parsePeriod("P6W").days)
    assertEquals(9, LaoScheduleService.parsePeriod("P9M").months)
    assertEquals(1, LaoScheduleService.parsePeriod("P1Y").years)
  }
}

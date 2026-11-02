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
package dev.ohs.vaxlink.feature.register

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.FP_ORAL_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ProgrammeRegisterTest {
  private val today = LocalDate(2026, 11, 20)
  private val placements =
    mapOf(
      "act/anc-1" to TaskPlacement(ANC_PLAN, "ANC", 0),
      "act/anc-2" to TaskPlacement(ANC_PLAN, "ANC", 56),
      "act/pnc-1" to TaskPlacement(PNC_PLAN, "PNC", 2),
    )

  private suspend fun InMemorySampleFhirRepository.add(json: String) {
    upsert(FhirJson.instance.decodeFromString(Resource.serializer(), json))
  }

  private suspend fun InMemorySampleFhirRepository.woman(id: String, given: String) =
    add(
      """{"resourceType":"Patient","id":"$id","name":[{"given":["$given"],"family":"Souvannalath"}]}"""
    )

  private suspend fun InMemorySampleFhirRepository.plan(
    id: String,
    patient: String,
    plan: String,
    status: String = "active",
    start: String = "2026-09-01",
  ) =
    add(
      """{"resourceType":"CarePlan","id":"$id","status":"$status","intent":"plan",
         "instantiatesCanonical":["$plan"],"subject":{"reference":"Patient/$patient"},
         "period":{"start":"$start"}}"""
    )

  private suspend fun InMemorySampleFhirRepository.task(
    id: String,
    patient: String,
    activity: String,
    due: String,
    status: String = "requested",
  ) =
    add(
      """{"resourceType":"Task","id":"$id","status":"$status","intent":"plan",
         "instantiatesCanonical":"$activity","description":"Visit $id",
         "for":{"reference":"Patient/$patient"},"restriction":{"period":{"end":"$due"}}}"""
    )

  @Test
  fun listsOnlyActiveEnrolmentsInTheProgramme() = runTest {
    val repo = InMemorySampleFhirRepository()
    repo.woman("a", "Mali")
    repo.woman("b", "Noy")
    repo.woman("c", "Ketsana")
    repo.plan("p1", "a", ANC_PLAN)
    repo.plan("p2", "b", ANC_PLAN, status = "completed")
    repo.plan("p3", "c", PNC_PLAN)

    val anc = repo.programmeRegister(Programme.Anc, placements, today)
    assertEquals(listOf("Mali Souvannalath"), anc.map { it.name })
    assertEquals("2026-09-01", anc.single().enrolledOn)
    assertEquals(
      listOf("c"),
      repo.programmeRegister(Programme.Pnc, placements, today).map { it.patientId },
    )
    assertEquals(emptyList(), repo.programmeRegister(Programme.Fp, placements, today))
  }

  @Test
  fun familyPlanningCoversEveryMethodPlan() = runTest {
    val repo = InMemorySampleFhirRepository()
    repo.woman("a", "Mali")
    repo.plan("p1", "a", FP_ORAL_PLAN)
    assertEquals(
      listOf("a"),
      repo.programmeRegister(Programme.Fp, placements, today).map { it.patientId },
    )
  }

  @Test
  fun overdueComeFirstThenByNextDueDate() = runTest {
    val repo = InMemorySampleFhirRepository()
    listOf("a" to "Mali", "b" to "Noy", "c" to "Ketsana").forEach { (id, n) ->
      repo.woman(id, n)
      repo.plan("plan-$id", id, ANC_PLAN)
    }
    repo.task("t-a", "a", "act/anc-1", "2026-12-10")
    repo.task("t-b", "b", "act/anc-1", "2026-11-01")
    repo.task("t-c", "c", "act/anc-2", "2026-11-25")
    // A finished visit and another programme's visit never count.
    repo.task("t-done", "c", "act/anc-1", "2026-10-01", status = "completed")
    repo.task("t-pnc", "a", "act/pnc-1", "2026-11-02")

    val register = repo.programmeRegister(Programme.Anc, placements, today)

    assertEquals(listOf("b", "c", "a"), register.map { it.patientId })
    assertEquals(listOf(true, false, false), register.map { it.overdue })
    assertEquals("Visit t-b", register.first().nextTitle)
    assertEquals("2026-11-25", register[1].nextDue)
  }

  @Test
  fun anEnrolledPersonWithNothingScheduledIsStillListed() = runTest {
    val repo = InMemorySampleFhirRepository()
    repo.woman("a", "Mali")
    repo.plan("p1", "a", ANC_PLAN)
    val entry = repo.programmeRegister(Programme.Anc, placements, today).single()
    assertEquals(null, entry.nextDue)
    assertEquals(false, entry.overdue)
  }
}

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
package dev.ohs.vaxlink.data.sync

import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.auth.UserContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Types and params the org-scoped gateway allows; anything else is a 403 at sync time. NE-2 adds
 * the household and record types (Group, RelatedPerson, Immunization, Condition,
 * MedicationStatement, AllergyIntolerance), which the gateway's allow-list has to gain too.
 */
private val GATEWAY_ALLOWED_TYPES =
  setOf(
    "Patient",
    "Group",
    "RelatedPerson",
    "Immunization",
    "Encounter",
    "Observation",
    "Condition",
    "AllergyIntolerance",
    "QuestionnaireResponse",
    "ServiceRequest",
    "MedicationRequest",
    "MedicationStatement",
    "Task",
    "CarePlan",
    "Procedure",
    "PractitionerRole",
    "Location",
    "Organization",
    "Practitioner",
    "Questionnaire",
  )
private val GATEWAY_STRIPPED_PARAMS =
  setOf("_include", "_revinclude", "_filter", "_contained", "_containedType")

class SyncConfigFilesTest {

  @Test
  fun everyRoleConfigUsesOnlyWhatTheGatewayAllows() {
    AppRole.entries.forEach { role ->
      val config =
        Json.decodeFromString<SyncConfig>(
          File("src/commonMain/composeResources/files/sync/${role.code}.json").readText()
        )
      config.resources.forEach {
        assertTrue(it.type in GATEWAY_ALLOWED_TYPES, "${role.code}: ${it.type}")
        assertTrue(
          it.params.keys.none(GATEWAY_STRIPPED_PARAMS::contains),
          "${role.code}: ${it.params}",
        )
      }
      SyncConfigResolver.resolve(config, UserContext(role, "p", "o", "l"))
    }
  }

  @Test
  fun noEntryFiltersByStatusSoClosuresElsewhereArrive() {
    AppRole.entries.forEach { role ->
      val config =
        Json.decodeFromString<SyncConfig>(
          File("src/commonMain/composeResources/files/sync/${role.code}.json").readText()
        )
      config.resources.forEach {
        assertTrue("status" !in it.params, "${role.code} ${it.type}: ${it.params}")
      }
    }
  }
}

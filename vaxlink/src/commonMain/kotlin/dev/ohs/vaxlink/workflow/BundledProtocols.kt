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
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.CanonicalResolver
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.util.FhirJson
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * PlanDefinition and ActivityDefinition files bundled under `files/protocols/`: the Lao PDR
 * immunisation schedule from the VaxLink IG, one ActivityDefinition per dose.
 */
private val PROTOCOL_FILES: List<String> =
  listOf(
    "PlanDefinition-LaoEpiSchedule.json",
    "ActivityDefinition-LaoEpi-hb0.json",
    "ActivityDefinition-LaoEpi-penta-1.json",
    "ActivityDefinition-LaoEpi-penta-2.json",
    "ActivityDefinition-LaoEpi-penta-3.json",
    "ActivityDefinition-LaoEpi-opv-1.json",
    "ActivityDefinition-LaoEpi-opv-2.json",
    "ActivityDefinition-LaoEpi-opv-3.json",
    "ActivityDefinition-LaoEpi-pcv-1.json",
    "ActivityDefinition-LaoEpi-pcv-2.json",
    "ActivityDefinition-LaoEpi-pcv-3.json",
    "ActivityDefinition-LaoEpi-rota-1.json",
    "ActivityDefinition-LaoEpi-rota-2.json",
    "ActivityDefinition-LaoEpi-rota-3.json",
    "ActivityDefinition-LaoEpi-ipv-1.json",
    "ActivityDefinition-LaoEpi-ipv-2.json",
    "ActivityDefinition-LaoEpi-ipv-3.json",
    "ActivityDefinition-LaoEpi-mr-1.json",
    "ActivityDefinition-LaoEpi-je.json",
    "ActivityDefinition-LaoEpi-mr-2.json",
    // The parents' programmes and the household check, ported from Nawi's eCHIS configuration.
    "PlanDefinition-Anc.json",
    "ActivityDefinition-Anc-contact-1.json",
    "ActivityDefinition-Anc-contact-2.json",
    "ActivityDefinition-Anc-contact-3.json",
    "ActivityDefinition-Anc-contact-4.json",
    "ActivityDefinition-Anc-contact-5.json",
    "ActivityDefinition-Anc-contact-6.json",
    "ActivityDefinition-Anc-contact-7.json",
    "ActivityDefinition-Anc-contact-8.json",
    "PlanDefinition-Pnc.json",
    "ActivityDefinition-Pnc-pnc-1.json",
    "ActivityDefinition-Pnc-pnc-2.json",
    "ActivityDefinition-Pnc-pnc-3.json",
    "ActivityDefinition-Pnc-pnc-4.json",
    "PlanDefinition-FpInjectable.json",
    "ActivityDefinition-FpInjectable-reinjection.json",
    "PlanDefinition-FpLongterm.json",
    "ActivityDefinition-FpLongterm-fp-longterm-visit.json",
    "PlanDefinition-FpOral.json",
    "ActivityDefinition-FpOral-fp-oral-visit.json",
    "PlanDefinition-HouseholdWashCheck.json",
    "ActivityDefinition-HouseholdWashCheck-wash-check.json",
  )

class BundledProtocols(private val resources: List<Resource>) : CanonicalResolver {
  /** Every bundled PlanDefinition, in file order. */
  fun plans(): List<PlanDefinition> = resources.filterIsInstance<PlanDefinition>()

  override suspend fun resolve(type: ResourceType, canonical: String): Resource? {
    val url = canonical.substringBefore('|')
    return resources.firstOrNull { it.resourceType == type.code && it.url() == url }
  }

  private fun Resource.url(): String? =
    when (this) {
      is PlanDefinition -> url?.value
      is ActivityDefinition -> url?.value
      else -> null
    }

  companion object {
    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): BundledProtocols =
      BundledProtocols(
        PROTOCOL_FILES.map {
          FhirJson.instance.decodeFromString(
            Resource.serializer(),
            Res.readBytes("files/protocols/$it").decodeToString(),
          )
        }
      )
  }
}

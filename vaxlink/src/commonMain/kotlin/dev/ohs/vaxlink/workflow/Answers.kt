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

import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.vaxlink.generateId

private fun List<QuestionnaireResponse.Item>.flatten(): List<QuestionnaireResponse.Item> = flatMap {
  listOf(it) + it.item.flatten() + it.answer.flatMap { answer -> answer.item.flatten() }
}

private fun QuestionnaireResponse.answer(linkId: String): Value? =
  item.flatten().firstOrNull { it.linkId.value == linkId }?.answer?.firstOrNull()?.value

fun QuestionnaireResponse.integer(linkId: String): Int? =
  (answer(linkId) as? Value.Integer)?.value?.value

fun QuestionnaireResponse.string(linkId: String): String? =
  (answer(linkId) as? Value.String)?.value?.value

fun QuestionnaireResponse.code(linkId: String): String? =
  (answer(linkId) as? Value.Coding)?.value?.code?.value

fun QuestionnaireResponse.display(linkId: String): String? =
  (answer(linkId) as? Value.Coding)?.value?.display?.value

fun QuestionnaireResponse.date(linkId: String): Date? = (answer(linkId) as? Value.Date)?.value

fun QuestionnaireResponse.decimal(linkId: String): FhirDecimal? =
  (answer(linkId) as? Value.Decimal)?.value?.value

/**
 * The Patient a registration response describes, managed by the registering user's organization.
 */
fun registrationPatient(response: QuestionnaireResponse, organizationId: String): Patient =
  Patient(
    id = generateId(),
    name =
      listOf(
        HumanName(
          given = listOfNotNull(response.string("given")).map { FhirString(value = it) },
          family = response.string("family")?.let { FhirString(value = it) },
        )
      ),
    gender =
      response.code("gender")?.let { Enumeration(value = AdministrativeGender.fromCode(it)) },
    birthDate = response.date("birth-date"),
    managingOrganization = Reference(reference = FhirString(value = "Organization/$organizationId")),
  )

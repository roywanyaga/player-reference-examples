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
package dev.ohs.vaxlink.data.datasource

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Integer
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.util.FhirJson
import dev.ohs.vaxlink.workflow.PlanScheduler
import dev.ohs.vaxlink.workflow.ProgrammeRecord
import dev.ohs.vaxlink.workflow.V2_YES_NO_SYSTEM
import dev.ohs.vaxlink.workflow.enrolAnc
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * The vignette's family as the Lao catchment seed carries it (scripts/seed-bundle.json): the
 * Souvannalath household in Ban Nongbuathong, the father as head, the mother, and the mother's
 * antenatal history ending in the birth abroad. Tests and demos seed from here; the device gets the
 * same ids from the gateway seed.
 */
object SampleFamily {
  const val ORGANIZATION_ID = "5e608359-ba7d-4a4c-a143-db40f32912a3"
  const val HOUSEHOLD_ID = "c7f3e9a1-5b2d-4e8c-a1f6-3d9b7c5e4a03"
  const val FATHER_ID = "3b1c6a7e-2f0d-4c7e-9a51-7d2e0b4c1a01"
  const val MOTHER_ID = "9e4d2c1b-7a63-4f08-b2d5-1c0e8f6a2b02"

  /** The baby was born in Thailand on this date; the ANC history ends here. */
  val DELIVERY_DATE = LocalDate(2026, 10, 25)

  /** Forty weeks before the birth, as the mother's card gives it. */
  val LMP: LocalDate = DELIVERY_DATE.minus(DatePeriod(days = 7 * 40))

  val father: Patient
    get() =
      decode(
        """{"resourceType":"Patient","id":"$FATHER_ID","active":true,
          "name":[{"use":"official","given":["Khamsing"],"family":"SOUVANNALATH"}],
          "gender":"male","birthDate":"1998-03-14",
          "address":[{"use":"home","line":["Ban Nongbuathong"],"city":"Vientiane","district":"Sikhottabong","state":"Vientiane Capital","country":"LA"}],
          "managingOrganization":{"reference":"Organization/$ORGANIZATION_ID"},
          "telecom":[{"system":"phone","value":"+856 20 9912 3344","use":"mobile"}]}"""
      )

  val mother: Patient
    get() =
      decode(
        """{"resourceType":"Patient","id":"$MOTHER_ID","active":true,
          "name":[{"use":"official","given":["Mali"],"family":"SOUVANNALATH"}],
          "gender":"female","birthDate":"2001-07-22",
          "address":[{"use":"home","line":["Ban Nongbuathong"],"city":"Vientiane","district":"Sikhottabong","state":"Vientiane Capital","country":"LA"}],
          "managingOrganization":{"reference":"Organization/$ORGANIZATION_ID"}}"""
      )

  val household: Group
    get() =
      decode(
        """{"resourceType":"Group","id":"$HOUSEHOLD_ID","active":true,"type":"person","actual":true,
          "code":{"coding":[{"system":"http://hl7.org/fhir/group-code","code":"household","display":"Household"}]},
          "name":"Souvannalath household",
          "managingEntity":{"reference":"Organization/$ORGANIZATION_ID","display":"Ban Nongbuathong Community Health Unit"},
          "characteristic":[{"code":{"coding":[{"system":"http://smartregister.org/fhir/CodeSystem/household","code":"village"}]},"valueCodeableConcept":{"text":"Ban Nongbuathong"},"exclude":false}],
          "member":[
            {"entity":{"reference":"Patient/$FATHER_ID"},"extension":[{"url":"http://smartregister.org/fhir/StructureDefinition/group-member-involvement","valueCodeableConcept":{"coding":[{"system":"http://hl7.org/fhir/group-involvement","code":"primary-contact","display":"Primary contact"}]}}]},
            {"entity":{"reference":"Patient/$MOTHER_ID"}}]}"""
      )

  /**
   * The mother's ANC history as the CHW would enter it on the enrol-anc form after the family comes
   * home: LMP known, four contacts had, the last at a Thai clinic, delivered abroad.
   */
  fun ancHistoryResponse(): QuestionnaireResponse =
    response(
      "lmp-known" to yes(),
      "lmp" to date(LMP),
      "anc-visits" to Value.Integer(Integer(value = 4)),
      "last-anc-visit" to date(DELIVERY_DATE.minus(DatePeriod(days = 7 * 6))),
      "anc-card" to yes(),
      "delivered" to yes(),
      "delivery-date" to date(DELIVERY_DATE),
      "delivery-place" to coding("http://snomed.info/sct", "22232009", "Hospital"),
      "delivery-abroad" to Value.String(FhirString(value = "Thailand")),
    )

  /** What the ANC history form produces for the mother. */
  fun motherAncHistory(today: LocalDate): ProgrammeRecord =
    enrolAnc(ancHistoryResponse(), MOTHER_ID, today)

  /** Stores the family and the mother's history, then runs the plans when a scheduler is given. */
  suspend fun seed(repository: FhirRepository, scheduler: PlanScheduler?, today: LocalDate) {
    listOf<Resource>(father, mother, household).forEach { repository.upsert(it) }
    motherAncHistory(today).resources.forEach { repository.upsert(it) }
    scheduler?.refresh(mother)
    scheduler?.refresh(household)
  }

  private inline fun <reified T : Resource> decode(json: String): T =
    FhirJson.instance.decodeFromString(Resource.serializer(), json) as T

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

  private fun yes() = coding(V2_YES_NO_SYSTEM, "yes", "Yes")

  private fun coding(system: String, code: String, display: String) =
    Value.Coding(
      Coding(
        system = Uri(value = system),
        code = Code(value = code),
        display = FhirString(value = display),
      )
    )

  private fun date(value: LocalDate) = Value.Date(Date(value = FhirDate.Date(value)))
}

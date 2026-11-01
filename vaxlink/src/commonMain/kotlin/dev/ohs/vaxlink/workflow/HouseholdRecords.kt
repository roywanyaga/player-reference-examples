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

import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.RelatedPerson
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import dev.ohs.vaxlink.generateId
import dev.ohs.vaxlink.util.displayName

/*
 * The household model, as Nawi's eCHIS configuration keeps it so the content ports between the two
 * platforms: a Group of type person, code group-code#household, managed by the CHW's Organization,
 * a village characteristic, members as Patient references, and the head of household as the member
 * carrying the group-member-involvement extension with group-involvement#primary-contact. Parents
 * of a newborn are RelatedPerson records on the child, pointing back at the parent's Patient.
 */

/** A registered household: the Group and the head, who is also a Patient. */
data class HouseholdRegistration(val household: Group, val head: Patient)

/**
 * A registered member: the Patient, the Group with the member added, and the relation to the head.
 */
data class MemberRegistration(
  val member: Patient,
  val household: Group,
  val relationToHead: RelatedPerson?,
)

/** A registered newborn with the RelatedPerson records naming the parents. */
data class NewbornRegistration(
  val child: Patient,
  val household: Group,
  val parents: List<RelatedPerson>,
)

/** From the eCHIS register-household form: the household and its head. */
fun registerHousehold(
  response: QuestionnaireResponse,
  organizationId: String,
): HouseholdRegistration {
  val head =
    person(
      response,
      givenId = "head-given-name",
      familyId = "head-family-name",
      sexId = "head-sex",
      birthDateId = "head-birth-date",
      phoneId = "head-phone",
      organizationId = organizationId,
    )
  val household =
    Group(
      id = generateId(),
      active = FhirBoolean(value = true),
      type = Enumeration(value = Group.GroupType.Person),
      actual = FhirBoolean(value = true),
      code = householdCode(),
      name =
        response.string("household-name")?.let { FhirString(value = it) }
          ?: FhirString(value = "${head.displayName()} household"),
      managingEntity = reference("Organization/$organizationId"),
      characteristic =
        listOfNotNull(
          response.string("village")?.let { village ->
            Group.Characteristic(
              code =
                CodeableConcept(
                  coding =
                    listOf(
                      Coding(
                        system = Uri(value = HOUSEHOLD_CHARACTERISTIC_SYSTEM),
                        code = Code(value = "village"),
                      )
                    )
                ),
              value =
                Group.Characteristic.Value.CodeableConcept(
                  CodeableConcept(text = FhirString(value = village))
                ),
              exclude = FhirBoolean(value = false),
            )
          }
        ),
      member = listOf(member(head.id!!, head = true)),
    )
  return HouseholdRegistration(household, head)
}

/** From the eCHIS register-member form: a member of [household], related to its head. */
fun registerMember(
  response: QuestionnaireResponse,
  household: Group,
  head: Patient?,
  organizationId: String,
): MemberRegistration {
  val member =
    person(
      response,
      givenId = "given-name",
      familyId = "family-name",
      sexId = "sex",
      birthDateId = "birth-date",
      phoneId = "phone",
      organizationId = organizationId,
    )
  val relation =
    response.code("relationship-to-head")?.let { code ->
      head?.let { relatedPerson(member.id!!, it, code, response.display("relationship-to-head")) }
    }
  return MemberRegistration(member, household.withMember(member.id!!), relation)
}

/** The newborn form: the child, added to the household and linked to the parents chosen. */
fun registerNewborn(
  response: QuestionnaireResponse,
  household: Group,
  mother: Patient?,
  father: Patient?,
  organizationId: String,
): NewbornRegistration {
  val family = response.string("family-name") ?: mother?.name?.firstOrNull()?.family?.value
  val child =
    person(
        response,
        givenId = "given-name",
        familyId = "family-name",
        sexId = "sex",
        birthDateId = "birth-date",
        phoneId = null,
        organizationId = organizationId,
      )
      .let { child ->
        if (family == null) child
        else child.copy(name = listOf(child.name.first().copy(family = FhirString(value = family))))
      }
  val parents =
    listOfNotNull(
      mother?.let { relatedPerson(child.id!!, it, "MTH", "Mother") },
      father?.let { relatedPerson(child.id!!, it, "FTH", "Father") },
    )
  return NewbornRegistration(child, household.withMember(child.id!!), parents)
}

/** The ids of the members a Group lists, head first. */
fun Group.memberIds(): List<String> =
  member
    .sortedByDescending { it.isHead() }
    .mapNotNull { it.entity.reference?.value }
    .map { it.removePrefix("Patient/") }

fun Group.headId(): String? =
  member.firstOrNull { it.isHead() }?.entity?.reference?.value?.removePrefix("Patient/")

fun Group.Member.isHead(): Boolean =
  extension.any { ext ->
    ext.url == GROUP_MEMBER_INVOLVEMENT_EXTENSION &&
      ext.value?.asCodeableConcept()?.value?.coding?.any {
        it.system?.value == GROUP_INVOLVEMENT_SYSTEM && it.code?.value == "primary-contact"
      } == true
  }

fun Group.withMember(patientId: String): Group =
  if (member.any { it.entity.reference?.value == "Patient/$patientId" }) this
  else copy(member = member + member(patientId, head = false))

private fun member(patientId: String, head: Boolean) =
  Group.Member(
    entity = reference("Patient/$patientId"),
    extension =
      if (!head) emptyList()
      else
        listOf(
          Extension(
            url = GROUP_MEMBER_INVOLVEMENT_EXTENSION,
            value =
              Extension.Value.CodeableConcept(
                CodeableConcept(
                  coding =
                    listOf(
                      Coding(
                        system = Uri(value = GROUP_INVOLVEMENT_SYSTEM),
                        code = Code(value = "primary-contact"),
                        display = FhirString(value = "Head of household"),
                      )
                    )
                )
              ),
          )
        ),
  )

private fun householdCode() =
  CodeableConcept(
    coding =
      listOf(
        Coding(
          system = Uri(value = GROUP_CODE_SYSTEM),
          code = Code(value = "household"),
          display = FhirString(value = "Household"),
        )
      )
  )

/** The related person as the child or member sees them: [related] is the parent or the head. */
private fun relatedPerson(patientId: String, related: Patient, code: String, display: String?) =
  RelatedPerson(
    id = generateId(),
    active = FhirBoolean(value = true),
    patient = reference("Patient/$patientId"),
    relationship =
      listOf(
        CodeableConcept(
          coding =
            listOf(
              Coding(
                system = Uri(value = V3_ROLE_CODE),
                code = Code(value = code),
                display = display?.let { FhirString(value = it) },
              )
            )
        )
      ),
    name = listOf(HumanName(text = FhirString(value = related.displayName()))),
    gender = related.gender,
    extension =
      listOf(
        Extension(
          url = RELATED_PATIENT_EXTENSION,
          value = Extension.Value.Reference(reference("Patient/${related.id}")),
        )
      ),
  )

private fun person(
  response: QuestionnaireResponse,
  givenId: String,
  familyId: String,
  sexId: String,
  birthDateId: String,
  phoneId: String?,
  organizationId: String,
): Patient =
  Patient(
    id = generateId(),
    active = FhirBoolean(value = true),
    name =
      listOf(
        HumanName(
          given = listOfNotNull(response.string(givenId)).map { FhirString(value = it) },
          family = response.string(familyId)?.let { FhirString(value = it) },
        )
      ),
    gender = response.code(sexId)?.let { Enumeration(value = AdministrativeGender.fromCode(it)) },
    birthDate = response.date(birthDateId),
    telecom =
      listOfNotNull(
        phoneId
          ?.let { response.string(it) }
          ?.let {
            ContactPoint(
              system = Enumeration(value = ContactPoint.ContactPointSystem.Phone),
              value = FhirString(value = it),
            )
          }
      ),
    managingOrganization = reference("Organization/$organizationId"),
  )

private fun reference(value: String) = Reference(reference = FhirString(value = value))

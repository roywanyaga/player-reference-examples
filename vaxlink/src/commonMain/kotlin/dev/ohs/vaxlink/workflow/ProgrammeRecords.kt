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

import dev.ohs.fhir.model.r4.Annotation
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.Markdown
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.QuestionnaireResponse.Item.Answer.Value
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.generateId
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/*
 * The parents' programmes and the household check, as the eCHIS forms record them. Each function
 * turns a QuestionnaireResponse into the resources the eCHIS extraction templates describe:
 * an Observation per contact with every answer as a component (code = linkId in the form's code
 * system, as eCHIS does), a CarePlan to enrol a plan, a referral when the form asked for one. The
 * templates are kept in the ported questionnaires for reference; this is the Kotlin equivalent.
 */

/** What one programme form produced, in the order it should be stored. */
data class ProgrammeRecord(val resources: List<Resource>) {
  val carePlans: List<CarePlan>
    get() = resources.filterIsInstance<CarePlan>()

  val observations: List<Observation>
    get() = resources.filterIsInstance<Observation>()
}

/** ANC contact offsets of the eCHIS plan, used to date synthesised history contacts. */
private val ANC_CONTACT_WEEKS = listOf(12, 20, 26, 30, 34, 36, 38, 40)

/**
 * Pregnancy assessment (eCHIS enrol-anc), with VaxLink's outcome group. Produces the ANC CarePlan
 * anchored on the LMP, the pregnancy Condition, the screening Observation with the LMP, one ANC
 * contact Observation per visit already had (the last on its date, the earlier ones on the plan's
 * contact dates), and, when the pregnancy has ended, the delivery Observation that discharges the
 * plan and enrols postnatal care, with the Condition resolved.
 */
fun enrolAnc(
  response: QuestionnaireResponse,
  patientId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val lmp = lmpOf(response, today) ?: error("The last menstrual period is needed to enrol ANC.")
  val delivered = response.code("delivered") == "yes"
  val deliveryDate = response.date("delivery-date")?.value?.toLocalDate()
  val ended = delivered && deliveryDate != null
  val carePlan =
    CarePlan(
      id = generateId(),
      instantiatesCanonical = listOf(Canonical(value = ANC_PLAN)),
      status =
        Enumeration(
          value = if (ended) CarePlan.RequestStatus.Completed else CarePlan.RequestStatus.Active
        ),
      intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
      title = FhirString(value = "Antenatal care"),
      subject = subject,
      period =
        Period(start = dateTime(lmp), end = deliveryDate?.takeIf { ended }?.let { dateTime(it) }),
    )
  val pregnancy =
    Condition(
      id = generateId(),
      clinicalStatus =
        concept(
          "http://terminology.hl7.org/CodeSystem/condition-clinical",
          if (ended) "resolved" else "active",
          if (ended) "Resolved" else "Active",
        ),
      verificationStatus =
        concept(
          "http://terminology.hl7.org/CodeSystem/condition-ver-status",
          "confirmed",
          "Confirmed",
        ),
      category =
        listOf(
          concept(
            "http://terminology.hl7.org/CodeSystem/condition-category",
            "problem-list-item",
            "Problem",
          )
        ),
      code = concept(SNOMED, PREGNANCY_CONDITION_CODE, "Pregnancy"),
      subject = subject,
      onset = Condition.Onset.DateTime(dateTime(lmp)),
      abatement =
        deliveryDate?.takeIf { ended }?.let { Condition.Abatement.DateTime(dateTime(it)) },
      recordedDate = dateTime(today),
    )
  val screening =
    observation(
        code = concept(ANC_SCREENING_SYSTEM, "anc-screening", "Pregnancy screening"),
        subject = subject,
        effective = today,
        response = response,
        componentSystem = ANC_SCREENING_SYSTEM,
        category = "survey",
      )
      .let { obs ->
        obs.copy(
          component =
            listOf(
              Observation.Component(
                code = concept(LOINC, LMP_LOINC, "Last menstrual period start date"),
                value = Observation.Component.Value.DateTime(dateTime(lmp)),
              )
            ) + obs.component
        )
      }
  val visits = response.integer("anc-visits") ?: 0
  val lastVisit = response.date("last-anc-visit")?.value?.toLocalDate()
  val contacts =
    (1..visits).map { n ->
      val date =
        if (n == visits && lastVisit != null) lastVisit
        else
          lmp.plus(
            DatePeriod(days = 7 * ANC_CONTACT_WEEKS[(n - 1).coerceIn(ANC_CONTACT_WEEKS.indices)])
          )
      Observation(
        id = generateId(),
        status = Enumeration(value = Observation.ObservationStatus.Final),
        code = concept(SNOMED, ANC_CONTACT_CODE, "ANC contact"),
        subject = subject,
        effective = Observation.Effective.DateTime(dateTime(date)),
        note = listOf(Annotation(text = Markdown(value = "ANC contact $n, recorded as history"))),
      )
    }
  val delivery =
    deliveryDate
      ?.takeIf { ended }
      ?.let { date -> deliveryObservation(subject, date, LIVE_BIRTH, "Livebirth", response) }
  val referral =
    if (
      response.code("referred-for-card") == "yes" || response.code("referred-danger-signs") == "yes"
    )
      referral(subject, today, "Antenatal care referral")
    else null
  return ProgrammeRecord(
    listOfNotNull(carePlan, pregnancy, screening) + contacts + listOfNotNull(delivery, referral)
  )
}

/** Record ANC contact (eCHIS record-anc-contact): one contact Observation, a referral if asked. */
fun recordAncContact(
  response: QuestionnaireResponse,
  patientId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val date = response.date("date")?.value?.toLocalDate() ?: today
  val contact =
    observation(
      code = concept(SNOMED, ANC_CONTACT_CODE, "ANC contact"),
      subject = subject,
      effective = date,
      response = response,
      componentSystem = ANC_SCREENING_SYSTEM,
    )
  val referral =
    if (response.code("referred") == "yes") referral(subject, date, "Antenatal danger signs")
    else null
  return ProgrammeRecord(listOfNotNull(contact, referral))
}

/** Record PNC contact (eCHIS record-pnc-contact) for the mother or the newborn. */
fun recordPncContact(
  response: QuestionnaireResponse,
  patientId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val date = response.date("date")?.value?.toLocalDate() ?: today
  val contact =
    observation(
      code = concept(SNOMED, PNC_CONTACT_CODE, "Postnatal visit"),
      subject = subject,
      effective = date,
      response = response,
      componentSystem = PNC_SYSTEM,
    )
  val weight =
    response.decimal("baby-weight")?.let {
      Observation(
        id = generateId(),
        status = Enumeration(value = Observation.ObservationStatus.Final),
        category =
          listOf(
            concept(
              "http://terminology.hl7.org/CodeSystem/observation-category",
              "vital-signs",
              "Vital Signs",
            )
          ),
        code = concept(LOINC, "29463-7", "Body weight"),
        subject = subject,
        effective = Observation.Effective.DateTime(dateTime(date)),
        value =
          Observation.Value.Quantity(
            Quantity(
              value = Decimal(value = it),
              unit = FhirString(value = "kg"),
              system = Uri(value = "http://unitsofmeasure.org"),
              code = Code(value = "kg"),
            )
          ),
      )
    }
  val referral =
    if (response.code("referred") == "yes") referral(subject, date, "Postnatal danger signs")
    else null
  return ProgrammeRecord(listOfNotNull(contact, weight, referral))
}

/** The FP plan the chosen method enrols, as the eCHIS enrol-fp CarePlan templates decide it. */
fun fpPlanFor(methodCode: String?): String? =
  when (methodCode) {
    "LA27907-7",
    "709457002" -> FP_INJECTABLE_PLAN
    "169467008",
    "268458002",
    "275831001",
    "228479001" -> FP_ORAL_PLAN
    "312081001",
    "428987008",
    "22523008",
    "74964007" -> FP_LONGTERM_PLAN
    else -> null
  }

/**
 * Family planning assessment (eCHIS enrol-fp): the assessment Observation and the method's plan.
 */
fun enrolFp(response: QuestionnaireResponse, patientId: String, today: LocalDate): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val date = response.date("date")?.value?.toLocalDate() ?: today
  val assessment =
    observation(
      code = concept(SNOMED, FP_ASSESSMENT_CODE, "Family planning assessment"),
      subject = subject,
      effective = date,
      response = response,
      componentSystem = FP_SYSTEM,
      category = "survey",
    )
  val plan =
    fpPlanFor(response.code("method"))
      ?.takeIf { response.code("interested") != "no" }
      ?.let { url ->
        CarePlan(
          id = generateId(),
          instantiatesCanonical = listOf(Canonical(value = url)),
          status = Enumeration(value = CarePlan.RequestStatus.Active),
          intent = Enumeration(value = CarePlan.CarePlanIntent.Plan),
          title = FhirString(value = "Family planning"),
          subject = subject,
          period = Period(start = dateTime(date)),
        )
      }
  val injection = fpInjectionIfGiven(response, subject, date)
  val referral =
    if (response.code("referred") == "yes") referral(subject, date, "Family planning referral")
    else null
  return ProgrammeRecord(listOfNotNull(assessment, plan, injection, referral))
}

/** Family planning visit (eCHIS record-fp-visit): the visit Observation that moves the anchor. */
fun recordFpVisit(
  response: QuestionnaireResponse,
  patientId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val date = response.date("date")?.value?.toLocalDate() ?: today
  val visit =
    observation(
        code = concept(SNOMED, FP_VISIT_CODE, "Family planning visit"),
        subject = subject,
        effective = date,
        response = response,
        componentSystem = FP_SYSTEM,
        category = "survey",
      )
      .let { obs ->
        val method = response.code("method")
        if (method == null) obs
        else
          obs.copy(
            component =
              listOf(
                Observation.Component(
                  code = concept(LOINC, FP_METHOD_LOINC, "Family planning method"),
                  value =
                    Observation.Component.Value.CodeableConcept(
                      concept(SNOMED, method, response.display("method") ?: method)
                    ),
                )
              ) + obs.component
          )
      }
  val injection = fpInjectionIfGiven(response, subject, date)
  val referral =
    if (response.code("referred") == "yes") referral(subject, date, "Family planning referral")
    else null
  return ProgrammeRecord(listOfNotNull(visit, injection, referral))
}

/** Household WASH check (eCHIS record-wash-check): one Observation on the household Group. */
fun recordWashCheck(
  response: QuestionnaireResponse,
  householdId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Group/$householdId")
  val date = response.date("date")?.value?.toLocalDate() ?: today
  return ProgrammeRecord(
    listOf(
      observation(
        code = concept(WASH_SYSTEM, WASH_CHECK_CODE, "Household WASH check"),
        subject = subject,
        effective = date,
        response = response,
        componentSystem = WASH_SYSTEM,
        category = "social-history",
      )
    )
  )
}

/** The last menstrual period as the eCHIS CarePlan template derives it: date, weeks, or a band. */
fun lmpOf(response: QuestionnaireResponse, today: LocalDate): LocalDate? {
  response.date("lmp")?.value?.toLocalDate()?.let {
    return it
  }
  response.integer("weeks-since-lmp")?.let {
    return today.minus(DatePeriod(days = 7 * it))
  }
  val weeks =
    when (response.code("approximate-lmp")) {
      "up-to-2-months" -> 8
      "up-to-3-months" -> 12
      "up-to-4-months" -> 16
      "5-to-6-months" -> 20
      "7-to-8-months" -> 32
      "9-months" -> 36
      else -> return null
    }
  return today.minus(DatePeriod(days = 7 * weeks))
}

private fun fpInjectionIfGiven(
  response: QuestionnaireResponse,
  subject: Reference,
  date: LocalDate,
): Observation? {
  val given =
    response.code("dmpa-administered") == "yes" ||
      response.code("sayana-press-administered") == "yes"
  if (!given) return null
  return Observation(
    id = generateId(),
    status = Enumeration(value = Observation.ObservationStatus.Final),
    code = concept(SNOMED, FP_INJECTION_CODE, "Contraceptive injection"),
    subject = subject,
    effective = Observation.Effective.DateTime(dateTime(date)),
  )
}

/** One Observation with every answered item as a component, code = linkId in [componentSystem]. */
private fun observation(
  code: CodeableConcept,
  subject: Reference,
  effective: LocalDate,
  response: QuestionnaireResponse,
  componentSystem: String,
  category: String? = null,
): Observation =
  Observation(
    id = generateId(),
    status = Enumeration(value = Observation.ObservationStatus.Final),
    category =
      listOfNotNull(
        category?.let {
          concept("http://terminology.hl7.org/CodeSystem/observation-category", it, it)
        }
      ),
    code = code,
    subject = subject,
    effective = Observation.Effective.DateTime(dateTime(effective)),
    component =
      response.answeredItems().mapNotNull { (linkId, value) ->
        if (linkId == "date") return@mapNotNull null
        componentValue(value)?.let {
          Observation.Component(code = concept(componentSystem, linkId, linkId), value = it)
        }
      },
  )

private fun componentValue(value: Value): Observation.Component.Value? =
  when (value) {
    is Value.Boolean -> Observation.Component.Value.Boolean(value.value)
    is Value.Integer -> Observation.Component.Value.Integer(value.value)
    is Value.Decimal ->
      Observation.Component.Value.Quantity(Quantity(value = Decimal(value = value.value.value)))
    is Value.String -> Observation.Component.Value.String(value.value)
    is Value.Date ->
      Observation.Component.Value.DateTime(
        DateTime(value = value.value.value?.let { FhirDateTime.fromString(it.toString()) })
      )
    is Value.Coding ->
      Observation.Component.Value.CodeableConcept(CodeableConcept(coding = listOf(value.value)))
    else -> null
  }

/** Every answered leaf item, in order, with its first answer. */
fun QuestionnaireResponse.answeredItems(): List<Pair<String, Value>> {
  fun walk(items: List<QuestionnaireResponse.Item>): List<Pair<String, Value>> =
    items.flatMap { item ->
      val own =
        item.answer
          .firstOrNull()
          ?.value
          ?.let { listOf(item.linkId.value.orEmpty() to it) }
          .orEmpty()
      own + walk(item.item) + item.answer.flatMap { walk(it.item) }
    }
  return walk(item)
}

private fun referral(subject: Reference, date: LocalDate, reason: String) =
  ServiceRequest(
    id = generateId(),
    status = Enumeration(value = ServiceRequest.RequestStatus.Active),
    intent = Enumeration(value = ServiceRequest.RequestIntent.Order),
    priority = Enumeration(value = ServiceRequest.RequestPriority.Urgent),
    code = concept(SNOMED, REFERRAL_CODE, "Patient referral for consultation"),
    subject = subject,
    authoredOn = dateTime(date),
    reasonCode = listOf(CodeableConcept(text = FhirString(value = reason))),
  )

private fun concept(system: String, code: String, display: String) =
  CodeableConcept(
    coding =
      listOf(
        Coding(
          system = Uri(value = system),
          code = Code(value = code),
          display = FhirString(value = display),
        )
      )
  )

private fun reference(value: String) = Reference(reference = FhirString(value = value))

private fun dateTime(date: LocalDate) = DateTime(value = FhirDateTime.Date(date))

private fun FhirDate.toLocalDate(): LocalDate? = (this as? FhirDate.Date)?.date

private const val LIVE_BIRTH = "281050002"
private const val STILLBIRTH = "237364002"

/**
 * The Delivery observation that discharges the ANC plan, with where and (if abroad) which country.
 */
private fun deliveryObservation(
  subject: Reference,
  date: LocalDate,
  outcomeCode: String,
  outcomeDisplay: String,
  response: QuestionnaireResponse,
) =
  Observation(
    id = generateId(),
    status = Enumeration(value = Observation.ObservationStatus.Final),
    code = concept(SNOMED, DELIVERY_CODE, "Delivery"),
    subject = subject,
    effective = Observation.Effective.DateTime(dateTime(date)),
    component =
      listOfNotNull(
        Observation.Component(
          code = concept(LOINC, "63893-2", "Outcome of pregnancy"),
          value =
            Observation.Component.Value.CodeableConcept(
              concept(SNOMED, outcomeCode, outcomeDisplay)
            ),
        ),
        response.code("delivery-place")?.let {
          Observation.Component(
            code = concept(ANC_SCREENING_SYSTEM, "delivery-place", "Place of delivery"),
            value =
              Observation.Component.Value.CodeableConcept(
                concept(SNOMED, it, response.display("delivery-place") ?: it)
              ),
          )
        },
        response
          .string("delivery-abroad")
          ?.takeIf { it.isNotBlank() }
          ?.let {
            Observation.Component(
              code = concept(ANC_SCREENING_SYSTEM, "delivery-abroad", "Country of delivery"),
              value = Observation.Component.Value.String(FhirString(value = it)),
            )
          },
      ),
  )

/** The date the pregnancy outcome form gives, or [today]. */
fun pregnancyOutcomeDate(response: QuestionnaireResponse, today: LocalDate): LocalDate =
  response.date("date")?.value?.toLocalDate() ?: today

/**
 * Pregnancy outcome (VaxLink): ends a pregnancy enrolled in antenatal care. The Delivery
 * observation it records is what discharges the ANC plan and enrols postnatal care; a referral is
 * requested when the form asks for one. The pregnancy Condition and the ANC CarePlan, which already
 * exist, are closed by [endPregnancy].
 */
fun recordPregnancyOutcome(
  response: QuestionnaireResponse,
  patientId: String,
  today: LocalDate,
): ProgrammeRecord {
  val subject = reference("Patient/$patientId")
  val date = pregnancyOutcomeDate(response, today)
  val stillborn = response.code("outcome") == STILLBIRTH
  val delivery =
    deliveryObservation(
      subject,
      date,
      if (stillborn) STILLBIRTH else LIVE_BIRTH,
      if (stillborn) "Stillbirth" else "Livebirth",
      response,
    )
  val referral =
    if (response.code("referred") == "yes") referral(subject, date, "Postpartum care referral")
    else null
  return ProgrammeRecord(listOfNotNull(delivery, referral))
}

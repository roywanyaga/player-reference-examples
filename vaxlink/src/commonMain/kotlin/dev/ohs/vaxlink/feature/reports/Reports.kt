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
package dev.ohs.vaxlink.feature.reports

import dev.ohs.fhir.model.r4.AuditEvent
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Provenance
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.fhir.model.r4.Task
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.feature.register.Programme
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.report_anc_clients
import dev.ohs.vaxlink.generated.resources.report_anc_new
import dev.ohs.vaxlink.generated.resources.report_bcg_pending
import dev.ohs.vaxlink.generated.resources.report_certificates_scanned
import dev.ohs.vaxlink.generated.resources.report_certificates_verified
import dev.ohs.vaxlink.generated.resources.report_children_under_five
import dev.ohs.vaxlink.generated.resources.report_doses_due
import dev.ohs.vaxlink.generated.resources.report_doses_given
import dev.ohs.vaxlink.generated.resources.report_doses_overdue
import dev.ohs.vaxlink.generated.resources.report_foreign_doses
import dev.ohs.vaxlink.generated.resources.report_fp_clients
import dev.ohs.vaxlink.generated.resources.report_fp_new
import dev.ohs.vaxlink.generated.resources.report_group_immunisation
import dev.ohs.vaxlink.generated.resources.report_group_people
import dev.ohs.vaxlink.generated.resources.report_group_programmes
import dev.ohs.vaxlink.generated.resources.report_group_referrals
import dev.ohs.vaxlink.generated.resources.report_group_verification
import dev.ohs.vaxlink.generated.resources.report_households
import dev.ohs.vaxlink.generated.resources.report_patients
import dev.ohs.vaxlink.generated.resources.report_pnc_clients
import dev.ohs.vaxlink.generated.resources.report_pnc_new
import dev.ohs.vaxlink.generated.resources.report_referrals_completed
import dev.ohs.vaxlink.generated.resources.report_referrals_open
import dev.ohs.vaxlink.generated.resources.report_referrals_sent
import dev.ohs.vaxlink.generated.resources.report_referrals_withdrawn
import dev.ohs.vaxlink.generated.resources.report_verification_failed
import dev.ohs.vaxlink.generated.resources.report_zero_dose
import dev.ohs.vaxlink.util.calendarDate
import dev.ohs.vaxlink.util.idOf
import dev.ohs.vaxlink.workflow.DOSE_CONFIRMED_ACTIVITY
import dev.ohs.vaxlink.workflow.DOSE_NOT_COUNTED_ACTIVITY
import dev.ohs.vaxlink.workflow.HCERT_FINGERPRINT_SYSTEM
import dev.ohs.vaxlink.workflow.LAO_DOSE_SYSTEM
import dev.ohs.vaxlink.workflow.LAO_SCHEDULE_PLAN
import dev.ohs.vaxlink.workflow.REFERRAL_CODE
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.jetbrains.compose.resources.StringResource

/** The headings the report groups its counters under. */
enum class ReportGroup(val label: StringResource) {
  People(Res.string.report_group_people),
  Programmes(Res.string.report_group_programmes),
  Immunisation(Res.string.report_group_immunisation),
  Verification(Res.string.report_group_verification),
  Referrals(Res.string.report_group_referrals),
}

/**
 * One counter. A [monthly] counter counts what happened in the chosen month; the others count the
 * position now, whatever the month.
 */
enum class ReportMetric(
  val group: ReportGroup,
  val label: StringResource,
  val monthly: Boolean = false,
) {
  Households(ReportGroup.People, Res.string.report_households),
  Patients(ReportGroup.People, Res.string.report_patients),
  ChildrenUnderFive(ReportGroup.People, Res.string.report_children_under_five),
  ZeroDose(ReportGroup.People, Res.string.report_zero_dose),
  AncClients(ReportGroup.Programmes, Res.string.report_anc_clients),
  AncNew(ReportGroup.Programmes, Res.string.report_anc_new, monthly = true),
  PncClients(ReportGroup.Programmes, Res.string.report_pnc_clients),
  PncNew(ReportGroup.Programmes, Res.string.report_pnc_new, monthly = true),
  FpClients(ReportGroup.Programmes, Res.string.report_fp_clients),
  FpNew(ReportGroup.Programmes, Res.string.report_fp_new, monthly = true),
  DosesGiven(ReportGroup.Immunisation, Res.string.report_doses_given, monthly = true),
  DosesDue(ReportGroup.Immunisation, Res.string.report_doses_due, monthly = true),
  DosesOverdue(ReportGroup.Immunisation, Res.string.report_doses_overdue),
  CertificatesScanned(ReportGroup.Verification, Res.string.report_certificates_scanned, true),
  CertificatesVerified(ReportGroup.Verification, Res.string.report_certificates_verified, true),
  VerificationsFailed(ReportGroup.Verification, Res.string.report_verification_failed, true),
  ForeignDosesVerified(ReportGroup.Verification, Res.string.report_foreign_doses, true),
  BcgDecisionsPending(ReportGroup.Verification, Res.string.report_bcg_pending),
  ReferralsOpen(ReportGroup.Referrals, Res.string.report_referrals_open),
  ReferralsSent(ReportGroup.Referrals, Res.string.report_referrals_sent, monthly = true),
  ReferralsCompleted(ReportGroup.Referrals, Res.string.report_referrals_completed, monthly = true),
  ReferralsWithdrawn(ReportGroup.Referrals, Res.string.report_referrals_withdrawn, monthly = true),
}

/** Age in days from which a child with no Penta dose counts as zero-dose. */
const val ZERO_DOSE_FROM_DAYS = 98

/**
 * Every counter, computed offline from what is on the device. [month] is `yyyy-MM`; [today] fixes
 * the position-now counters (overdue, under five, zero-dose). [placements] tells each Task's plan.
 */
suspend fun FhirRepository.reportCounts(
  month: String,
  today: LocalDate,
  placements: Map<String, TaskPlacement>,
): Map<ReportMetric, Int> {
  fun inMonth(date: String?) = date?.startsWith(month) == true

  val patients = all("Patient").filterIsInstance<Patient>()
  val immunizations = all("Immunization").filterIsInstance<Immunization>()
  val carePlans = all("CarePlan").filterIsInstance<CarePlan>()
  val tasks = all("Task").filterIsInstance<Task>()
  val referrals =
    all("ServiceRequest").filterIsInstance<ServiceRequest>().filter { r ->
      r.code?.coding?.any { it.code?.value == REFERRAL_CODE } == true
    }
  val audits = all("AuditEvent").filterIsInstance<AuditEvent>()
  val provenances = all("Provenance").filterIsInstance<Provenance>()

  val underFiveFrom = today.minus(5, DateTimeUnit.YEAR)
  val zeroDoseFrom = today.minus(ZERO_DOSE_FROM_DAYS, DateTimeUnit.DAY)
  val withPenta =
    immunizations
      .filter { it.status.value == Immunization.ImmunizationStatusCodes.Completed }
      .filter { imm ->
        imm.vaccineCode.coding.any {
          it.system?.value == LAO_DOSE_SYSTEM && it.code?.value?.startsWith("PENTA") == true
        }
      }
      .mapNotNull { it.patient.idOf("Patient") }
      .toSet()
  val children = patients.filter { (it.born() ?: return@filter false) >= underFiveFrom }

  fun active(programme: Programme) =
    carePlans.filter { plan ->
      plan.status.value == CarePlan.RequestStatus.Active &&
        plan.instantiatesCanonical.any { it.value in programme.plans }
    }

  fun enrolledIn(programme: Programme) =
    carePlans.count { plan ->
      plan.instantiatesCanonical.any { it.value in programme.plans } &&
        inMonth(plan.period?.start?.value.calendarDate())
    }

  val laoTasks =
    tasks.filter { placements[it.instantiatesCanonical?.value]?.planUrl == LAO_SCHEDULE_PLAN }
  val openLao = laoTasks.filter { it.status.value in OPEN_TASK_STATUSES }
  fun Task.dueOn() = restriction?.period?.end?.value.calendarDate()

  val scanned =
    immunizations.filter { imm ->
      imm.identifier.any { it.system?.value == HCERT_FINGERPRINT_SYSTEM }
    }
  val decided =
    provenances
      .filter {
        it.activity?.text?.value == DOSE_CONFIRMED_ACTIVITY ||
          it.activity?.text?.value == DOSE_NOT_COUNTED_ACTIVITY
      }
      .flatMap { p -> p.target.mapNotNull { it.idOf("Immunization") } }
      .toSet()
  val success = AuditEvent.AuditEventOutcome.fromCode("0")

  return mapOf(
    ReportMetric.Households to
      all("Group").filterIsInstance<Group>().count { g ->
        g.code?.coding?.any { it.code?.value == "household" } == true
      },
    ReportMetric.Patients to patients.size,
    ReportMetric.ChildrenUnderFive to children.size,
    ReportMetric.ZeroDose to
      children.count { child ->
        (child.born() ?: return@count false) <= zeroDoseFrom && child.id !in withPenta
      },
    ReportMetric.AncClients to
      active(Programme.Anc).mapNotNull { it.subject.idOf("Patient") }.toSet().size,
    ReportMetric.AncNew to enrolledIn(Programme.Anc),
    ReportMetric.PncClients to
      active(Programme.Pnc).mapNotNull { it.subject.idOf("Patient") }.toSet().size,
    ReportMetric.PncNew to enrolledIn(Programme.Pnc),
    ReportMetric.FpClients to
      active(Programme.Fp).mapNotNull { it.subject.idOf("Patient") }.toSet().size,
    ReportMetric.FpNew to enrolledIn(Programme.Fp),
    ReportMetric.DosesGiven to
      immunizations.count {
        it.status.value == Immunization.ImmunizationStatusCodes.Completed &&
          inMonth((it.occurrence as? Immunization.Occurrence.DateTime)?.value?.value.calendarDate())
      },
    ReportMetric.DosesDue to openLao.count { inMonth(it.dueOn()) },
    ReportMetric.DosesOverdue to
      openLao.count { (it.dueOn() ?: return@count false) < today.toString() },
    ReportMetric.CertificatesScanned to
      scanned.count { inMonth(it.recorded?.value.calendarDate()) },
    ReportMetric.CertificatesVerified to
      audits.count { it.outcome?.value == success && inMonth(it.recorded.value.calendarDate()) },
    ReportMetric.VerificationsFailed to
      audits.count { it.outcome?.value != success && inMonth(it.recorded.value.calendarDate()) },
    ReportMetric.ForeignDosesVerified to
      scanned.count {
        it.primarySource?.value == true && inMonth(it.recorded?.value.calendarDate())
      },
    ReportMetric.BcgDecisionsPending to
      scanned.count { imm ->
        imm.primarySource?.value == true &&
          imm.vaccineCode.coding.none { it.system?.value == LAO_DOSE_SYSTEM } &&
          imm.id !in decided
      },
    ReportMetric.ReferralsOpen to
      referrals.count { it.status.value == ServiceRequest.RequestStatus.Active },
    ReportMetric.ReferralsSent to referrals.count { inMonth(it.authoredOn?.value.calendarDate()) },
    ReportMetric.ReferralsCompleted to
      referrals.count {
        it.status.value == ServiceRequest.RequestStatus.Completed &&
          inMonth(it.authoredOn?.value.calendarDate())
      },
    ReportMetric.ReferralsWithdrawn to
      referrals.count {
        it.status.value == ServiceRequest.RequestStatus.Revoked &&
          inMonth(it.authoredOn?.value.calendarDate())
      },
  )
}

private fun Patient.born(): LocalDate? = (birthDate?.value as? FhirDate.Date)?.date

private val OPEN_TASK_STATUSES =
  setOf(
    Task.TaskStatus.Requested,
    Task.TaskStatus.Draft,
    Task.TaskStatus.Received,
    Task.TaskStatus.Accepted,
    Task.TaskStatus.Ready,
    Task.TaskStatus.In_Progress,
    Task.TaskStatus.On_Hold,
  )

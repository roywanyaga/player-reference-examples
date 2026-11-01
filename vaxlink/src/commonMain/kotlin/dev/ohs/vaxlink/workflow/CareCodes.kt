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

/**
 * SNOMED CT "Patient referral" (3457005): the code on a ServiceRequest that refers a child to the
 * facility. NE-2 creates these with the reason "foreign immunization record to verify".
 */
const val REFERRAL_CODE = "3457005"

/**
 * Task.code for a follow-up the community health worker owes after a facility visit. The same code
 * sits in `files/states/Binary-FollowUp.json` and `files/sync/chw.json`; rename all three together.
 */
const val FOLLOW_UP_TASK = "iccm-follow-up"

/** The VaxLink IG: the Lao schedule plan and its dose code system. */
const val LAO_SCHEDULE_PLAN = "https://nawitech.github.io/vaxlink-ig/PlanDefinition/LaoEpiSchedule"
const val LAO_DOSE_SYSTEM = "https://nawitech.github.io/vaxlink-ig/CodeSystem/lao-epi-schedule-dose"

/**
 * On a vaccineCode coding: `automatic` when the national map added it, `confirmed` when a clinician
 * did.
 */
const val CODING_ORIGIN_EXTENSION =
  "https://nawitech.github.io/vaxlink-ig/StructureDefinition/coding-origin"

/** On a RelatedPerson: the Patient record of the related person, when they are registered too. */
const val RELATED_PATIENT_EXTENSION =
  "https://nawitech.github.io/vaxlink-ig/StructureDefinition/related-patient"

/* The eCHIS conventions the household model and the schedule plan use, kept so the content ports. */
const val SCHEDULE_OFFSET_EXTENSION =
  "http://smartregister.org/fhir/StructureDefinition/schedule-offset"
const val GROUP_MEMBER_INVOLVEMENT_EXTENSION =
  "http://smartregister.org/fhir/StructureDefinition/group-member-involvement"
const val GROUP_INVOLVEMENT_SYSTEM = "http://hl7.org/fhir/group-involvement"
const val HOUSEHOLD_CHARACTERISTIC_SYSTEM = "http://smartregister.org/fhir/CodeSystem/household"
const val GROUP_CODE_SYSTEM = "http://hl7.org/fhir/group-code"
const val V3_ROLE_CODE = "http://terminology.hl7.org/CodeSystem/v3-RoleCode"
const val HCERT_FINGERPRINT_SYSTEM = "http://ohs.dev/vaxlink/identifier/hcert-sha256"
const val REFERRAL_REASON_FOREIGN_RECORD = "Foreign immunization record to verify"

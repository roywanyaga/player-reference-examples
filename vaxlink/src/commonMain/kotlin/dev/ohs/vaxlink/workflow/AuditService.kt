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

import dev.ohs.fhir.model.r4.AuditEvent
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.hcert.CertificateFingerprint
import dev.ohs.vaxlink.hcert.HcertClock
import dev.ohs.vaxlink.hcert.VerificationAudit
import dev.ohs.vaxlink.hcert.VerificationResult

const val DEVICE_ID_SYSTEM = "http://ohs.dev/vaxlink/identifier/device-id"
const val CLINIC_APP_DISPLAY = "VaxLink clinic app"

/**
 * One AuditEvent per verification attempt, success or failure (CLN-10, AUD-01), stored locally so
 * it uploads with the next sync through the gateway. The observer is this app instance, named by
 * the device's stable id; no certificate content goes into the event.
 */
class AuditService(
  private val repository: FhirRepository,
  private val deviceId: suspend () -> String,
  private val now: () -> Long = HcertClock::nowEpochSeconds,
) {
  suspend fun recordVerification(
    result: VerificationResult,
    hc1: String,
    user: UserContext,
    patientId: String?,
  ): AuditEvent {
    val event =
      VerificationAudit.event(
        result = result,
        certificateFingerprint = CertificateFingerprint.of(hc1),
        practitioner =
          Reference(reference = FhirString(value = "Practitioner/${user.practitionerId}")),
        patient = patientId?.let { Reference(reference = FhirString(value = "Patient/$it")) },
        observer =
          Reference(
            identifier =
              Identifier(
                system = Uri(value = DEVICE_ID_SYSTEM),
                value = FhirString(value = deviceId()),
              ),
            display = FhirString(value = CLINIC_APP_DISPLAY),
          ),
        recordedAtEpochSeconds = now(),
      )
    repository.upsert(event)
    return event
  }
}

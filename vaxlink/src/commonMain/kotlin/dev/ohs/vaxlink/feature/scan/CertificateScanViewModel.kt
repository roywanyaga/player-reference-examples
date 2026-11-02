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
package dev.ohs.vaxlink.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.hcert.HcertService
import dev.ohs.vaxlink.hcert.HcertClock
import dev.ohs.vaxlink.hcert.ReadResult
import dev.ohs.vaxlink.hcert.TrustListSnapshot
import dev.ohs.vaxlink.hcert.UnverifiedHcert
import dev.ohs.vaxlink.hcert.VerificationFailure
import dev.ohs.vaxlink.hcert.VerificationResult
import dev.ohs.vaxlink.hcert.VerifiedHcert
import dev.ohs.vaxlink.workflow.AuditService
import dev.ohs.vaxlink.workflow.CertificateRecorder
import dev.ohs.vaxlink.workflow.ClinicImportService
import dev.ohs.vaxlink.workflow.DoseChoice
import dev.ohs.vaxlink.workflow.ImportOutcome
import dev.ohs.vaxlink.workflow.LaoDose
import dev.ohs.vaxlink.workflow.RecordOutcome
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which journey the scan serves. The CHW (J2) reads and shows without trusting; the clinic (J3)
 * verifies first and shows content only when the signature checked out.
 */
enum class ScanMode {
  ReadOnly,
  VerifyFirst,
}

sealed interface ScanStage {
  data object Scanning : ScanStage

  data object Working : ScanStage

  /** Decoded, not verified. Shown with a "Not verified" mark; the clinic may verify from here. */
  data class Decoded(val hc1: String, val hcert: UnverifiedHcert) : ScanStage

  data class Verified(
    val hc1: String,
    val hcert: VerifiedHcert,
    val trustList: TrustListSnapshot?,
  ) : ScanStage

  /** A typed failure; no certificate content is shown. */
  data class Failed(val failure: VerificationFailure, val trustList: TrustListSnapshot?) :
    ScanStage

  /** The decoded card was written to the child's record, or refused as already recorded. */
  data class Recorded(val hcert: UnverifiedHcert, val outcome: RecordOutcome) : ScanStage

  /** The clinic: verified and imported; [outcome] says whether the nurse still has to decide. */
  data class Imported(
    val hcert: VerifiedHcert,
    val outcome: ImportOutcome,
    val doses: List<LaoDose> = emptyList(),
  ) : ScanStage

  /** The nurse's decision on an unmatched dose was recorded. */
  data class Confirmed(val hcert: VerifiedHcert, val choice: DoseChoice) : ScanStage
}

data class ScanUiState(
  val stage: ScanStage = ScanStage.Scanning,
  val scanError: String? = null,
  val trustList: TrustListSnapshot? = null,
)

class CertificateScanViewModel(
  private val hcert: HcertService,
  private val mode: ScanMode,
  /** The child whose record a decoded card is written to; null hides the Record action. */
  private val patientId: String? = null,
  private val recorder: CertificateRecorder? = null,
  /** The signed-in user; with [importer] and [audit], the clinic path verifies and imports. */
  private val user: UserContext? = null,
  private val importer: ClinicImportService? = null,
  private val audit: AuditService? = null,
  private val now: () -> Long = HcertClock::nowEpochSeconds,
) : ViewModel() {
  /** The clinic path: verify first, then import onto the open child's record. */
  val canImport: Boolean
    get() = mode == ScanMode.VerifyFirst && patientId != null && importer != null && user != null

  /** True when a decoded card can be recorded from this screen. */
  val canRecord: Boolean
    get() = patientId != null && recorder != null

  private val _uiState = MutableStateFlow(ScanUiState())
  val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

  val products
    get() = hcert.products

  init {
    viewModelScope.launch { _uiState.update { it.copy(trustList = hcert.trustList()) } }
  }

  fun onScan(scan: QrScan) {
    when (scan) {
      is QrScan.Text -> onText(scan.value)
      is QrScan.Failed -> _uiState.update { it.copy(scanError = scan.message) }
      QrScan.Cancelled -> Unit
    }
  }

  fun onText(text: String) {
    val hc1 = text.trim()
    if (hc1.isEmpty()) return
    _uiState.update { it.copy(stage = ScanStage.Working, scanError = null) }
    viewModelScope.launch {
      when (mode) {
        ScanMode.ReadOnly -> read(hc1)
        ScanMode.VerifyFirst -> verify(hc1)
      }
    }
  }

  /** From [ScanStage.Decoded], the full check. */
  fun verifyDecoded() {
    val decoded = _uiState.value.stage as? ScanStage.Decoded ?: return
    _uiState.update { it.copy(stage = ScanStage.Working) }
    viewModelScope.launch { verify(decoded.hc1) }
  }

  /** From [ScanStage.Decoded], J2's record step: store the card as an Immunization. */
  fun recordDecoded() {
    val decoded = _uiState.value.stage as? ScanStage.Decoded ?: return
    val recorder = recorder ?: return
    val patientId = patientId ?: return
    _uiState.update { it.copy(stage = ScanStage.Working) }
    viewModelScope.launch {
      try {
        val outcome = recorder.record(patientId, decoded.hc1, decoded.hcert)
        _uiState.update { it.copy(stage = ScanStage.Recorded(decoded.hcert, outcome)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { it.copy(stage = decoded, scanError = e.message) }
      }
    }
  }

  /** From [ScanStage.Imported] with an unmatched dose: the nurse's choice. */
  fun confirmDose(choice: DoseChoice) {
    val imported = _uiState.value.stage as? ScanStage.Imported ?: return
    val outcome = imported.outcome as? ImportOutcome.Imported ?: return
    val importer = importer ?: return
    val user = user ?: return
    _uiState.update { it.copy(stage = ScanStage.Working) }
    viewModelScope.launch {
      try {
        importer.confirmDose(outcome.immunization.id!!, choice, user)
        _uiState.update { it.copy(stage = ScanStage.Confirmed(imported.hcert, choice)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { it.copy(stage = imported, scanError = e.message) }
      }
    }
  }

  fun reset() {
    _uiState.update { it.copy(stage = ScanStage.Scanning, scanError = null) }
  }

  private suspend fun read(hc1: String) {
    val stage =
      when (val r = hcert.read(hc1)) {
        is ReadResult.Decoded -> ScanStage.Decoded(hc1, r.hcert)
        is ReadResult.Failed -> ScanStage.Failed(r.failure, null)
      }
    _uiState.update { it.copy(stage = stage) }
  }

  private suspend fun verify(hc1: String) {
    val verification = hcert.verify(hc1, now())
    // Every attempt is audited, success or failure, before anything else happens (CLN-10).
    user?.let { u ->
      audit?.let { a ->
        runCatching { a.recordVerification(verification.result, hc1, u, patientId) }
      }
    }
    val stage =
      when (val r = verification.result) {
        is VerificationResult.Verified ->
          if (canImport) importStage(hc1, r.hcert)
          else ScanStage.Verified(hc1, r.hcert, verification.trustList)
        is VerificationResult.Failed -> ScanStage.Failed(r.failure, verification.trustList)
      }
    _uiState.update { it.copy(stage = stage, trustList = verification.trustList) }
  }

  private suspend fun importStage(hc1: String, verified: VerifiedHcert): ScanStage {
    val importer = importer!!
    return try {
      val outcome = importer.import(patientId!!, hc1, verified, user!!)
      val doses =
        if ((outcome as? ImportOutcome.Imported)?.needsConfirmation == true) importer.laoDoses()
        else emptyList()
      ScanStage.Imported(verified, outcome, doses)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _uiState.update { it.copy(scanError = e.message) }
      ScanStage.Verified(hc1, verified, _uiState.value.trustList)
    }
  }
}

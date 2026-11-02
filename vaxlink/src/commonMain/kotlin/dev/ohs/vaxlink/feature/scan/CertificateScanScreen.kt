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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.scan_action
import dev.ohs.vaxlink.generated.resources.scan_close
import dev.ohs.vaxlink.generated.resources.scan_confirm_hint
import dev.ohs.vaxlink.generated.resources.scan_confirm_not_counted
import dev.ohs.vaxlink.generated.resources.scan_confirm_not_counted_done
import dev.ohs.vaxlink.generated.resources.scan_confirm_prompt
import dev.ohs.vaxlink.generated.resources.scan_confirmed
import dev.ohs.vaxlink.generated.resources.scan_done
import dev.ohs.vaxlink.generated.resources.scan_failed
import dev.ohs.vaxlink.generated.resources.scan_field_clinician
import dev.ohs.vaxlink.generated.resources.scan_field_date
import dev.ohs.vaxlink.generated.resources.scan_field_dob
import dev.ohs.vaxlink.generated.resources.scan_field_dsc
import dev.ohs.vaxlink.generated.resources.scan_field_issuer
import dev.ohs.vaxlink.generated.resources.scan_field_lot
import dev.ohs.vaxlink.generated.resources.scan_field_name
import dev.ohs.vaxlink.generated.resources.scan_field_vaccine
import dev.ohs.vaxlink.generated.resources.scan_imported
import dev.ohs.vaxlink.generated.resources.scan_imported_already
import dev.ohs.vaxlink.generated.resources.scan_imported_walk_in
import dev.ohs.vaxlink.generated.resources.scan_not_verified
import dev.ohs.vaxlink.generated.resources.scan_paste_label
import dev.ohs.vaxlink.generated.resources.scan_record
import dev.ohs.vaxlink.generated.resources.scan_recorded
import dev.ohs.vaxlink.generated.resources.scan_recorded_already
import dev.ohs.vaxlink.generated.resources.scan_recorded_foreign
import dev.ohs.vaxlink.generated.resources.scan_recorded_lao
import dev.ohs.vaxlink.generated.resources.scan_recorded_none
import dev.ohs.vaxlink.generated.resources.scan_scan_again
import dev.ohs.vaxlink.generated.resources.scan_title
import dev.ohs.vaxlink.generated.resources.scan_trust_list
import dev.ohs.vaxlink.generated.resources.scan_trust_list_bundled
import dev.ohs.vaxlink.generated.resources.scan_trust_list_none
import dev.ohs.vaxlink.generated.resources.scan_use_text
import dev.ohs.vaxlink.generated.resources.scan_verified
import dev.ohs.vaxlink.generated.resources.scan_verify
import dev.ohs.vaxlink.generated.resources.scan_working
import dev.ohs.vaxlink.generated.resources.scan_wrong_child
import dev.ohs.vaxlink.generated.resources.scan_wrong_child_detail
import dev.ohs.vaxlink.hcert.DoseProposal
import dev.ohs.vaxlink.hcert.DvcMin
import dev.ohs.vaxlink.hcert.HcertClock
import dev.ohs.vaxlink.hcert.PreQualProducts
import dev.ohs.vaxlink.hcert.TrustListSnapshot
import dev.ohs.vaxlink.workflow.DoseChoice
import dev.ohs.vaxlink.workflow.ImportOutcome
import dev.ohs.vaxlink.workflow.LAO_DOSE_SYSTEM
import dev.ohs.vaxlink.workflow.RecordOutcome
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The profile action: a button that opens the scanner. The CHW role reads without verifying and
 * records the card on the child's record (J2, NE-2); the clinic roles verify first (J3). With no
 * [patientId] the screen only proves the scan, decode and verify path on the device.
 */
@Composable
fun ScanCertificateAction(
  mode: ScanMode,
  patientId: String? = null,
  user: UserContext? = null,
  modifier: Modifier = Modifier,
) {
  var open by remember { mutableStateOf(false) }
  Button(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
    Text(stringResource(Res.string.scan_action))
  }
  if (open) {
    CertificateScanDialog(
      mode = mode,
      patientId = patientId,
      user = user,
      onDismiss = { open = false },
    )
  }
}

@Composable
fun CertificateScanDialog(
  mode: ScanMode,
  patientId: String? = null,
  user: UserContext? = null,
  onDismiss: () -> Unit,
) {
  val viewModel =
    koinViewModel<CertificateScanViewModel>(key = "${mode.name}:$patientId") {
      parametersOf(mode, patientId, user)
    }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(Res.string.scan_title), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onDismiss) { Text(stringResource(Res.string.scan_close)) }
        }
        when (val stage = state.stage) {
          ScanStage.Scanning ->
            ScanInput(
              scanError = state.scanError,
              onScan = viewModel::onScan,
              onText = viewModel::onText,
              modifier = Modifier.weight(1f),
            )
          ScanStage.Working ->
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.scan_working))
              }
            }
          is ScanStage.Decoded ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
              Badge(
                stringResource(Res.string.scan_not_verified),
                MaterialTheme.colorScheme.tertiary,
              )
              stage.hcert.dvc?.let { CertificateFields(it, viewModel.products) }
              Field(stringResource(Res.string.scan_field_issuer), stage.hcert.iss ?: "?")
              TrustListLine(state.trustList)
              state.scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
              Spacer(Modifier.height(16.dp))
              if (mode == ScanMode.ReadOnly && viewModel.canRecord) {
                Button(onClick = viewModel::recordDecoded, Modifier.fillMaxWidth()) {
                  Text(stringResource(Res.string.scan_record))
                }
              }
              if (mode == ScanMode.ReadOnly) {
                OutlinedButton(onClick = viewModel::verifyDecoded, Modifier.fillMaxWidth()) {
                  Text(stringResource(Res.string.scan_verify))
                }
              }
              OutlinedButton(onClick = viewModel::reset, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_scan_again))
              }
            }
          is ScanStage.Recorded ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
              val outcome = stage.outcome
              Badge(
                stringResource(
                  if (outcome is RecordOutcome.Recorded) Res.string.scan_recorded
                  else Res.string.scan_recorded_already
                ),
                if (outcome is RecordOutcome.Recorded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
              )
              Text(
                when (outcome) {
                  is RecordOutcome.Recorded ->
                    when (val proposal = outcome.proposal) {
                      is DoseProposal.Mapped ->
                        stringResource(
                          Res.string.scan_recorded_lao,
                          proposal.display ?: proposal.code,
                        )
                      else -> stringResource(Res.string.scan_recorded_foreign)
                    }
                  is RecordOutcome.AlreadyRecorded ->
                    stringResource(Res.string.scan_recorded_already)
                  RecordOutcome.NoVaccination -> stringResource(Res.string.scan_recorded_none)
                },
                style = MaterialTheme.typography.bodyLarge,
              )
              stage.hcert.dvc?.let { CertificateFields(it, viewModel.products) }
              Spacer(Modifier.height(16.dp))
              Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_done))
              }
            }
          is ScanStage.Verified ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
              Badge(stringResource(Res.string.scan_verified), MaterialTheme.colorScheme.primary)
              stage.hcert.dvc?.let { CertificateFields(it, viewModel.products) }
              Field(
                stringResource(Res.string.scan_field_issuer),
                listOfNotNull(stage.hcert.iss, stage.hcert.dsc.participant?.let { "GDHCN $it" })
                  .joinToString(", "),
              )
              Field(stringResource(Res.string.scan_field_dsc), stage.hcert.dsc.kid)
              TrustListLine(stage.trustList)
              Spacer(Modifier.height(16.dp))
              Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_done))
              }
              OutlinedButton(onClick = viewModel::reset, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_scan_again))
              }
            }
          is ScanStage.Imported ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
              when (val outcome = stage.outcome) {
                is ImportOutcome.Imported -> {
                  Badge(
                    stringResource(
                      if (outcome.walkIn) Res.string.scan_imported_walk_in
                      else Res.string.scan_imported
                    ),
                    MaterialTheme.colorScheme.primary,
                  )
                  stage.hcert.dvc?.let { CertificateFields(it, viewModel.products) }
                  Field(stringResource(Res.string.scan_field_dsc), stage.hcert.dsc.kid)
                  if (outcome.needsConfirmation) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                      stringResource(
                        Res.string.scan_confirm_prompt,
                        stage.hcert.dvc?.let { viewModel.products.display(it.vx.vp) } ?: "",
                      ),
                      style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(Res.string.scan_confirm_hint))
                    Spacer(Modifier.height(8.dp))
                    stage.doses.forEach { dose ->
                      OutlinedButton(
                        onClick = { viewModel.confirmDose(DoseChoice.Dose(dose)) },
                        Modifier.fillMaxWidth(),
                      ) {
                        Text(dose.display)
                      }
                    }
                    Button(
                      onClick = { viewModel.confirmDose(DoseChoice.NotCounted) },
                      Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                      Text(stringResource(Res.string.scan_confirm_not_counted))
                    }
                  } else {
                    val lao =
                      outcome.immunization.vaccineCode.coding.firstOrNull {
                        it.system?.value == LAO_DOSE_SYSTEM
                      }
                    Text(
                      lao?.display?.value?.let { stringResource(Res.string.scan_recorded_lao, it) }
                        ?: stringResource(Res.string.scan_recorded_foreign)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                      Text(stringResource(Res.string.scan_done))
                    }
                  }
                }
                is ImportOutcome.AlreadyImported -> {
                  Badge(
                    stringResource(Res.string.scan_imported_already),
                    MaterialTheme.colorScheme.tertiary,
                  )
                  Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.scan_done))
                  }
                }
                is ImportOutcome.WrongChild -> {
                  Badge(
                    stringResource(Res.string.scan_wrong_child),
                    MaterialTheme.colorScheme.error,
                  )
                  Text(
                    stringResource(
                      Res.string.scan_wrong_child_detail,
                      outcome.certificateName,
                      outcome.certificateBirthDate,
                    )
                  )
                }
                ImportOutcome.NoVaccination -> Text(stringResource(Res.string.scan_recorded_none))
              }
              OutlinedButton(onClick = viewModel::reset, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_scan_again))
              }
            }
          is ScanStage.Confirmed ->
            Column(Modifier.weight(1f)) {
              Badge(stringResource(Res.string.scan_confirmed), MaterialTheme.colorScheme.primary)
              Text(
                when (val choice = stage.choice) {
                  is DoseChoice.Dose ->
                    stringResource(Res.string.scan_recorded_lao, choice.dose.display)
                  DoseChoice.NotCounted -> stringResource(Res.string.scan_confirm_not_counted_done)
                }
              )
              Spacer(Modifier.height(16.dp))
              Button(onClick = onDismiss, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_done))
              }
              OutlinedButton(onClick = viewModel::reset, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_scan_again))
              }
            }
          is ScanStage.Failed ->
            Column(Modifier.weight(1f)) {
              Badge(stringResource(Res.string.scan_failed), MaterialTheme.colorScheme.error)
              Text(
                "${stage.failure.step}: ${stage.failure.reason}",
                style = MaterialTheme.typography.titleMedium,
              )
              if (stage.failure.detail.isNotBlank()) Text(stage.failure.detail)
              TrustListLine(stage.trustList)
              Spacer(Modifier.height(16.dp))
              OutlinedButton(onClick = viewModel::reset, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_scan_again))
              }
            }
        }
      }
    }
  }
}

@Composable
private fun ScanInput(
  scanError: String?,
  onScan: (QrScan) -> Unit,
  onText: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var pasted by remember { mutableStateOf("") }
  Column(modifier) {
    if (cameraScanningAvailable) {
      QrScannerView(Modifier.weight(1f).fillMaxWidth(), onResult = onScan)
    } else {
      QrScannerView(Modifier.fillMaxWidth(), onResult = onScan)
      Spacer(Modifier.weight(1f))
    }
    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
      value = pasted,
      onValueChange = { pasted = it },
      label = { Text(stringResource(Res.string.scan_paste_label)) },
      minLines = 2,
      maxLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )
    Button(
      onClick = { onText(pasted) },
      enabled = pasted.isNotBlank(),
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
      Text(stringResource(Res.string.scan_use_text))
    }
  }
}

@Composable
private fun CertificateFields(dvc: DvcMin, products: PreQualProducts) {
  Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Field(stringResource(Res.string.scan_field_name), dvc.n)
      Field(stringResource(Res.string.scan_field_dob), dvc.dob)
      Field(stringResource(Res.string.scan_field_vaccine), products.display(dvc.vx.vp))
      Field(stringResource(Res.string.scan_field_date), dvc.vx.dv)
      Field(stringResource(Res.string.scan_field_lot), dvc.vx.bo)
      dvc.vx.cn?.let { Field(stringResource(Res.string.scan_field_clinician), it) }
    }
  }
}

@Composable
private fun Field(label: String, value: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
  Text(
    text,
    color = color,
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(vertical = 8.dp),
  )
}

@Composable
private fun TrustListLine(snapshot: TrustListSnapshot?) {
  val text =
    when {
      snapshot == null -> stringResource(Res.string.scan_trust_list_none)
      snapshot.bundled ->
        stringResource(
          Res.string.scan_trust_list_bundled,
          formatAge(snapshot.ageSeconds(HcertClock.nowEpochSeconds())),
        )
      else ->
        stringResource(
          Res.string.scan_trust_list,
          snapshot.source.substringAfter("://").substringBefore('/'),
          formatAge(snapshot.ageSeconds(HcertClock.nowEpochSeconds())),
        )
    }
  Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

/** "3 days", "2 hours", "5 minutes": enough for a nurse to see how stale the keys are. */
fun formatAge(seconds: Long): String =
  when {
    seconds >= 2 * 86_400 -> "${seconds / 86_400} days"
    seconds >= 86_400 -> "1 day"
    seconds >= 2 * 3_600 -> "${seconds / 3_600} hours"
    seconds >= 3_600 -> "1 hour"
    seconds >= 120 -> "${seconds / 60} minutes"
    else -> "1 minute"
  }

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
package dev.ohs.vaxlink.feature.referral

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Practitioner
import dev.ohs.fhir.model.r4.ServiceRequest
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.referral_inbox_empty
import dev.ohs.vaxlink.generated.resources.referral_inbox_from
import dev.ohs.vaxlink.generated.resources.referral_inbox_title
import dev.ohs.vaxlink.util.idOf
import dev.ohs.vaxlink.workflow.REFERRAL_CODE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** One open referral addressed to the clinic, as the inbox shows it. */
data class InboxReferral(
  val referralId: String,
  val patientId: String,
  val childName: String,
  val reason: String,
  val referredBy: String?,
  val referredOn: String?,
)

/** Open referrals addressed to [organizationId], newest first. */
suspend fun FhirRepository.referralInbox(organizationId: String): List<InboxReferral> {
  val patients = all("Patient").filterIsInstance<Patient>().associateBy { it.id }
  val practitioners = all("Practitioner").filterIsInstance<Practitioner>().associateBy { it.id }
  return all("ServiceRequest")
    .filterIsInstance<ServiceRequest>()
    .filter {
      it.status.value == ServiceRequest.RequestStatus.Active &&
        it.intent.value == ServiceRequest.RequestIntent.Order &&
        it.code?.coding?.any { c -> c.code?.value == REFERRAL_CODE } == true &&
        it.performer.any { p -> p.idOf("Organization") == organizationId }
    }
    .sortedByDescending { it.authoredOn?.value?.toString().orEmpty() }
    .mapNotNull { referral ->
      val patientId = referral.subject.idOf("Patient") ?: return@mapNotNull null
      val patient = patients[patientId]
      InboxReferral(
        referralId = referral.id.orEmpty(),
        patientId = patientId,
        childName = patient?.displayName() ?: patientId,
        reason = referral.reasonCode.firstNotNullOfOrNull { it.text?.value }.orEmpty(),
        referredBy =
          referral.requester.idOf("Practitioner")?.let { practitioners[it]?.displayName() },
        referredOn = referral.authoredOn?.value?.toString()?.take(10),
      )
    }
}

private fun Patient.displayName(): String =
  name.firstOrNull()?.let { n ->
    (n.given.map { it.value } + n.family?.value).filterNotNull().joinToString(" ")
  } ?: id.orEmpty()

private fun Practitioner.displayName(): String? =
  name.firstOrNull()?.let { n ->
    (n.given.map { it.value } + n.family?.value).filterNotNull().joinToString(" ")
  }

class ReferralInboxViewModel(
  private val repository: FhirRepository,
  private val user: UserContext,
) : ViewModel() {
  private val _referrals = MutableStateFlow<List<InboxReferral>?>(null)
  val referrals: StateFlow<List<InboxReferral>?> = _referrals.asStateFlow()

  init {
    viewModelScope.launch {
      repository.revision.collect {
        _referrals.value = repository.referralInbox(user.organizationId)
      }
    }
  }
}

@Composable
fun ReferralInboxScreen(user: UserContext, onPatientClick: (String) -> Unit) {
  val viewModel: ReferralInboxViewModel =
    koinViewModel(key = user.organizationId) { parametersOf(user) }
  val referrals by viewModel.referrals.collectAsStateWithLifecycle()
  val items = referrals
  if (items == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  Column(Modifier.fillMaxSize()) {
    Text(
      stringResource(Res.string.referral_inbox_title),
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(16.dp),
    )
    if (items.isEmpty()) {
      Text(stringResource(Res.string.referral_inbox_empty), Modifier.padding(horizontal = 16.dp))
      return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
      items(items, key = { it.referralId }) { referral ->
        Card(
          Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
            onPatientClick(referral.patientId)
          }
        ) {
          Column(Modifier.padding(16.dp)) {
            Text(referral.childName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(referral.reason, style = MaterialTheme.typography.bodyMedium)
            val from =
              listOfNotNull(
                  referral.referredBy?.let { stringResource(Res.string.referral_inbox_from, it) },
                  referral.referredOn,
                )
                .joinToString(", ")
            if (from.isNotBlank()) {
              Spacer(Modifier.height(4.dp))
              Text(from, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    }
  }
}

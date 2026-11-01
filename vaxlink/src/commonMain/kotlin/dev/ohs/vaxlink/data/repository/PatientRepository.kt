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
package dev.ohs.vaxlink.data.repository

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.player.client.model.SearchResult
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.PatientCareTaskState
import dev.ohs.player.generated.state.PatientSummaryState
import dev.ohs.vaxlink.data.Extraction.extractor
import dev.ohs.vaxlink.data.datasource.householdOf
import dev.ohs.vaxlink.data.datasource.patientProfileSearchResult
import dev.ohs.vaxlink.data.datasource.patientSummarySearchResult
import dev.ohs.vaxlink.data.datasource.patientSummarySearchResults
import dev.ohs.vaxlink.feature.patient.profile.ProfileUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PatientRepository(private val fhirRepository: FhirRepository) {

  // FhirPathEvaluator holds mutable state is not concurrent-safe.
  // limitedParallelism(1) serializes all extraction on a single background thread without any
  // explicit locking.
  private val extractorDispatcher = Dispatchers.Default.limitedParallelism(1)

  fun observePatients(): Flow<List<PatientSummaryState>> =
    fhirRepository.revision.map { getPatients() }

  suspend fun getPatients(): List<PatientSummaryState> =
    withContext(extractorDispatcher) {
      patientSummarySearchResults(fhirRepository).mapNotNull {
        extractor.extract<PatientSummaryState>(it).firstOrNull()
      }
    }

  fun observePatientProfile(patientId: String): Flow<ProfileUiState> =
    fhirRepository.revision.map { getPatientProfile(patientId) }

  suspend fun getPatientProfile(patientId: String): ProfileUiState =
    withContext(extractorDispatcher) {
      val summary =
        patientSummarySearchResult(patientId, fhirRepository) ?: return@withContext ProfileUiState()
      val record = patientProfileSearchResult(patientId, fhirRepository)!!
      val household =
        householdOf(patientId, fhirRepository)?.let {
          extractor.extract<HouseholdListState>(SearchResult<Resource>(resource = it)).firstOrNull()
        }
      ProfileUiState(
        patient = extractor.extract<PatientSummaryState>(summary).firstOrNull(),
        household = household,
        relationships = extractor.extract(record),
        immunizations = extractor.extract(record),
        conditions = extractor.extract(record),
        allergies = extractor.extract(record),
        medicationStatements = extractor.extract(record),
        careTasks =
          extractor.extract<PatientCareTaskState>(record).sortedBy { it.dueBy?.toString() },
        carePlans = extractor.extract(record),
        referrals = extractor.extract(record),
        outcomes = extractor.extract(record),
        treatments = extractor.extract(record),
        followUps = extractor.extract(record),
        visits = extractor.extract(record),
        vitals = extractor.extract(record),
      )
    }
}

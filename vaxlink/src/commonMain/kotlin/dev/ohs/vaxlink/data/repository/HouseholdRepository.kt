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

import dev.ohs.player.generated.state.HouseholdHeaderState
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.HouseholdMemberState
import dev.ohs.vaxlink.data.Extraction.extractor
import dev.ohs.vaxlink.data.datasource.householdSearchResult
import dev.ohs.vaxlink.data.datasource.householdSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A household as its screen shows it: the header row and one row per member, head first. */
data class HouseholdUiState(
  val household: HouseholdHeaderState,
  val members: List<HouseholdMemberState>,
)

class HouseholdRepository(private val fhirRepository: FhirRepository) {

  // See PatientRepository: extraction runs on one thread because the evaluator is not thread-safe.
  private val extractorDispatcher = Dispatchers.Default.limitedParallelism(1)

  fun observeHouseholds(): Flow<List<HouseholdListState>> =
    fhirRepository.revision.map { getHouseholds() }

  suspend fun getHouseholds(): List<HouseholdListState> =
    withContext(extractorDispatcher) {
      householdSearchResults(fhirRepository).mapNotNull {
        extractor.extract<HouseholdListState>(it).firstOrNull()
      }
    }

  fun observeHousehold(householdId: String): Flow<HouseholdUiState?> =
    fhirRepository.revision.map { getHousehold(householdId) }

  suspend fun getHousehold(householdId: String): HouseholdUiState? =
    withContext(extractorDispatcher) {
      val result = householdSearchResult(householdId, fhirRepository) ?: return@withContext null
      val header =
        extractor.extract<HouseholdHeaderState>(result).firstOrNull() ?: return@withContext null
      val members =
        extractor
          .extract<HouseholdMemberState>(result)
          .sortedWith(
            compareBy<HouseholdMemberState> { it.isHead != true }
              .thenBy { it.memberBirthDate?.toString() }
          )
      HouseholdUiState(header, members)
    }
}

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
package dev.ohs.vaxlink.feature.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.vaxlink.data.repository.HouseholdRepository
import dev.ohs.vaxlink.data.repository.HouseholdUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HouseholdListViewModel(repository: HouseholdRepository) : ViewModel() {
  private val _households = MutableStateFlow<List<HouseholdListState>?>(null)
  val households: StateFlow<List<HouseholdListState>?> = _households.asStateFlow()

  init {
    viewModelScope.launch { repository.observeHouseholds().collect { _households.value = it } }
  }
}

/** One household's screen; [Loaded.state] is null when the household does not exist. */
sealed interface HouseholdScreenState {
  data object Loading : HouseholdScreenState

  data class Loaded(val state: HouseholdUiState?) : HouseholdScreenState
}

class HouseholdViewModel(householdId: String, repository: HouseholdRepository) : ViewModel() {
  private val _uiState = MutableStateFlow<HouseholdScreenState>(HouseholdScreenState.Loading)
  val uiState: StateFlow<HouseholdScreenState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      repository.observeHousehold(householdId).collect {
        _uiState.value = HouseholdScreenState.Loaded(it)
      }
    }
  }
}

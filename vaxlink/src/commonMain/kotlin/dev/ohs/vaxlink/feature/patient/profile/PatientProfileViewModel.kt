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
package dev.ohs.vaxlink.feature.patient.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.vaxlink.data.repository.PatientRepository
import dev.ohs.vaxlink.workflow.ScheduleCatalogue
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PatientProfileViewModel(
  patientId: String,
  patientRepository: PatientRepository,
  placements: suspend () -> Map<String, TaskPlacement> = { ScheduleCatalogue.load() },
) : ViewModel() {
  private val _uiState = MutableStateFlow<ProfileUiState?>(null)
  val uiState: StateFlow<ProfileUiState?> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val catalogue = placements()
      patientRepository.observePatientProfile(patientId).collect {
        _uiState.value = it.copy(placements = catalogue)
      }
    }
  }
}

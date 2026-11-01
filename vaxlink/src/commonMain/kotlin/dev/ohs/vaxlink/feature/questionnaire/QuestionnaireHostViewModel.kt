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
package dev.ohs.vaxlink.feature.questionnaire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ohs.fhir.model.r4.Questionnaire as QuestionnaireR4
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.vaxlink.data.sync.SyncManager
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen-level state holder for hosting a single questionnaire. All FHIR work is delegated to
 * [QuestionnaireService].
 */
class QuestionnaireHostViewModel(
  private val questionnaireId: String,
  private val launchContext: QuestionnaireLaunchContext,
  private val questionnaireService: QuestionnaireService,
  private val syncManager: SyncManager,
  private val appScope: CoroutineScope,
) : ViewModel() {

  private val _uiState =
    MutableStateFlow<QuestionnaireHostUiState>(QuestionnaireHostUiState.Loading)
  val uiState: StateFlow<QuestionnaireHostUiState> = _uiState.asStateFlow()

  private var loadedQuestionnaire: QuestionnaireR4? = null

  init {
    load()
  }

  fun load() {
    _uiState.value = QuestionnaireHostUiState.Loading
    viewModelScope.launch {
      runCatching {
          val questionnaire = questionnaireService.getQuestionnaire(questionnaireId, launchContext)
          loadedQuestionnaire = questionnaire
          questionnaireService.toJson(questionnaire) to questionnaire.title?.value
        }
        .onSuccess { (json, title) -> _uiState.value = QuestionnaireHostUiState.Ready(json, title) }
        .onFailure { throwable ->
          _uiState.value =
            QuestionnaireHostUiState.Error(throwable.message ?: "Failed to load questionnaire.")
        }
    }
  }

  fun onSubmit(response: QuestionnaireResponse) {
    val questionnaire = loadedQuestionnaire ?: return
    val current = _uiState.value as? QuestionnaireHostUiState.Ready ?: return

    _uiState.value = QuestionnaireHostUiState.Submitting(current.questionnaireJson, current.title)

    // The app scope lets the submit finish if the user leaves the form mid-way.
    appScope.launch {
      _uiState.value =
        try {
          val result = questionnaireService.submit(questionnaire, response, launchContext)
          upload()
          QuestionnaireHostUiState.Submitted(result)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          current.copy(error = e.message ?: "Failed to submit questionnaire.")
        }
    }
  }

  /**
   * Sends the new records up straight away instead of waiting for the periodic sync. Runs in the
   * app scope because the form closes on submit; offline, it fails quietly and the next sync
   * uploads them.
   */
  private fun upload() {
    appScope.launch {
      try {
        syncManager.syncNow()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {}
    }
  }
}

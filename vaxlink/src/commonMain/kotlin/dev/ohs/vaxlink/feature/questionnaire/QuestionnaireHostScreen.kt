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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ohs.fhir.datacapture.Questionnaire
import dev.ohs.fhir.datacapture.QuestionnaireConfig
import dev.ohs.fhir.datacapture.QuestionnaireItemViewFactoryMatcher
import dev.ohs.fhir.datacapture.QuestionnaireItemViewFactoryMatchersProvider
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.questionnaire_back
import dev.ohs.vaxlink.generated.resources.questionnaire_close
import dev.ohs.vaxlink.generated.resources.questionnaire_retry
import dev.ohs.vaxlink.generated.resources.questionnaire_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun QuestionnaireHostScreen(
  questionnaireId: String,
  patientId: String? = null,
  taskId: String? = null,
  householdId: String? = null,
  user: UserContext? = null,
  onBack: () -> Unit,
  onSubmitted: (message: String) -> Unit,
) {
  val launchContext =
    remember(patientId, user, taskId, householdId) {
      QuestionnaireLaunchContext(
        patientId = patientId,
        user = user,
        taskId = taskId,
        householdId = householdId,
      )
    }
  val viewItemMatchersProvider = remember {
    object : QuestionnaireItemViewFactoryMatchersProvider {
      override fun get(): List<QuestionnaireItemViewFactoryMatcher> = listOf()
    }
  }

  val viewModel =
    koinViewModel<QuestionnaireHostViewModel>(
      key = "$questionnaireId:$patientId:$taskId:$householdId"
    ) {
      parametersOf(questionnaireId, launchContext)
    }
  val uiState by viewModel.uiState.collectAsState()
  val coroutineScope = rememberCoroutineScope()

  val snackbarHostState = remember { SnackbarHostState() }
  val submitError = (uiState as? QuestionnaireHostUiState.Ready)?.error
  LaunchedEffect(submitError) { submitError?.let { snackbarHostState.showSnackbar(it) } }
  LaunchedEffect(uiState) {
    val submitted = uiState as? QuestionnaireHostUiState.Submitted ?: return@LaunchedEffect
    onSubmitted(submitted.result.successMessage)
  }
  val title =
    when (val state = uiState) {
      is QuestionnaireHostUiState.Ready -> state.title
      is QuestionnaireHostUiState.Submitting -> state.title
      else -> null
    }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ) {
        Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
          Row(
            modifier =
              Modifier.align(Alignment.Center)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(onClick = onBack) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.questionnaire_back),
              )
            }
            Text(
              text = title ?: stringResource(Res.string.questionnaire_title),
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = onBack) {
              Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(Res.string.questionnaire_close),
              )
            }
          }
        }
      }
    },
  ) { padding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
          when (val state = uiState) {
            is QuestionnaireHostUiState.Loading -> CircularProgressIndicator()

            is QuestionnaireHostUiState.Error -> {
              Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = state.message,
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(16.dp),
                )
                TextButton(onClick = viewModel::load) {
                  Text(stringResource(Res.string.questionnaire_retry))
                }
              }
            }

            is QuestionnaireHostUiState.Ready,
            is QuestionnaireHostUiState.Submitting -> {
              val questionnaireJson =
                when (state) {
                  is QuestionnaireHostUiState.Ready -> state.questionnaireJson
                  is QuestionnaireHostUiState.Submitting -> state.questionnaireJson
                  else -> ""
                }
              Questionnaire(
                questionnaireJson = questionnaireJson,
                questionnaireLaunchContextMap = emptyMap(),
                config =
                  QuestionnaireConfig(
                    showReviewPage = true,
                    showReviewPageFirst = false,
                    isReadOnly = false,
                    showCancelButton = false,
                  ),
                onSubmit = { getResponse ->
                  coroutineScope.launch { viewModel.onSubmit(getResponse()) }
                },
                matchersProvider = viewItemMatchersProvider,
                onCancel = {},
              )
            }

            is QuestionnaireHostUiState.Submitted -> Unit
          }
        }
      }
    }
  }
}

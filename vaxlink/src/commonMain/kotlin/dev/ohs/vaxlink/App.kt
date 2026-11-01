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
package dev.ohs.vaxlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.auth.AuthState
import dev.ohs.vaxlink.auth.AuthViewModel
import dev.ohs.vaxlink.auth.UserContext
import dev.ohs.vaxlink.auth.rememberAuthorizationLauncher
import dev.ohs.vaxlink.feature.home.HomeScreen
import dev.ohs.vaxlink.feature.household.HouseholdScreen
import dev.ohs.vaxlink.feature.login.LoginScreen
import dev.ohs.vaxlink.feature.patient.profile.PatientProfileScreen
import dev.ohs.vaxlink.feature.patient.profile.ProgrammeActions
import dev.ohs.vaxlink.feature.patient.profile.ReferToClinicAction
import dev.ohs.vaxlink.feature.questionnaire.QuestionnaireHostScreen
import dev.ohs.vaxlink.feature.questionnaire.QuestionnaireIds
import dev.ohs.vaxlink.feature.role.NoRoleScreen
import dev.ohs.vaxlink.feature.role.UserContextState
import dev.ohs.vaxlink.feature.role.UserContextViewModel
import dev.ohs.vaxlink.feature.scan.ScanCertificateAction
import dev.ohs.vaxlink.feature.scan.ScanMode
import dev.ohs.vaxlink.feature.sync.InitialSyncGateState
import dev.ohs.vaxlink.feature.sync.InitialSyncScreen
import dev.ohs.vaxlink.feature.sync.InitialSyncViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App() {
  val registry = remember { buildAppViewRegistry() }

  CompositionLocalProvider(LocalViewRegistry provides registry) {
    OhsPlayerTheme {
      val authViewModel: AuthViewModel = koinViewModel()
      val launcher = rememberAuthorizationLauncher()
      val authState by authViewModel.state.collectAsStateWithLifecycle()
      val signingIn by authViewModel.signingIn.collectAsStateWithLifecycle()
      val authError by authViewModel.error.collectAsStateWithLifecycle()

      LaunchedEffect(launcher) { authViewModel.bootstrap(launcher) }

      when (authState) {
        is AuthState.Loading ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        is AuthState.Unauthenticated ->
          LoginScreen(
            signingIn = signingIn,
            error = authError,
            onSignIn = { authViewModel.login(launcher) },
            onErrorDismiss = { authViewModel.clearError() },
          )
        is AuthState.Authenticated -> {
          val session = (authState as AuthState.Authenticated).session
          val userName =
            session.user.fullName.ifBlank { session.user.username }.ifBlank { session.user.email }
          val contextViewModel: UserContextViewModel = koinViewModel(key = session.user.subject)
          val contextState by contextViewModel.state.collectAsStateWithLifecycle()
          LaunchedEffect(session.accessToken) { contextViewModel.resolve() }

          when (val state = contextState) {
            UserContextState.Loading ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
            UserContextState.NoRole ->
              NoRoleScreen(message = null, onRetry = null, onSignOut = { authViewModel.logout() })
            is UserContextState.Failed ->
              NoRoleScreen(
                message = state.message,
                onRetry = { contextViewModel.resolve() },
                onSignOut = { authViewModel.logout() },
              )
            is UserContextState.Ready ->
              SignedInApp(state.context, userName, onSignOut = { authViewModel.logout() })
          }
        }
      }
    }
  }
}

@Composable
private fun SignedInApp(context: UserContext, userName: String, onSignOut: () -> Unit) {
  val initialSyncViewModel: InitialSyncViewModel = koinViewModel()
  val gateState by initialSyncViewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { initialSyncViewModel.start() }

  when (gateState) {
    InitialSyncGateState.Checking,
    InitialSyncGateState.Syncing,
    is InitialSyncGateState.Failed ->
      InitialSyncScreen(
        state = gateState,
        onRetry = { initialSyncViewModel.retry() },
        onContinueAnyway = { initialSyncViewModel.continueAnyway() },
      )
    InitialSyncGateState.Passed -> {
      val navController = rememberNavController()
      val snackbarHostState = remember { SnackbarHostState() }
      val scope = rememberCoroutineScope()
      Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "home") {
          composable("home") {
            HomeScreen(
              context = context,
              userName = userName,
              onPatientClick = { id -> navController.navigate("patientProfile/$id") },
              onRegisterPatient = {
                navController.navigate("questionnaireHost/${QuestionnaireIds.PATIENT_REGISTRATION}")
              },
              onHouseholdClick = { id -> navController.navigate("household/$id") },
              onRegisterHousehold = {
                navController.navigate("questionnaireHost/${QuestionnaireIds.REGISTER_HOUSEHOLD}")
              },
              onSignOut = onSignOut,
            )
          }

          composable(
            route =
              "questionnaireHost/{questionnaireId}?patientId={patientId}&taskId={taskId}" +
                "&householdId={householdId}",
            arguments =
              listOf(
                navArgument("questionnaireId") { type = NavType.StringType },
                navArgument("patientId") {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
                navArgument("taskId") {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
                navArgument("householdId") {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
              ),
          ) { back ->
            val questionnaireId =
              back.arguments?.read { getStringOrNull("questionnaireId") }.orEmpty()
            val patientId = back.arguments?.read { getStringOrNull("patientId") }
            val taskId = back.arguments?.read { getStringOrNull("taskId") }
            val householdId = back.arguments?.read { getStringOrNull("householdId") }
            QuestionnaireHostScreen(
              questionnaireId = questionnaireId,
              patientId = patientId,
              taskId = taskId,
              householdId = householdId,
              user = context,
              onBack = { navController.popBackStack() },
              onSubmitted = { message ->
                navController.popBackStack()
                scope.launch { snackbarHostState.showSnackbar(message) }
              },
            )
          }

          composable(
            route = "household/{householdId}",
            arguments = listOf(navArgument("householdId") { type = NavType.StringType }),
          ) { back ->
            val householdId = back.arguments?.read { getStringOrNull("householdId") }.orEmpty()
            HouseholdScreen(
              householdId = householdId,
              onBack = { navController.popBackStack() },
              onMemberClick = { id -> navController.navigate("patientProfile/$id") },
              onAddMember = {
                navController.navigate(
                  "questionnaireHost/${QuestionnaireIds.REGISTER_MEMBER}?householdId=$householdId"
                )
              },
              onRegisterNewborn = {
                navController.navigate(
                  "questionnaireHost/${QuestionnaireIds.REGISTER_NEWBORN}?householdId=$householdId"
                )
              },
              onRecordWashCheck = {
                navController.navigate(
                  "questionnaireHost/${QuestionnaireIds.RECORD_WASH_CHECK}?householdId=$householdId"
                )
              },
            )
          }

          composable(
            route = "patientProfile/{patientId}",
            arguments = listOf(navArgument("patientId") { type = NavType.StringType }),
          ) { back ->
            val patientId = back.arguments?.read { getStringOrNull("patientId") }.orEmpty()
            PatientProfileScreen(
              patientId = patientId,
              onBack = { navController.popBackStack() },
              onHouseholdClick = { id -> navController.navigate("household/$id") },
              // Scan a WHO certificate. The CHW reads it offline without trusting it and records
              // it on the child's record, then refers the child for verification (J2); the clinic
              // roles verify it against the cached GDHCN trust list first (J3).
              actions = { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  if (context.role == AppRole.CHW) {
                    ScanCertificateAction(mode = ScanMode.ReadOnly, patientId = patientId)
                    ReferToClinicAction(patientId = patientId, user = context, profile = profile)
                    // The mother's programmes: ANC, PNC and family planning (eCHIS forms).
                    ProgrammeActions(
                      profile = profile,
                      onLaunch = { id ->
                        navController.navigate("questionnaireHost/$id?patientId=$patientId")
                      },
                    )
                  } else {
                    ScanCertificateAction(mode = ScanMode.VerifyFirst)
                  }
                }
              },
            )
          }
        }
        SnackbarHost(
          snackbarHostState,
          Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
      }
    }
  }
}

@OptIn(ExperimentalUuidApi::class) fun generateId(): String = Uuid.random().toString()

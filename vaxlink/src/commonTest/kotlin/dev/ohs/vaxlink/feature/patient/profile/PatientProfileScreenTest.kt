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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import dev.ohs.player.client.registry.LocalViewRegistry
import dev.ohs.vaxlink.buildAppViewRegistry
import dev.ohs.vaxlink.data.di.repositoryModule
import dev.ohs.vaxlink.data.di.viewModelModule
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.data.repository.InMemorySampleFhirRepository
import dev.ohs.vaxlink.data.repository.SamplePatientFixture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class PatientProfileScreenTest {

  @BeforeTest
  fun setUp() = runTest {
    val repository = InMemorySampleFhirRepository()
    SamplePatientFixture.resources.forEach { repository.upsert(it) }
    startKoin {
      modules(module { single<FhirRepository> { repository } }, repositoryModule, viewModelModule)
    }
  }

  @AfterTest fun tearDown() = stopKoin()

  @Test
  fun knownPatient_rendersName() = runComposeUiTest {
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme { PatientProfileScreen(patientId = "p1", onBack = {}) }
      }
    }

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Amina Diallo").fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(onAllNodesWithText("Amina Diallo").fetchSemanticsNodes().isNotEmpty())
  }

  @Test
  fun overviewShowsTheShortList() = runComposeUiTest {
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme {
          PatientProfileScreen(
            patientId = "p1",
            onBack = {},
            onOpenSchedule = {},
            onOpenPast = {},
            onOpenSummary = {},
          )
        }
      }
    }

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Amina Diallo").fetchSemanticsNodes().isNotEmpty()
    }
    // The open referral and the immunisations section, and no clinical sections: those moved to
    // the summary page.
    onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Waiting at facility"))
    assertTrue(onAllNodesWithText("Waiting at facility").fetchSemanticsNodes().isNotEmpty())
    onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Patient summary"))
    assertTrue(onAllNodesWithText("Patient summary").fetchSemanticsNodes().isNotEmpty())
    assertTrue(onAllNodesWithText("Amoxicillin 250 mg dispersible").fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("CONDITIONS").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun summaryPageHoldsTheFullRecord() = runComposeUiTest {
    val registry = buildAppViewRegistry()
    setContent {
      CompositionLocalProvider(LocalViewRegistry provides registry) {
        MaterialTheme { PatientSummaryScreen(patientId = "p1", onBack = {}) }
      }
    }

    waitUntil(timeoutMillis = 5_000L) {
      onAllNodesWithText("Amina Diallo").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Amoxicillin 250 mg dispersible"))
    assertTrue(
      onAllNodesWithText("Amoxicillin 250 mg dispersible").fetchSemanticsNodes().isNotEmpty()
    )
    onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("CONDITIONS"))
    assertTrue(onAllNodesWithText("CONDITIONS").fetchSemanticsNodes().isNotEmpty())
  }
}

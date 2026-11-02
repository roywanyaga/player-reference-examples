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
package dev.ohs.vaxlink.feature.register

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Task
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.feature.component.common.StatusChipData
import dev.ohs.vaxlink.feature.component.common.StatusRow
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.register_empty
import dev.ohs.vaxlink.generated.resources.register_enrolled
import dev.ohs.vaxlink.generated.resources.register_next
import dev.ohs.vaxlink.generated.resources.register_nothing_scheduled
import dev.ohs.vaxlink.generated.resources.task_overdue
import dev.ohs.vaxlink.generated.resources.work_due
import dev.ohs.vaxlink.util.idOf
import dev.ohs.vaxlink.workflow.ANC_PLAN
import dev.ohs.vaxlink.workflow.FP_INJECTABLE_PLAN
import dev.ohs.vaxlink.workflow.FP_LONGTERM_PLAN
import dev.ohs.vaxlink.workflow.FP_ORAL_PLAN
import dev.ohs.vaxlink.workflow.PNC_PLAN
import dev.ohs.vaxlink.workflow.ScheduleCatalogue
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A programme with a register: the people currently enrolled in it. Enrolment is a CarePlan of one
 * of the programme's plans, so every register works the same way and a new programme is one more
 * entry here. The Condition (pregnancy, postpartum) stays the clinical record and is not read.
 */
enum class Programme(val plans: Set<String>) {
  Anc(setOf(ANC_PLAN)),
  Pnc(setOf(PNC_PLAN)),
  Fp(setOf(FP_INJECTABLE_PLAN, FP_ORAL_PLAN, FP_LONGTERM_PLAN)),
}

/** One enrolled person: who, since when, and the next visit the programme expects. */
data class RegisterEntry(
  val patientId: String,
  val name: String,
  val enrolledOn: String?,
  val nextTitle: String?,
  val nextDue: String?,
  val overdue: Boolean,
)

/**
 * The people with an active CarePlan in [programme], those with an overdue visit first, then by the
 * next due date. [placements] maps each Task's activity to its plan.
 */
suspend fun FhirRepository.programmeRegister(
  programme: Programme,
  placements: Map<String, TaskPlacement>,
  today: LocalDate,
): List<RegisterEntry> {
  val plans =
    all("CarePlan").filterIsInstance<CarePlan>().filter { plan ->
      plan.status.value == CarePlan.RequestStatus.Active &&
        plan.instantiatesCanonical.any { it.value in programme.plans }
    }
  if (plans.isEmpty()) return emptyList()
  val patients = all("Patient").filterIsInstance<Patient>().associateBy { it.id }
  val tasks =
    all("Task").filterIsInstance<Task>().filter {
      it.status.value in OPEN_STATUSES &&
        placements[it.instantiatesCanonical?.value]?.planUrl in programme.plans
    }
  val todayText = today.toString()
  return plans
    .groupBy { it.subject.idOf("Patient") }
    .mapNotNull { (patientId, own) ->
      patientId ?: return@mapNotNull null
      val next =
        tasks
          .filter { it.`for`.idOf("Patient") == patientId }
          .minByOrNull { it.restriction?.period?.end?.value?.toString().orEmpty().ifBlank { "~" } }
      val due = next?.restriction?.period?.end?.value?.toString()?.take(10)
      RegisterEntry(
        patientId = patientId,
        name = patients[patientId]?.displayName() ?: patientId,
        enrolledOn = own.mapNotNull { it.period?.start?.value?.toString()?.take(10) }.minOrNull(),
        nextTitle = next?.description?.value ?: next?.code?.text?.value,
        nextDue = due,
        overdue = due != null && due < todayText,
      )
    }
    .sortedWith(compareBy({ !it.overdue }, { it.nextDue ?: "~" }, { it.name }))
}

private val OPEN_STATUSES =
  setOf(
    Task.TaskStatus.Requested,
    Task.TaskStatus.Draft,
    Task.TaskStatus.Received,
    Task.TaskStatus.Accepted,
    Task.TaskStatus.Ready,
    Task.TaskStatus.In_Progress,
    Task.TaskStatus.On_Hold,
  )

private fun Patient.displayName(): String =
  name.firstOrNull()?.let { n ->
    (n.given.map { it.value } + n.family?.value).filterNotNull().joinToString(" ")
  } ?: id.orEmpty()

class ProgrammeRegisterViewModel(
  private val repository: FhirRepository,
  private val programme: Programme,
  private val placements: suspend () -> Map<String, TaskPlacement> = { ScheduleCatalogue.load() },
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {
  private val _entries = MutableStateFlow<List<RegisterEntry>?>(null)
  val entries: StateFlow<List<RegisterEntry>?> = _entries.asStateFlow()

  init {
    viewModelScope.launch {
      val catalogue = placements()
      repository.revision.collect {
        _entries.value = repository.programmeRegister(programme, catalogue, today())
      }
    }
  }
}

/** A register: who is enrolled in [programme] and what is due next for each. */
@Composable
fun ProgrammeRegisterScreen(programme: Programme, onPatientClick: (String) -> Unit) {
  val viewModel: ProgrammeRegisterViewModel =
    koinViewModel(key = programme.name) { parametersOf(programme) }
  val entries by viewModel.entries.collectAsStateWithLifecycle()
  val list = entries
  if (list == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  if (list.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(stringResource(Res.string.register_empty))
    }
    return
  }
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding =
      androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
  ) {
    items(list, key = { it.patientId }) { entry ->
      Column(Modifier.clickable { onPatientClick(entry.patientId) }) {
        StatusRow(
          title = entry.name,
          subtitle =
            listOfNotNull(
                entry.enrolledOn?.let { stringResource(Res.string.register_enrolled, it) },
                entry.nextTitle?.let { stringResource(Res.string.register_next, it) }
                  ?: stringResource(Res.string.register_nothing_scheduled),
              )
              .joinToString(" · "),
          status =
            entry.nextDue?.let { due ->
              if (entry.overdue)
                StatusChipData(
                  stringResource(Res.string.task_overdue),
                  MaterialTheme.colorScheme.errorContainer,
                  MaterialTheme.colorScheme.onErrorContainer,
                )
              else
                StatusChipData(
                  stringResource(Res.string.work_due, due),
                  MaterialTheme.colorScheme.tertiaryContainer,
                  MaterialTheme.colorScheme.onTertiaryContainer,
                )
            },
        )
      }
    }
  }
}

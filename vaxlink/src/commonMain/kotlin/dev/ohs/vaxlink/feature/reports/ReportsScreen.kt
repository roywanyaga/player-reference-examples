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
package dev.ohs.vaxlink.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.reports_next_month
import dev.ohs.vaxlink.generated.resources.reports_now_note
import dev.ohs.vaxlink.generated.resources.reports_previous_month
import dev.ohs.vaxlink.generated.resources.reports_title
import dev.ohs.vaxlink.workflow.ScheduleCatalogue
import dev.ohs.vaxlink.workflow.TaskPlacement
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The month a report covers and the counters for it, recomputed as the record changes. */
data class ReportState(val month: String, val counts: Map<ReportMetric, Int>?)

class ReportsViewModel(
  private val repository: FhirRepository,
  private val placements: suspend () -> Map<String, TaskPlacement> = { ScheduleCatalogue.load() },
  private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {
  private val month = MutableStateFlow(monthKey(today()))
  private val _state = MutableStateFlow(ReportState(month.value, null))
  val state: StateFlow<ReportState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val catalogue = placements()
      combine(repository.revision, month) { _, m -> m }
        .collect { m ->
          _state.value = ReportState(m, repository.reportCounts(m, today(), catalogue))
        }
    }
  }

  fun previousMonth() = shift(-1)

  fun nextMonth() = shift(1)

  private fun shift(by: Int) {
    val first = LocalDate.parse("${month.value}-01")
    month.value =
      monthKey(
        if (by < 0) first.minus(1, DateTimeUnit.MONTH) else first.plus(1, DateTimeUnit.MONTH)
      )
  }

  companion object {
    fun monthKey(date: LocalDate) = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"
  }
}

/** The reports page: counters grouped by what they describe, for a month the user can move. */
@Composable
fun ReportsScreen() {
  val viewModel: ReportsViewModel = koinViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val counts = state.counts
  Column {
    Text(
      stringResource(Res.string.reports_title),
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(16.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(onClick = viewModel::previousMonth) {
        Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(Res.string.reports_previous_month),
        )
      }
      Text(
        state.month,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      IconButton(onClick = viewModel::nextMonth) {
        Icon(
          Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = stringResource(Res.string.reports_next_month),
        )
      }
    }
    if (counts == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Column
    }
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
      item(key = "note") {
        Text(
          stringResource(Res.string.reports_now_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
      ReportGroup.entries.forEach { group ->
        item(key = "group:${group.name}") {
          Text(
            stringResource(group.label).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        ReportMetric.entries
          .filter { it.group == group }
          .forEach { metric ->
            item(key = metric.name) {
              Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  stringResource(metric.label),
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.weight(1f),
                )
                Text(
                  (counts[metric] ?: 0).toString(),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
          }
      }
    }
  }
}

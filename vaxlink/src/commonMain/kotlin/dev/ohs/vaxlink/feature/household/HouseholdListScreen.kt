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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ohs.player.client.layout.VerticalListRenderer
import dev.ohs.player.client.scaffold.ListScaffold
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.viewtype.ViewTypeCS
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.household_list_empty
import dev.ohs.vaxlink.generated.resources.household_list_register
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HouseholdListScreen(onHouseholdClick: (String) -> Unit, onRegister: () -> Unit) {
  val viewModel: HouseholdListViewModel = koinViewModel()
  val households by viewModel.households.collectAsStateWithLifecycle()

  if (households == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  Box(modifier = Modifier.fillMaxSize()) {
    ListScaffold<HouseholdListState>(
      items = households!!,
      onItemClick = { onHouseholdClick(it.householdId ?: "") },
      key = { it.householdId ?: it.hashCode().toString() },
    ) {
      component(ViewTypeCS.HouseholdCard)
      layout(VerticalListRenderer.VIEW_TYPE)
      emptyState { Text(stringResource(Res.string.household_list_empty)) }
    }
    FloatingActionButton(
      onClick = onRegister,
      modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
      Icon(
        Icons.Filled.Add,
        contentDescription = stringResource(Res.string.household_list_register),
      )
    }
  }
}

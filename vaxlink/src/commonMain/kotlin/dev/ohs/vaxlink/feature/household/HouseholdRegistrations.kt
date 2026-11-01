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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import dev.ohs.player.client.layout.VerticalListRenderer
import dev.ohs.player.client.registry.ViewRegistry
import dev.ohs.player.client.registry.registerComponent
import dev.ohs.player.client.registry.registerLayout
import dev.ohs.player.generated.config.HouseholdCardConfig
import dev.ohs.player.generated.config.HouseholdHeaderConfig
import dev.ohs.player.generated.config.MemberItemConfig
import dev.ohs.player.generated.state.HouseholdHeaderState
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.HouseholdMemberState
import dev.ohs.player.generated.viewtype.ViewTypeCS

fun ViewRegistry.registerHouseholds() {
  registerComponent<HouseholdListState, HouseholdCardConfig>(
    ViewTypeCS.HouseholdCard,
    HouseholdCardRenderer(),
    HouseholdCardConfig(),
  )
  registerLayout<HouseholdListState>(
    VerticalListRenderer.VIEW_TYPE,
    VerticalListRenderer(
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
      itemSpacing = 2.dp,
    ),
  )
  registerComponent<HouseholdHeaderState, HouseholdHeaderConfig>(
    ViewTypeCS.HouseholdHeader,
    HouseholdHeaderRenderer(),
    HouseholdHeaderConfig(),
  )
  registerComponent<HouseholdMemberState, MemberItemConfig>(
    ViewTypeCS.MemberItem,
    MemberItemRenderer(),
    MemberItemConfig(),
  )
}

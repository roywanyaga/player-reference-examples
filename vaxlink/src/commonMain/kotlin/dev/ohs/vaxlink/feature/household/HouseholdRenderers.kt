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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ohs.player.client.renderer.ComponentRenderer
import dev.ohs.player.client.renderer.RenderOptions
import dev.ohs.player.generated.config.HouseholdCardConfig
import dev.ohs.player.generated.config.HouseholdHeaderConfig
import dev.ohs.player.generated.config.MemberItemConfig
import dev.ohs.player.generated.state.HouseholdHeaderState
import dev.ohs.player.generated.state.HouseholdListState
import dev.ohs.player.generated.state.HouseholdMemberState
import dev.ohs.vaxlink.feature.component.common.Chip
import dev.ohs.vaxlink.feature.patient.list.calculateAge
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.household_head
import dev.ohs.vaxlink.generated.resources.household_head_name
import dev.ohs.vaxlink.generated.resources.household_member_count
import dev.ohs.vaxlink.generated.resources.household_village
import dev.ohs.vaxlink.generated.resources.label_age
import dev.ohs.vaxlink.generated.resources.name_unknown
import org.jetbrains.compose.resources.stringResource

/** A household on the register: name, village and how many people live there. */
class HouseholdCardRenderer : ComponentRenderer<HouseholdListState, HouseholdCardConfig> {
  @Composable
  override fun Render(
    item: HouseholdListState,
    config: HouseholdCardConfig,
    options: RenderOptions,
  ) {
    val onClick = options.onClick
    Row(
      modifier =
        options.modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
          .padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.Home,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.householdName ?: stringResource(Res.string.name_unknown),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        val parts = buildList {
          if (config.showVillage != false)
            item.village?.let { add(stringResource(Res.string.household_village, it)) }
          if (config.showMemberCount != false)
            item.memberCount?.let { add(stringResource(Res.string.household_member_count, it)) }
        }
        if (parts.isNotEmpty()) {
          Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** The household's heading on its own screen. */
class HouseholdHeaderRenderer : ComponentRenderer<HouseholdHeaderState, HouseholdHeaderConfig> {
  @Composable
  override fun Render(
    item: HouseholdHeaderState,
    config: HouseholdHeaderConfig,
    options: RenderOptions,
  ) {
    Row(
      modifier = options.modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.Home,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(36.dp),
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = item.householdName ?: stringResource(Res.string.name_unknown),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        val meta = buildList {
          item.village?.let { add(stringResource(Res.string.household_village, it)) }
          if (config.showMemberCount != false)
            item.memberCount?.let { add(stringResource(Res.string.household_member_count, it)) }
        }
        if (meta.isNotEmpty()) {
          Text(
            text = meta.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (config.showHeadName != false) {
          val head = listOfNotNull(item.headGivenName, item.headFamilyName).joinToString(" ")
          if (head.isNotBlank()) {
            Text(
              text = stringResource(Res.string.household_head_name, head),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** One member row: name, age and sex, with a badge on the head of household. */
class MemberItemRenderer : ComponentRenderer<HouseholdMemberState, MemberItemConfig> {
  @Composable
  override fun Render(
    item: HouseholdMemberState,
    config: MemberItemConfig,
    options: RenderOptions,
  ) {
    val onClick = options.onClick
    val name =
      listOfNotNull(item.memberGivenName, item.memberFamilyName).joinToString(" ").ifBlank {
        stringResource(Res.string.name_unknown)
      }
    val initials =
      buildString {
          item.memberGivenName?.firstOrNull()?.uppercaseChar()?.let { append(it) }
          item.memberFamilyName?.firstOrNull()?.uppercaseChar()?.let { append(it) }
        }
        .ifBlank { "?" }
    Row(
      modifier =
        options.modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
          .padding(horizontal = 8.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = initials,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          fontWeight = FontWeight.Bold,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
        )
        val parts = buildList {
          if (config.showAge != false)
            calculateAge(item.memberBirthDate?.toString())?.let {
              add(stringResource(Res.string.label_age, it))
            }
          if (config.showGender != false)
            item.memberGender?.let { add(it.replaceFirstChar { c -> c.uppercaseChar() }) }
        }
        if (parts.isNotEmpty()) {
          Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (item.isHead == true) Chip(label = stringResource(Res.string.household_head))
    }
  }
}

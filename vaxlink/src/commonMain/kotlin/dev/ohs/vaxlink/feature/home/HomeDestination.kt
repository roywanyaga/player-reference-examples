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
package dev.ohs.vaxlink.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.generated.resources.Res
import dev.ohs.vaxlink.generated.resources.home_destination_households
import dev.ohs.vaxlink.generated.resources.home_destination_patients
import dev.ohs.vaxlink.generated.resources.home_destination_referrals
import dev.ohs.vaxlink.generated.resources.role_chw
import dev.ohs.vaxlink.generated.resources.role_clinician
import dev.ohs.vaxlink.generated.resources.role_nurse
import org.jetbrains.compose.resources.StringResource

/** A top-level destination reachable from [HomeScreen]'s navigation drawer. */
enum class HomeDestination(val label: StringResource, val icon: ImageVector) {
  Households(label = Res.string.home_destination_households, icon = Icons.Filled.Home),
  Patients(label = Res.string.home_destination_patients, icon = Icons.Filled.Person),
  Referrals(label = Res.string.home_destination_referrals, icon = Icons.Filled.Notifications),
}

/**
 * The destinations a role works in, the first being where it lands. The CHW works household by
 * household (J2); the clinic roles start on the patient register. NE-3 adds the clinic's referral
 * inbox and NE-4 the viewer.
 */
fun AppRole.destinations(): List<HomeDestination> =
  when (this) {
    AppRole.CHW -> listOf(HomeDestination.Households, HomeDestination.Patients)
    AppRole.NURSE,
    AppRole.CLINICIAN ->
      listOf(HomeDestination.Referrals, HomeDestination.Patients, HomeDestination.Households)
  }

val AppRole.label: StringResource
  get() =
    when (this) {
      AppRole.CHW -> Res.string.role_chw
      AppRole.NURSE -> Res.string.role_nurse
      AppRole.CLINICIAN -> Res.string.role_clinician
    }

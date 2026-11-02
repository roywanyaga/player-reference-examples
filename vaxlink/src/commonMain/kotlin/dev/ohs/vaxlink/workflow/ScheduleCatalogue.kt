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
package dev.ohs.vaxlink.workflow

import dev.ohs.fhir.model.r4.PlanDefinition

/**
 * Where one scheduled activity sits: the plan it belongs to and how many days after the plan's
 * anchor (birth, for the Lao schedule) it falls due. A Task carries only its activity's URL, so the
 * profile reads the plan and the schedule contact from here.
 */
data class TaskPlacement(val planUrl: String, val planTitle: String, val offsetDays: Int)

/** The placement of every activity in the bundled plans, keyed by ActivityDefinition URL. */
object ScheduleCatalogue {
  /** Loads the bundled plans; empty when they cannot be read, so the profile still renders. */
  suspend fun load(): Map<String, TaskPlacement> =
    runCatching { from(BundledProtocols.load().plans()) }.getOrDefault(emptyMap())

  fun from(plans: List<PlanDefinition>): Map<String, TaskPlacement> = buildMap {
    plans.forEach { plan ->
      val planUrl = plan.url?.value ?: return@forEach
      val title = plan.title?.value ?: planUrl.substringAfterLast('/')
      plan.action.forEach { action ->
        val activity = action.definition?.asCanonical()?.value?.value ?: return@forEach
        val offset =
          action.extension
            .firstOrNull { it.url == SCHEDULE_OFFSET_EXTENSION }
            ?.extension
            ?.firstOrNull { it.url == "offset" }
            ?.value
            ?.asString()
            ?.value
            ?.value
            ?.let { runCatching { PlanScheduler.parsePeriod(it) }.getOrNull() }
        val days = offset?.let { it.years * 365 + it.months * 30 + it.days } ?: 0
        put(activity, TaskPlacement(planUrl, title, days))
      }
    }
  }

  /** How a schedule contact reads: birth, N weeks, or N months. */
  fun contactOf(days: Int): Contact =
    when {
      days <= 0 -> Contact(ContactUnit.Birth, 0)
      days <= 98 && days % 7 == 0 -> Contact(ContactUnit.Weeks, days / 7)
      else -> Contact(ContactUnit.Months, (days + 15) / 30)
    }
}

enum class ContactUnit {
  Birth,
  Weeks,
  Months,
}

data class Contact(val unit: ContactUnit, val count: Int)

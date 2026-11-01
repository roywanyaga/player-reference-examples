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
package dev.ohs.vaxlink.data.datasource

import dev.ohs.fhir.model.r4.Group
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.player.client.model.SearchResult
import dev.ohs.vaxlink.data.repository.FhirRepository
import dev.ohs.vaxlink.workflow.GROUP_CODE_SYSTEM
import dev.ohs.vaxlink.workflow.memberIds

/** True for the Groups the eCHIS household model marks as households. */
fun Group.isHousehold(): Boolean =
  code?.coding?.any { it.system?.value == GROUP_CODE_SYSTEM && it.code?.value == "household" } ==
    true

/** Household list rows: each household Group on its own, newest name last. */
suspend fun householdSearchResults(repository: FhirRepository): List<SearchResult<Resource>> =
  households(repository).map { SearchResult<Resource>(resource = it) }

/**
 * One household with its members included, mirroring `Group?_include=Group:member`. The header and
 * member rows both read from it.
 */
suspend fun householdSearchResult(
  householdId: String,
  repository: FhirRepository,
): SearchResult<Resource>? {
  val household = (repository.get("Group", householdId) as? Group) ?: return null
  val members = household.memberIds().mapNotNull { repository.get("Patient", it) as? Patient }
  return SearchResult(resource = household, included = mapOf("member" to members))
}

/** The household a patient belongs to, if any. */
suspend fun householdOf(patientId: String, repository: FhirRepository): Group? =
  households(repository).firstOrNull { patientId in it.memberIds() }

private suspend fun households(repository: FhirRepository) =
  repository
    .all("Group")
    .filterIsInstance<Group>()
    .filter { it.isHousehold() && it.active?.value != false }
    .sortedBy { it.name?.value }

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
package dev.ohs.vaxlink.data.sync

import dev.ohs.fhir.engine.sync.download.ResourceParamsBasedDownloadWorkManager.TimestampContext
import dev.ohs.fhir.engine.sync.download.UrlDownloadRequest
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.vaxlink.auth.AppRole
import dev.ohs.vaxlink.auth.UserContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private object NoTimestamps : TimestampContext {
  override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String?) =
    Unit

  override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? = null
}

class RoleDownloadWorkManagerTest {

  @Test
  fun downloadsWhatTheUsersRoleConfigNames() = runTest {
    val manager = RoleDownloadWorkManager(UserContext(AppRole.CHW, "p1", "o1", null), NoTimestamps)

    val urls = mutableListOf<String>()
    while (true) {
      val request = manager.getNextRequest() as? UrlDownloadRequest ?: break
      urls += request.url
    }

    // Schedule Tasks belong to the child, not a practitioner, so the CHW pulls every Task.
    assertTrue(urls.any { it.startsWith("Task?_sort=") }, "$urls")
    assertTrue(urls.any { it.startsWith("Immunization?") }, "$urls")
    assertTrue(urls.any { it.startsWith("Group?managing-entity=Organization/o1") }, "$urls")
    assertTrue(urls.any { it.startsWith("Practitioner?_id=p1") }, "$urls")
  }

  @Test
  fun downloadsNothingWithoutAUserContext() = runTest {
    assertNull(RoleDownloadWorkManager(null, NoTimestamps).getNextRequest())
  }
}

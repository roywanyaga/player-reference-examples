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
package dev.ohs.vaxlink.data.hcert

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ohs.vaxlink.generateId
import kotlinx.coroutines.flow.first

/**
 * A stable id for this app instance, generated once and kept beside the trust list (device
 * material, not user data, so a worker change does not reset it). It names the observer of every
 * AuditEvent the device writes.
 */
class DeviceIdentity(private val dataStore: DataStore<Preferences>) {
  suspend fun id(): String {
    dataStore.data.first()[DEVICE_ID]?.let {
      return it
    }
    val generated = generateId()
    dataStore.edit { it[DEVICE_ID] = it[DEVICE_ID] ?: generated }
    return dataStore.data.first()[DEVICE_ID] ?: generated
  }

  private companion object {
    val DEVICE_ID = stringPreferencesKey("device_id")
  }
}

package org.rhythmeta.maimaid.core.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.updateDataStore by preferencesDataStore(name = "app_update_state")

class AppUpdateStateStore(private val context: Context, private val json: Json) : AppUpdateStore {
    private val pendingKey = stringPreferencesKey("pending")

    override suspend fun read(): PendingAppUpdate? {
        val value = context.updateDataStore.data.first()[pendingKey] ?: return null
        return try {
            json.decodeFromString<PendingAppUpdate>(value)
        } catch (_: IllegalArgumentException) {
            save(null)
            null
        }
    }

    override suspend fun save(update: PendingAppUpdate?) {
        context.updateDataStore.edit {
            if (update == null) it.remove(pendingKey) else it[pendingKey] = json.encodeToString(update)
        }
    }
}

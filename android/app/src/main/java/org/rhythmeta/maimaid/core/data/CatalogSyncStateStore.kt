package org.rhythmeta.maimaid.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.catalogSyncDataStore by preferencesDataStore(name = "catalog_sync")

class CatalogSyncStateStore(
    private val context: Context,
) {
    suspend fun currentMd5(): String? = context.catalogSyncDataStore.data.first()[CatalogMd5Key]

    suspend fun isCurrentSchema(): Boolean =
        (context.catalogSyncDataStore.data.first()[CatalogSchemaVersionKey] ?: 0) >= CURRENT_CATALOG_SCHEMA_VERSION

    suspend fun save(version: String, md5: String) {
        context.catalogSyncDataStore.edit { preferences ->
            preferences[CatalogVersionKey] = version
            preferences[CatalogMd5Key] = md5
            preferences[CatalogSchemaVersionKey] = CURRENT_CATALOG_SCHEMA_VERSION
        }
    }

    /**
     * True while cover/preset-avatar images may still be missing on disk. The catalog itself is
     * already usable at that point, so the flag only decides whether the image sync service should
     * be (re)started — including after the process was killed mid-download.
     */
    suspend fun isImageAssetsSyncPending(): Boolean =
        context.catalogSyncDataStore.data.first()[ImageAssetsSyncPendingKey] ?: false

    suspend fun setImageAssetsSyncPending(pending: Boolean) {
        context.catalogSyncDataStore.edit { preferences ->
            preferences[ImageAssetsSyncPendingKey] = pending
        }
    }

    private companion object {
        const val CURRENT_CATALOG_SCHEMA_VERSION = 2
        val CatalogVersionKey = stringPreferencesKey("catalog_version")
        val CatalogMd5Key = stringPreferencesKey("catalog_md5")
        val CatalogSchemaVersionKey = intPreferencesKey("catalog_schema_version")
        val ImageAssetsSyncPendingKey = booleanPreferencesKey("image_assets_sync_pending")
    }
}

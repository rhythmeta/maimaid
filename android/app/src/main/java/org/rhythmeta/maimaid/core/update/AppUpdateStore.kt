package org.rhythmeta.maimaid.core.update

interface AppUpdateStore {
    suspend fun read(): PendingAppUpdate?
    suspend fun save(update: PendingAppUpdate?)
}

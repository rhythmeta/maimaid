package org.rhythmeta.maimaid.core.data

class CollectionSharingService {
    fun resolveImport(value: String): SongCollectionExport = SongCollectionCodec.decode(value)
}

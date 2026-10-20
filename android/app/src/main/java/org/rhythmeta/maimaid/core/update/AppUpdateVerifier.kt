package org.rhythmeta.maimaid.core.update

import java.io.File

fun interface AppUpdateVerifier {
    suspend fun verify(file: File, manifest: AppUpdateManifest, artifact: AppUpdateArtifact)
}

package org.rhythmeta.maimaid.core.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

object AppUpdateFileIntegrity {
    suspend fun verify(file: File, artifact: AppUpdateArtifact) {
        if (!file.isFile || file.length() != artifact.size) throw AppUpdateException(AppUpdateFailure.Integrity)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (hex != artifact.sha256) throw AppUpdateException(AppUpdateFailure.Integrity)
    }
}

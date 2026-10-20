package org.rhythmeta.maimaid.core.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidAppUpdateVerifier(private val context: Context) : AppUpdateVerifier {
    override suspend fun verify(file: File, manifest: AppUpdateManifest, artifact: AppUpdateArtifact) = withContext(Dispatchers.IO) {
        AppUpdateFileIntegrity.verify(file, artifact)
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AppUpdateException(AppUpdateFailure.Package)
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.packageName != context.packageName || archive.longVersionCode != manifest.versionCode ||
            archive.longVersionCode <= installed.longVersionCode) throw AppUpdateException(AppUpdateFailure.Package)
        val incoming = archive.signingInfo ?: throw AppUpdateException(AppUpdateFailure.Signature)
        val current = installed.signingInfo ?: throw AppUpdateException(AppUpdateFailure.Signature)
        val compatible = if (incoming.hasMultipleSigners() || current.hasMultipleSigners()) {
            incoming.apkContentsSigners.toSet() == current.apkContentsSigners.toSet()
        } else {
            incoming.signingCertificateHistory.orEmpty().contains(current.apkContentsSigners.firstOrNull())
        }
        if (!compatible) throw AppUpdateException(AppUpdateFailure.Signature)
    }
}

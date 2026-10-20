package org.rhythmeta.maimaid.core.update

data class AppDownloadSnapshot(
    val status: Status,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val failure: AppUpdateFailure = AppUpdateFailure.Download,
) {
    enum class Status { Running, Complete, Failed, Missing }
}

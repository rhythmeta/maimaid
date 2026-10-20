package org.rhythmeta.maimaid.ui.update

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.update.ApkInstaller
import org.rhythmeta.maimaid.core.update.AppUpdatePhase
import org.rhythmeta.maimaid.core.update.AppUpdatePolicy
import org.rhythmeta.maimaid.ui.common.openInAppBrowser

@Composable
fun AppUpdateHost(viewModel: AppUpdateViewModel, blocked: Boolean) {
    val context = LocalContext.current
    val installer = remember(context) { ApkInstaller(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requested by viewModel.dialogRequested.collectAsStateWithLifecycle()
    val shouldInstall by viewModel.shouldInstall.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(installer.canInstall()) }
    val installation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = installer.canInstall()
        if (canInstall) viewModel.install { file -> installation.launch(installer.installIntent(file)) }
    }
    LifecycleResumeEffect(viewModel) {
        resumed = true
        canInstall = installer.canInstall()
        viewModel.setForeground(true)
        onPauseOrDispose {
            resumed = false
            viewModel.setForeground(false)
        }
    }
    LaunchedEffect(viewModel) { viewModel.check(automatic = true) }
    LaunchedEffect(state.phase, shouldInstall, resumed, blocked, requested) {
        if (state.phase == AppUpdatePhase.Ready && shouldInstall && resumed && !blocked && requested) {
            viewModel.consumeInstallRequest()
            if (canInstall) viewModel.install { file -> installation.launch(installer.installIntent(file)) }
        }
    }
    AppUpdateDialog(
        show = requested && !blocked && resumed,
        state = state,
        canInstall = canInstall,
        onDismiss = viewModel::dismiss,
        onShare = {
            state.artifact?.let { artifact ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, artifact.url)
                }
                context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.update_share_chooser)))
            }
        },
        onDownload = viewModel::download,
        onCancel = viewModel::cancel,
        onCheck = { viewModel.check() },
        onInstall = {
            if (installer.canInstall()) viewModel.install { file -> installation.launch(installer.installIntent(file)) }
            else runCatching { permission.launch(installer.permissionIntent()) }.onFailure { viewModel.installationUnavailable() }
        },
        onViewChanges = { state.manifest?.let { context.openInAppBrowser(AppUpdatePolicy.compareUrl(BuildConfig.GIT_SHA, it.sha)) } },
    )
}

package org.rhythmeta.maimaid.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.BackendAccountConflict
import org.rhythmeta.maimaid.core.data.BackendAccountResolution
import org.rhythmeta.maimaid.core.data.BackendAuthUser
import org.rhythmeta.maimaid.core.data.BackendCloudRestorePreview
import org.rhythmeta.maimaid.core.data.BackendProfileConflictException
import org.rhythmeta.maimaid.core.data.BackendSessionNotice
import org.rhythmeta.maimaid.core.data.BackendWebAuthMode
import org.rhythmeta.maimaid.ui.common.openInAppBrowser
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class CloudOperation {
    Backup,
    Restore,
    Resolve,
    Logout,
}

@Composable
fun BackendAuthScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by container.backendSessionManager.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<org.rhythmeta.maimaid.core.backup.CloudBackup>>(emptyList()) }
    var restoreTarget by remember { mutableStateOf<org.rhythmeta.maimaid.core.backup.CloudBackup?>(null) }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { work() } catch (error: Exception) { message(error.localizedMessage ?: "Backup failed") }
            finally { busy = false }
        }
    }
    LaunchedEffect(sessionState.user?.id) {
        if (sessionState.user != null) run { snapshots = container.cloudBackupService.list() }
        else snapshots = emptyList()
    }
    LaunchedEffect(Unit) { container.backendSessionManager.checkSession() }
    LaunchedEffect(sessionState.notice) {
        sessionState.notice?.let {
            message(context.getString(if (it == BackendSessionNotice.LoginSucceeded) R.string.cloud_message_login_success else R.string.cloud_message_auth_link_failed))
            container.backendSessionManager.consumeNotice()
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AccountSummaryCard(sessionState.user) }
            if (sessionState.user == null) {
                item { CloudSection("Rhythmeta") {
                    CloudActionRow(Icons.AutoMirrored.Rounded.Login, stringResource(R.string.cloud_login), !busy) { openWebAuth(context, container, BackendWebAuthMode.Login, ::message) }
                    CloudActionRow(Icons.Rounded.AddCircleOutline, stringResource(R.string.cloud_register), !busy) { openWebAuth(context, container, BackendWebAuthMode.Register, ::message) }
                    CloudActionRow(Icons.Rounded.Key, stringResource(R.string.cloud_forgot_password), !busy) { openWebAuth(context, container, BackendWebAuthMode.Forgot, ::message) }
                } }
            } else {
                item { CloudSection(stringResource(R.string.cloud_sync_section)) {
                    CloudActionRow(Icons.Rounded.CloudUpload, stringResource(R.string.cloud_backup), !busy) {
                        run { container.cloudBackupService.backup(); snapshots = container.cloudBackupService.list(); message(context.getString(R.string.cloud_message_backup_success)) }
                    }
                    CloudActionRow(Icons.Rounded.Sync, stringResource(R.string.cloud_snapshots_refresh), !busy) { run { snapshots = container.cloudBackupService.list() } }
                    Text(stringResource(R.string.cloud_snapshot_hint), modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1)
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } }
                if (snapshots.isEmpty()) item { Text(stringResource(R.string.cloud_snapshots_empty), modifier = Modifier.padding(16.dp)) }
                snapshots.forEach { snapshot -> item(key = snapshot.id) {
                    CloudSection(formatBackupDate(snapshot.committedAt)) {
                        CloudValueRow(snapshot.deviceName, context.getString(R.string.cloud_snapshot_profiles, snapshot.profileCount))
                        CloudActionRow(Icons.Rounded.CloudDownload, stringResource(R.string.cloud_restore), !busy) { restoreTarget = snapshot }
                    }
                } }
                item { LogoutButton(enabled = !busy) { run { container.backendSessionManager.logout() } } }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (busy && restoreTarget == null) WindowDialog(
        show = true,
        title = stringResource(R.string.cloud_sync_section),
        onDismissRequest = {},
    ) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    restoreTarget?.let { snapshot -> WindowDialog(
        show = true,
        title = stringResource(R.string.cloud_restore),
        summary = stringResource(R.string.cloud_restore_replace_hint),
        onDismissRequest = { if (!busy) restoreTarget = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { restoreTarget = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_snapshot_cancel)) }
            Button(onClick = { run { container.cloudBackupService.restore(snapshot); restoreTarget = null; container.widgetUpdateCoordinator.requestUpdate(); message(context.getString(R.string.cloud_message_restore_success)) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_restore)) }
        }
    } }
}

@Composable
private fun AccountSummaryCard(user: BackendAuthUser?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(20.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (user == null) Icons.Rounded.AccountCircle else Icons.Rounded.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = user?.displayHandle ?: stringResource(R.string.cloud_login_title),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = user?.email ?: stringResource(R.string.cloud_login_subtitle),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.cloud_privacy_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CloudSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        SmallTitle(text = title, insideMargin = PaddingValues(horizontal = 4.dp, vertical = 8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            cornerRadius = 16.dp,
            content = content,
        )
    }
}

@Composable
private fun CloudActionRow(icon: ImageVector, title: String, enabled: Boolean, onClick: () -> Unit) {
    BasicComponent(
        title = title,
        enabled = enabled,
        onClick = onClick,
        startAction = { MonochromeIcon(icon) },
        endActions = {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.55f),
            )
        },
    )
}

@Composable
private fun CloudValueRow(title: String, value: String) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun MonochromeIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(24.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

private fun openWebAuth(
    context: Context,
    container: AppContainer,
    mode: BackendWebAuthMode,
    onError: (String) -> Unit,
) {
    val url = container.backendSessionManager.webAuthUrl(mode)
    if (url == null) {
        onError(context.getString(R.string.cloud_unconfigured))
        return
    }
    if (!context.openInAppBrowser(url)) {
        onError(context.getString(R.string.cloud_browser_unavailable))
    }
}

@Composable
private fun LogoutButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.error,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Logout,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.cloud_logout),
                color = MiuixTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun formatBackupDate(value: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    }
    return try {
        Instant.parse(value).atZone(ZoneId.systemDefault()).format(formatter)
    } catch (_: DateTimeParseException) {
        value
    }
}

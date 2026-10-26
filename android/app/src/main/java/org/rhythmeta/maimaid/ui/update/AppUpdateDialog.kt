package org.rhythmeta.maimaid.ui.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.update.AppUpdateFailure
import org.rhythmeta.maimaid.core.update.AppUpdatePhase
import org.rhythmeta.maimaid.core.update.AppUpdateState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AppUpdateDialog(
    show: Boolean,
    state: AppUpdateState,
    canInstall: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onViewChanges: () -> Unit,
) {
    val manifest = state.manifest
    val maxContentHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.58f }
    val title = when {
        manifest != null -> R.string.update_found
        state.phase == AppUpdatePhase.Latest -> R.string.update_latest
        else -> R.string.update_check
    }
    OverlayDialog(show = show, title = stringResource(title), onDismissRequest = onDismiss) {
        Column {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (manifest != null) {
                    Text(
                        text = "${BuildConfig.VERSION_CODE} → ${manifest.versionCode}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    UpdateInfoRow(stringResource(R.string.update_branch_commit), "${manifest.branch} · ${manifest.sha.take(8)}", true)
                    UpdateInfoRow(stringResource(R.string.update_author), manifest.author)
                    UpdateInfoRow(stringResource(R.string.update_time), localizedTime(manifest.builtAt, LocalConfiguration.current.locales[0]))
                    Text(stringResource(R.string.update_changes), style = MiuixTheme.textStyles.subtitle, modifier = Modifier.padding(top = 4.dp))
                    state.changes.commits.forEach { commit ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• ${commit.title}", style = MiuixTheme.textStyles.body2)
                            Text(commit.sha.take(8), style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    if (state.changes.incomplete) {
                        Text(stringResource(R.string.update_changes_incomplete), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    TextButton(text = stringResource(R.string.update_view_changes), onClick = onViewChanges, modifier = Modifier.fillMaxWidth())
                }
                when (state.phase) {
                    AppUpdatePhase.Checking -> {
                        Text(stringResource(R.string.update_checking))
                        LinearProgressIndicator()
                    }
                    AppUpdatePhase.Downloading -> Unit
                    AppUpdatePhase.Verifying -> {
                        Text(stringResource(R.string.update_verifying))
                        LinearProgressIndicator()
                    }
                    AppUpdatePhase.Ready -> Text(stringResource(if (canInstall) R.string.update_ready else R.string.update_permission))
                    AppUpdatePhase.Latest -> Text(stringResource(R.string.update_latest_description))
                    else -> Unit
                }
                state.failure?.let {
                    Text(stringResource(failureText(it)), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (manifest != null && state.phase == AppUpdatePhase.Failed) {
                        TextButton(text = stringResource(R.string.update_check_again), onClick = onCheck, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    AppUpdatePhase.Downloading, AppUpdatePhase.Verifying -> TextButton(
                        text = stringResource(android.R.string.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                    else -> if (manifest != null && state.artifact != null) {
                        TextButton(text = stringResource(R.string.update_share), onClick = onShare, modifier = Modifier.weight(1f))
                    }
                }
                when (state.phase) {
                    AppUpdatePhase.Downloading, AppUpdatePhase.Verifying -> {
                        val progress = if (state.totalBytes > 0) {
                            (state.downloadedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        DownloadProgressButton(
                            progress = progress,
                            label = progress?.let {
                                stringResource(R.string.update_download_percent, (it * 100).roundToInt())
                            } ?: stringResource(R.string.update_downloading),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppUpdatePhase.Ready -> TextButton(
                        text = stringResource(if (canInstall) R.string.update_install else R.string.update_allow_install),
                        onClick = onInstall, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    AppUpdatePhase.Available, AppUpdatePhase.Failed -> TextButton(
                        text = stringResource(if (manifest != null) R.string.update_download else R.string.update_retry),
                        onClick = if (manifest != null) onDownload else onCheck,
                        enabled = state.failure != AppUpdateFailure.Signature && state.failure != AppUpdateFailure.Package,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressButton(
    progress: Float?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MiuixTheme.colorScheme
    val animatedProgress = animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 320),
        label = "downloadProgress",
    )
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .semantics {
                progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                    ?: ProgressBarRangeInfo.Indeterminate
            }
            .squircleSurface(
                color = colorScheme.primary.copy(alpha = DownloadTrackAlpha),
                cornerRadius = ButtonDefaults.CornerRadius,
            )
            .drawBehind {
                val fraction = animatedProgress.value.coerceIn(0f, 1f)
                if (fraction > 0f) {
                    drawRect(
                        color = colorScheme.primary,
                        size = Size(width = size.width * fraction, height = size.height),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.button,
            color = colorScheme.primary,
            modifier = Modifier.padding(ButtonDefaults.InsideMargin),
        )
        // Repaint the label on top of the filled part so it stays legible across the wipe.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clearAndSetSemantics { }
                .drawWithContent {
                    clipRect(right = size.width * animatedProgress.value.coerceIn(0f, 1f)) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.button,
                color = colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun UpdateInfoRow(title: String, value: String, monospace: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent {
            Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, style = if (monospace) MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace) else MiuixTheme.textStyles.body2)
        }
    }
}

private fun localizedTime(time: String, locale: Locale): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
        .withZone(ZoneId.systemDefault()).format(Instant.parse(time))
}.getOrDefault(time)

private fun failureText(failure: AppUpdateFailure): Int = when (failure) {
    AppUpdateFailure.Network -> R.string.update_error_network
    AppUpdateFailure.InvalidManifest -> R.string.update_error_manifest
    AppUpdateFailure.UnsupportedDevice -> R.string.update_error_device
    AppUpdateFailure.Storage -> R.string.update_error_storage
    AppUpdateFailure.Download -> R.string.update_error_download
    AppUpdateFailure.Integrity -> R.string.update_error_integrity
    AppUpdateFailure.Package -> R.string.update_error_package
    AppUpdateFailure.Signature -> R.string.update_error_signature
    AppUpdateFailure.Install -> R.string.update_error_install
}

private const val DownloadTrackAlpha = 0.18f

package org.rhythmeta.maimaid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Storage
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AstroDxImportScreen(container: AppContainer, contentTopPadding: Dp) {
    val model = viewModel<AstroDxImportViewModel>(factory = AstroDxImportViewModel.Factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val manualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importManual(it, resolver) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = contentTopPadding + 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ImportSummaryCard(
                icon = Icons.Rounded.Storage,
                title = stringResource(R.string.astrodx_summary_title),
                summary = stringResource(R.string.astrodx_summary_description),
            )
        }
        item { ImportProfileSection(state.profile, state.best50Rating, container.presetAvatarRepository) }
        item {
            SmallTitle(text = stringResource(R.string.astrodx_step_authorize),
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = model::authorize,
                        enabled = !state.busy && !state.authorized,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (state.authorized) Icons.Rounded.CheckCircle else Icons.Rounded.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(
                            when {
                                state.authorized -> R.string.astrodx_authorized
                                state.busy -> R.string.astrodx_authorizing
                                else -> R.string.astrodx_authorize_action
                            },
                        ))
                    }
                    state.error?.let { Text(stringResource(it), color = MiuixTheme.colorScheme.error) }
                    if (state.busy && !state.importing) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    }
                }
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.astrodx_shizuku_description))
                        append(" ")
                        withLink(LinkAnnotation.Url("https://shizuku.rikka.app/")) {
                            withStyle(SpanStyle(
                                color = MiuixTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )) {
                            append(stringResource(R.string.astrodx_shizuku_what_is))
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (state.authorized) item {
            SmallTitle(text = stringResource(R.string.astrodx_step_import),
                insideMargin = PaddingValues(horizontal = 4.dp, vertical = 6.dp))
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = model::import,
                        enabled = !state.busy && state.profileId != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        if (state.busy) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.importing) R.string.astrodx_importing else R.string.astrodx_import_action))
                    }
                    Button(
                        onClick = { manualPicker.launch(arrayOf("*/*")) },
                        enabled = !state.busy && state.profileId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.astrodx_manual_import_action))
                    }
                    state.error?.let { Text(stringResource(it), color = MiuixTheme.colorScheme.error) }
                    state.result?.let { result ->
                        Text(stringResource(if (result.found == 0) R.string.astrodx_no_scores else R.string.astrodx_complete),
                            color = MiuixTheme.colorScheme.primary)
                        Text(stringResource(R.string.astrodx_result_counts, result.found, result.matched, result.updated, result.skipped),
                            style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
        }
    }
}

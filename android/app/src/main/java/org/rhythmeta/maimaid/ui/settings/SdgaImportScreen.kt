package org.rhythmeta.maimaid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.RegionNames
import org.rhythmeta.maimaid.core.data.SdgaCredential
import org.rhythmeta.maimaid.core.data.SdgaCredentialParser
import org.rhythmeta.maimaid.core.nfc.NfcCardRead
import org.rhythmeta.maimaid.ui.components.PreferenceIcon
import org.rhythmeta.maimaid.ui.components.appTextFieldColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * International-server (`SDGA`) score import.
 *
 * The credential is a card identifier, not a QR code: `IDm [PMm]` for a FeliCa card or a
 * 20-digit `AccessCode [M1 UID]`. The country/arcade pickers choose which cabinet's keychip
 * performs the login; leaving them on automatic rotates through the whole catalog.
 */
@Composable
fun SdgaImportScreen(
    container: AppContainer,
    contentTopPadding: Dp,
) {
    val viewModel = viewModel<SdgaImportViewModel>(
        key = "sdga-import",
        factory = SdgaImportViewModel.Factory(container),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }

    val context = LocalContext.current
    var nfcListening by remember { mutableStateOf(false) }
    var nfcStatus by remember { mutableStateOf<NfcScanStatus>(NfcScanStatus.Idle) }
    val nfcScanner = rememberNfcCardScanner { result ->
        when (result) {
            is NfcCardRead.Credential -> {
                viewModel.setCredential(result.card)
                viewModel.setCredentialExtra(result.extra.orEmpty())
                nfcStatus = NfcScanStatus.Done
            }
            is NfcCardRead.MifareUid -> {
                // The UID lands in the second field, so no extra caption is needed.
                viewModel.setCredentialExtra(result.uid)
                nfcStatus = NfcScanStatus.Done
            }
            NfcCardRead.Unsupported -> nfcStatus = NfcScanStatus.Failed
        }
        nfcListening = false
    }
    // Reader mode is tied to the foreground screen: leaving it must stop the radio and
    // reset the button, otherwise the next screen would show "stop reading" for nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                nfcScanner?.stop()
                nfcListening = false
                if (nfcStatus == NfcScanStatus.Listening) nfcStatus = NfcScanStatus.Idle
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toggleNfc() {
        val scanner = nfcScanner ?: return
        if (nfcListening) {
            scanner.stop()
            nfcListening = false
            nfcStatus = NfcScanStatus.Idle
            return
        }
        if (!scanner.supported) {
            nfcStatus = NfcScanStatus.Unsupported
            return
        }
        if (!scanner.enabled) {
            nfcStatus = NfcScanStatus.Disabled
            return
        }
        nfcListening = scanner.start()
        nfcStatus = if (nfcListening) NfcScanStatus.Listening else NfcScanStatus.Failed
    }

    ScoreImportPage(contentTopPadding = contentTopPadding) {
        item {
            ImportSummaryCard(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.import_sdga_summary_title),
                summary = stringResource(R.string.import_sdga_summary_description),
            )
        }
        item {
            ImportProfileSection(
                profile = state.profile,
                best50Rating = state.best50Rating,
                presetAvatarRepository = container.presetAvatarRepository,
            )
        }
        item {
            Column {
                Text(
                    text = stringResource(R.string.import_sdga_egress_section),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(14.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
                ) {
                    val egress = state.egress
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.refreshEgress() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = when {
                                    egress != null -> egress.address
                                    state.egressLoading -> stringResource(R.string.import_sdga_egress_loading)
                                    else -> stringResource(R.string.import_sdga_egress_failed)
                                },
                                style = MiuixTheme.textStyles.title3,
                            )
                            if (egress != null) {
                                val code = egress.countryCode
                                val region = if (code == null) {
                                    stringResource(R.string.import_sdga_egress_unknown)
                                } else {
                                    "${RegionNames.displayName(code)} ($code)"
                                }
                                // Region and carrier share one line: `美国 (US) · Cogent …`.
                                Text(
                                    text = listOfNotNull(
                                        region,
                                        egress.isp?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.import_sdga_egress_refresh),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.import_sdga_egress_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
                )
            }
        }
        if (state.countries.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = stringResource(R.string.import_sdga_region_section),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
                    ) {
                        val labels = listOf(stringResource(R.string.import_sdga_region_auto)) +
                            state.countries.map { RegionNames.displayName(it) }
                        WindowDropdownPreference(
                            startAction = { PreferenceIcon(Icons.Rounded.Language) },
                            title = stringResource(R.string.import_sdga_country_title),
                            items = labels,
                            selectedIndex = state.countries.indexOf(state.country) + 1,
                            onSelectedIndexChange = { index ->
                                viewModel.selectCountry(if (index == 0) null else state.countries[index - 1])
                            },
                            maxHeight = 420.dp,
                        )
                        if (state.places.isNotEmpty()) {
                            val placeLabels = listOf(stringResource(R.string.import_sdga_place_auto)) +
                                state.places.map { place -> place.displayName }
                            WindowDropdownPreference(
                                startAction = { PreferenceIcon(Icons.Rounded.Place) },
                                title = stringResource(R.string.import_sdga_place_title),
                                items = placeLabels,
                                selectedIndex = state.places.indexOfFirst { it.placeId == state.placeId } + 1,
                                onSelectedIndexChange = { index ->
                                    viewModel.selectPlace(if (index == 0) null else state.places[index - 1].placeId)
                                },
                                maxHeight = 420.dp,
                            )
                        }
                    }
                }
            }
        }
        state.saved?.let { saved ->
            item {
                ImportSection(stringResource(R.string.import_sdga_saved_section)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                saved.playerName.ifBlank {
                                    stringResource(R.string.import_sdga_saved_player)
                                },
                                style = MiuixTheme.textStyles.title3,
                            )
                            Text(
                                text = listOfNotNull(
                                    saved.masked,
                                    saved.extra.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    ImportPrimaryButton(
                        title = stringResource(R.string.import_sdga_quick_import),
                        icon = Icons.Rounded.Refresh,
                        busy = state.busy,
                        enabled = state.profile != null,
                        onClick = viewModel::quickImport,
                    )
                    Button(
                        onClick = viewModel::forgetCredential,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.errorContainer,
                            contentColor = MiuixTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_sdga_forget))
                    }
                }
            }
        }
        item {
            ImportSection(stringResource(R.string.import_sdga_input_section)) {
                TextField(
                    value = state.credential,
                    onValueChange = viewModel::setCredential,
                    colors = appTextFieldColors(),
                    label = stringResource(R.string.import_sdga_credential),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                )
                // The second field only makes sense with an IDm (PMm) or an access code
                // (M1 UID); its label follows whatever the first field looks like.
                val extraLabel = when (SdgaCredentialParser.parse(state.credential.trim())) {
                    is SdgaCredential.Idm -> stringResource(R.string.import_sdga_extra_pmm)
                    is SdgaCredential.AccessCode -> stringResource(R.string.import_sdga_extra_uid)
                    null -> stringResource(R.string.import_sdga_extra_generic)
                }
                TextField(
                    value = state.credentialExtra,
                    onValueChange = viewModel::setCredentialExtra,
                    colors = appTextFieldColors(),
                    label = extraLabel,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                )
                Button(
                    onClick = { toggleNfc() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nfcScanner?.supported == true,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Rounded.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (nfcListening) R.string.import_sdga_nfc_cancel
                            else R.string.import_sdga_nfc_read,
                        ),
                    )
                }
                val statusText = when (nfcStatus) {
                    NfcScanStatus.Idle, NfcScanStatus.Done -> null
                    NfcScanStatus.Listening -> stringResource(R.string.import_sdga_nfc_listening)
                    NfcScanStatus.Failed -> stringResource(R.string.import_sdga_nfc_failed)
                    NfcScanStatus.Unsupported -> stringResource(R.string.import_sdga_nfc_unsupported)
                    NfcScanStatus.Disabled -> stringResource(R.string.import_sdga_nfc_disabled)
                }
                if (statusText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusText,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        if (nfcStatus == NfcScanStatus.Disabled) {
                            Text(
                                text = stringResource(R.string.import_sdga_nfc_settings),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                                        }
                                    },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.import_sdga_credential_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                ImportPrimaryButton(
                    title = stringResource(R.string.import_sdga_import),
                    icon = Icons.Rounded.CloudDownload,
                    busy = state.busy,
                    enabled = state.canImport,
                    onClick = viewModel::startImport,
                )
            }
        }
        item { SdgaStatusCard(state) }
    }
}

@Composable
private fun SdgaStatusCard(state: SdgaImportUiState) {
    if (!state.busy && state.result == null) return
    val failed = state.result == ScoreImportResult.Failed
    val color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    val title = when {
        state.busy -> stringResource(R.string.import_status_fetching)
        state.result == ScoreImportResult.Imported -> stringResource(R.string.import_status_imported)
        state.result == ScoreImportResult.NoChanges -> stringResource(R.string.import_status_no_changes)
        else -> stringResource(R.string.import_status_failed)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    state.busy -> Icons.Rounded.Sync
                    failed -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.CheckCircle
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = color)
                if (state.fetchedCount > 0) {
                    Text(
                        stringResource(
                            R.string.import_status_counts,
                            state.fetchedCount,
                            state.updatedCount,
                            state.skippedCount,
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                state.details?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (state.busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Reader-mode state of the NFC scan button. */
private sealed interface NfcScanStatus {
    data object Idle : NfcScanStatus
    data object Listening : NfcScanStatus
    data object Done : NfcScanStatus
    data object Failed : NfcScanStatus
    data object Unsupported : NfcScanStatus
    data object Disabled : NfcScanStatus
}

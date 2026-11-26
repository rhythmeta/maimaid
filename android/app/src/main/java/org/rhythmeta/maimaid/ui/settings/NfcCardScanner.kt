package org.rhythmeta.maimaid.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import org.rhythmeta.maimaid.core.nfc.NfcCardCodec
import org.rhythmeta.maimaid.core.nfc.NfcCardRead

/**
 * Reader-mode NFC scanner for the import screen.
 *
 * Reader mode is used instead of the system tag dispatcher so the scan stays inside this
 * screen, keeps the activity in the foreground and returns raw tag handles (FeliCa IDm/PMm,
 * Mifare UID) without an NDEF intent round trip.
 */
internal class NfcCardScanner(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    var onResult: ((NfcCardRead) -> Unit)? = null
    private var listening = false

    val supported: Boolean get() = adapter != null

    val enabled: Boolean get() = adapter?.isEnabled == true

    /** @return false when NFC is missing, switched off, or reader mode could not start. */
    fun start(): Boolean {
        val nfc = adapter ?: return false
        if (!nfc.isEnabled) return false
        if (listening) return true
        return try {
            nfc.enableReaderMode(
                activity,
                { tag ->
                    val result = runCatching { readTag(tag) }.getOrDefault(NfcCardRead.Unsupported)
                    activity.runOnUiThread {
                        if (!listening) return@runOnUiThread
                        // Keep listening after an unusable card so the user can just try again.
                        if (result !is NfcCardRead.Unsupported) stop()
                        onResult?.invoke(result)
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_F,
                null,
            )
            listening = true
            true
        } catch (error: RuntimeException) {
            listening = false
            false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { adapter?.disableReaderMode(activity) }
    }

    private fun readTag(tag: Tag): NfcCardRead {
        NfcF.get(tag)?.let { felica ->
            return NfcCardCodec.decode(
                identifier = tag.id,
                isFelica = true,
                manufacturer = felica.manufacturer,
            )
        }
        val textPayloads = Ndef.get(tag)?.cachedNdefMessage?.records.orEmpty().mapNotNull { record ->
            record.payload.takeIf {
                record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)
            }
        }
        return NfcCardCodec.decode(
            identifier = tag.id,
            isFelica = false,
            isMifareClassic = MifareClassic.get(tag) != null,
            ndefTextPayloads = textPayloads,
        )
    }
}

/**
 * Returns a scanner bound to the hosting activity and stops reader mode when the screen
 * leaves composition, so a background screen never keeps the NFC radio busy.
 */
@Composable
internal fun rememberNfcCardScanner(onRead: (NfcCardRead) -> Unit): NfcCardScanner? {
    val activity = LocalContext.current.findActivity() ?: return null
    val scanner = remember(activity) { NfcCardScanner(activity) }
    val currentOnRead = rememberUpdatedState(onRead)
    SideEffect { scanner.onResult = { currentOnRead.value(it) } }
    DisposableEffect(scanner) { onDispose { scanner.stop() } }
    return scanner
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package org.rhythmeta.maimaid.core.nfc

/** What a single tag presentation produced. */
internal sealed interface NfcCardRead {
    /** The main field plus the optional second field (`PMm` or `M1 UID`). */
    data class Credential(val card: String, val extra: String?) : NfcCardRead

    /** A Mifare UID without a readable access code; the user still has to type the 20 digits. */
    data class MifareUid(val uid: String) : NfcCardRead

    data object Unsupported : NfcCardRead
}

/**
 * Turns the public fields of a discovered tag into an import credential.
 *
 * Nothing is sent to the card and no protected sector is read: a FeliCa tag publishes its
 * IDm (`tag.id`) and PMm (`NfcF.manufacturer`), and a Mifare tag publishes its 4-byte UID.
 * An access code only shows up when the card itself carries a plain NDEF text record.
 */
internal object NfcCardCodec {
    private val ACCESS_CODE = Regex("""(?:AccessCode\s*[:=]\s*)?([0-9]{20})""", RegexOption.IGNORE_CASE)
    private val ACCESS_CODE_ONLY = Regex("""[0-9]{20}""")

    fun decode(
        identifier: ByteArray,
        isFelica: Boolean,
        manufacturer: ByteArray? = null,
        isMifareClassic: Boolean = false,
        ndefTextPayloads: List<ByteArray> = emptyList(),
    ): NfcCardRead {
        if (isFelica) {
            if (identifier.size != 8) return NfcCardRead.Unsupported
            val idm = identifier.toUpperHex()
            val pmm = manufacturer?.takeIf { it.size == 8 }?.toUpperHex()?.takeIf { it.isNotBlank() }
            return NfcCardRead.Credential(idm, pmm)
        }
        val uid = identifier.takeIf { isMifareClassic && it.size == 4 }?.toUpperHex()
        val code = ndefTextPayloads.mapNotNull(::accessCodeFromTextRecord).distinct().singleOrNull()
        if (code != null) return NfcCardRead.Credential(code, uid)
        return uid?.let(NfcCardRead::MifareUid) ?: NfcCardRead.Unsupported
    }

    /** True when [value] is exactly a 20-digit access code, i.e. the case a scanned UID applies to. */
    fun isBareAccessCode(value: String): Boolean = ACCESS_CODE_ONLY.matches(value.trim())

    /** NDEF text record: status byte (UTF-16 flag in bit 7, language length in bits 0-5). */
    private fun accessCodeFromTextRecord(payload: ByteArray): String? {
        if (payload.size !in 2..128) return null
        val status = payload[0].toInt() and 0xFF
        if (status and 0x40 != 0) return null
        val textStart = 1 + (status and 0x3F)
        if (textStart >= payload.size) return null
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val text = String(payload, textStart, payload.size - textStart, charset)
        return ACCESS_CODE.matchEntire(text.trim())?.groupValues?.get(1)
    }

    private fun ByteArray.toUpperHex(): String = joinToString("") { "%02X".format(it) }
}

package org.rhythmeta.maimaid.core.data

/**
 * A login credential accepted by the international-server AiMeDB.
 *
 * The reference tool accepts two shapes, both with an optional second field:
 * `IDm [PMm]` for FeliCa cards and `AccessCode [M1 UID]` for Aime/Mifare cards. The
 * cleaned lengths never overlap, so one expression is enough to tell them apart.
 */
internal sealed interface SdgaCredential {
    /** FeliCa IDm, optionally with the card's PMm. */
    data class Idm(val idm: String, val pmm: String?) : SdgaCredential

    /** 20-digit access code, optionally with a Mifare Classic UID. */
    data class AccessCode(val code: String, val m1Uid: String?) : SdgaCredential

    /** The value the cache and the logs key on: the cleaned input, before defaulting. */
    val normalized: String
        get() = when (this) {
            is Idm -> idm + (pmm.orEmpty())
            is AccessCode -> code + (m1Uid.orEmpty())
        }
}

internal object SdgaCredentialParser {
    /** Separators the reference parser strips before matching. */
    private val SEPARATORS = Regex("[\\s:,;/|\\-]+")

    private val IDM = Regex("([0-9A-Fa-f]{16})([0-9A-Fa-f]{16})?")
    private val ACCESS_CODE = Regex("([0-9]{20})([0-9A-Fa-f]{8})?")

    /** The PMm every `012E…` Aime card reports; other FeliCa media must supply their own. */
    const val DEFAULT_PMM = "00F1000000014300"

    /** Prefix of a physical Aime card, the only IDm that may omit the PMm. */
    private const val AIME_CARD_IDM_PREFIX = "012E"

    fun clean(value: String): String = SEPARATORS.replace(value, "")

    fun parse(raw: String): SdgaCredential? {
        val text = clean(raw)
        IDM.matchEntire(text)?.let { match ->
            val idm = match.groupValues[1].uppercase()
            val pmm = match.groupValues[2].takeIf { it.isNotEmpty() }?.uppercase()
            return SdgaCredential.Idm(idm, pmm)
        }
        ACCESS_CODE.matchEntire(text)?.let { match ->
            val code = match.groupValues[1]
            val uid = match.groupValues[2].takeIf { it.isNotEmpty() }?.uppercase()
            return SdgaCredential.AccessCode(code, uid)
        }
        return null
    }

    /** Non-`012E` FeliCa media each carry their own PMm, so a bare IDm is not enough. */
    fun needsPmm(idm: String): Boolean = !clean(idm).uppercase().startsWith(AIME_CARD_IDM_PREFIX)

    /**
     * The PMm actually sent for [credential].
     *
     * @throws DirectImportException `sdga_pmm_required` when a non-Aime IDm has no PMm.
     */
    fun pmmFor(credential: SdgaCredential.Idm): String {
        credential.pmm?.let { return it }
        if (needsPmm(credential.idm)) throw DirectImportException("sdga_pmm_required")
        return DEFAULT_PMM
    }
}

package org.rhythmeta.maimaid.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Imports international-server (`SDGA`) scores from a card credential.
 *
 * The pipeline is: credential → AiMeDB (card → account) → title-server login → paginated
 * `GetUserMusicApi` → the shared score import. The last step is the same code the China
 * cabinet import uses: the response is `userMusicList[].userMusicDetailList[]`, so decoding
 * and applying are region-independent.
 *
 * Unlike the China flow there is no one-time QR code: the credential can be resolved again,
 * which makes a cabinet retry safe while the account lookup is the only thing repeating.
 */
class SdgaImportService(
    private val config: SdgaConfig,
    private val catalog: SdgaPlacesCatalog,
    private val store: SdgaPlaceStore,
    private val importer: ThirdPartyImportService,
    private val credentialStore: SdgaCredentialStore? = null,
    private val json: Json,
    private val http: OkHttpClient = SdgaGameClient.defaultHttpClient(),
) {
    val isConfigured: Boolean get() = config.isComplete


    val countries: List<String> get() = catalog.countries

    val selectedCountry: String? get() = store.country()

    val selectedPlaceId: Int? get() = store.placeId()


    fun places(country: String?): List<SdgaPlace> = catalog.placesIn(country).sortedBy { it.displayName }

    fun selectCountry(country: String?) {
        store.setCountry(country)
        // A place of the previous country is meaningless in the new pool.
        store.setPlaceId(null)
    }

    fun selectPlace(placeId: Int?) {
        store.setPlaceId(placeId)
    }

    /** The credential remembered for this profile, if any. */
    fun savedCredential(profileId: String): SdgaSavedCredential? = credentialStore?.load(profileId)

    fun forgetCredential(profileId: String) {
        credentialStore?.clear(profileId)
    }

    /**
     * One-tap re-import with the credential remembered from the last success. The card
     * number is fixed, so nothing else has to be typed again.
     */
    suspend fun importSaved(profileId: String): LocalScoreImportResult {
        val saved = savedCredential(profileId)
            ?: throw DirectImportException("sdga_no_saved_credential")
        val raw = if (saved.extra.isBlank()) saved.card else "${saved.card} ${saved.extra}"
        return import(raw, profileId)
    }

    suspend fun import(rawCredential: String, profileId: String): LocalScoreImportResult =
        withContext(Dispatchers.IO) {
            if (!config.isComplete) throw DirectImportException("sdga_not_configured")
            val credential = SdgaCredentialParser.parse(rawCredential)
                ?: throw DirectImportException("sdga_invalid_credential")
            // Validate the PMm before opening a socket so a malformed credential cannot
            // consume a cabinet attempt.
            if (credential is SdgaCredential.Idm) SdgaCredentialParser.pmmFor(credential)

            val fallback = if (config.keychip.isNotBlank() && config.placeId > 0 && config.regionId > 0) {
                SdgaCabinet(
                    keychip = config.keychip.filter { it.isLetterOrDigit() },
                    placeId = config.placeId,
                    regionId = config.regionId,
                )
            } else {
                null
            }
            val attempts = SdgaCabinetSelector.plan(
                catalog = catalog,
                country = store.country(),
                placeId = store.placeId(),
                cursor = store.cursor(),
                fallback = fallback,
            )
            if (attempts.isEmpty()) throw DirectImportException("sdga_no_cabinet")

            val aime = AimeDbClient(
                host = config.aimeHost,
                port = config.aimePort,
                commonKey = config.commonKey,
                gameId = config.gameId,
            )
            val game = SdgaGameClient(config, json, http)
            var attempted = 0
            var loginRejections = 0
            var lastError: DirectImportException? = null
            for (cabinet in attempts) {
                currentCoroutineContext().ensureActive()
                attempted++
                // Some catalogued keychips are no longer answered by the server. That is
                // transport-shaped, so try the next cabinet instead of failing outright,
                // but keep it short: every dead keychip costs one timeout.
                val account = try {
                    aime.resolve(credential, cabinet)
                } catch (error: DirectImportException) {
                    val transport = error.code == "sdga_network" || error.code == "sdga_no_response"
                    if (!transport || attempted >= TRANSPORT_ATTEMPT_LIMIT) throw error
                    lastError = error
                    continue
                }
                store.advanceCursor(attempted)
                val fetched = try {
                    game.loginAndFetchMusic(account, cabinet)
                } catch (error: DirectImportException) {
                    // A rejected login can be a cabinet-side problem (a blocked clientId),
                    // so try exactly one more cabinet — but never loop on the player's
                    // account, which each rejected login counts against.
                    val retryable = error.code.startsWith("game_login_") && loginRejections == 0
                    if (!retryable) throw error
                    loginRejections++
                    lastError = error
                    continue
                }
                // The profile name is user-chosen; a cabinet snapshot must not rename it.
                val payload = ThirdPartyImportPolicy.decodeSgwcmaid(fetched.music)
                currentCoroutineContext().ensureActive()
                val applied = importer.apply(payload, ScoreImportProvider.Sgwcmaid, profileId)
                rememberCredential(profileId, rawCredential, fetched.playerName)
                return@withContext applied
            }
            throw lastError ?: DirectImportException("sdga_no_cabinet")
        }

    /** Stores the credential only after a successful import, never on a failed attempt. */
    private suspend fun rememberCredential(profileId: String, raw: String, playerName: String) {
        val store = credentialStore ?: return
        val parts = raw.trim().split(WHITESPACE, limit = 2)
        val card = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                store.save(
                    profileId,
                    SdgaSavedCredential(card, parts.getOrElse(1) { "" }, playerName),
                )
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Cabinets tried when the server does not answer at all. */
        const val TRANSPORT_ATTEMPT_LIMIT = 2
    }
}

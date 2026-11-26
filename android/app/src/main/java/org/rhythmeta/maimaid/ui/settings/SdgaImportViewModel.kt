package org.rhythmeta.maimaid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.DirectImportException
import org.rhythmeta.maimaid.core.data.EgressIp
import org.rhythmeta.maimaid.core.data.LocalScoreImportResult
import org.rhythmeta.maimaid.core.data.SdgaSavedCredential
import org.rhythmeta.maimaid.core.data.SdgaPlace
import org.rhythmeta.maimaid.core.database.UserProfileEntity

data class SdgaImportUiState(
    val profile: UserProfileEntity? = null,
    /** Rating computed from the profile's scores, like the other import screens. */
    val best50Rating: Int = 0,
    val credential: String = "",
    val countries: List<String> = emptyList(),
    val country: String? = null,
    val places: List<SdgaPlace> = emptyList(),
    val placeId: Int? = null,
    val configured: Boolean = false,
    /** Second field: PMm for an IDm, M1 UID for an access code. */
    val credentialExtra: String = "",
    /** Credential remembered from the last successful import, for one-tap re-import. */
    val saved: SdgaSavedCredential? = null,
    /** Public exit address the title server will see, when it could be resolved. */
    val egress: EgressIp? = null,
    val egressLoading: Boolean = false,
    val egressFailed: Boolean = false,
    val busy: Boolean = false,
    val result: ScoreImportResult? = null,
    val details: String? = null,
    val fetchedCount: Int = 0,
    val updatedCount: Int = 0,
    val skippedCount: Int = 0,
) {
    val canImport: Boolean get() = !busy && profile != null && credential.isNotBlank() && configured
}

/**
 * Drives the international-server import: the credential field, the country/arcade pickers
 * and the one-shot import.
 *
 * The screen keeps the credential only in memory; it is never persisted, because it is a
 * card credential rather than a session token.
 */
class SdgaImportViewModel(private val container: AppContainer) : ViewModel() {
    private val service = container.sdgaImportService
    private val mutableState = MutableStateFlow(
        SdgaImportUiState(
            countries = service.countries,
            country = service.selectedCountry,
            places = service.places(service.selectedCountry),
            placeId = service.selectedPlaceId,
            configured = service.isConfigured,
        ),
    )
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0

    init {
        refreshEgress(force = false)
        viewModelScope.launch {
            container.best50Repository.observeBest50(b35CountOverride = 35, b15CountOverride = 15)
                .collect { best50 -> mutableState.update { it.copy(best50Rating = best50.total) } }
        }
        viewModelScope.launch {
            container.profileRepository.activeProfile.collect { profile ->
                if (profile?.id != mutableState.value.profile?.id) {
                    cancel()
                    mutableState.update {
                        it.copy(
                            profile = profile,
                            saved = profile?.let { service.savedCredential(it.id) },
                            result = null,
                            details = null,
                            fetchedCount = 0,
                            updatedCount = 0,
                            skippedCount = 0,
                        )
                    }
                } else {
                    mutableState.update { it.copy(profile = profile) }
                }
            }
        }
    }

    fun setCredential(value: String) {
        // Typing a whole "IDm PMm" into the first field still works; it is split when the
        // import runs, so the two-field layout never blocks a paste from elsewhere.
        mutableState.update { it.copy(credential = value, result = null, details = null) }
    }

    fun setCredentialExtra(value: String) {
        mutableState.update { it.copy(credentialExtra = value, result = null, details = null) }
    }

    fun clearCredentialExtra() {
        mutableState.update { it.copy(credentialExtra = "") }
    }

    fun selectCountry(country: String?) {
        service.selectCountry(country)
        mutableState.update {
            it.copy(country = country, places = service.places(country), placeId = null, result = null, details = null)
        }
    }

    fun selectPlace(placeId: Int?) {
        service.selectPlace(placeId)
        mutableState.update { it.copy(placeId = placeId, result = null, details = null) }
    }

    fun startImport() {
        val credential = credentialRequestValue(
            mutableState.value.credential,
            mutableState.value.credentialExtra,
        )
        if (credential.isEmpty()) return
        launchImport { profileId -> service.import(credential, profileId) }
    }

    /** Re-imports with the remembered card credential; nothing has to be typed. */
    fun quickImport() {
        if (mutableState.value.saved == null) return
        launchImport { profileId -> service.importSaved(profileId) }
    }

    fun forgetCredential() {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.busy) return
        service.forgetCredential(profile.id)
        mutableState.update { it.copy(saved = null, result = null, details = null) }
    }

    private fun launchImport(work: suspend (String) -> LocalScoreImportResult) {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.busy) return
        mutableState.update {
            it.copy(busy = true, result = null, details = null, fetchedCount = 0, updatedCount = 0, skippedCount = 0)
        }
        val currentGeneration = ++generation
        operation = viewModelScope.launch {
            try {
                val outcome = work(profile.id)
                if (generation != currentGeneration) return@launch
                mutableState.update {
                    it.copy(
                        credentialExtra = "",
                        saved = service.savedCredential(profile.id),
                        fetchedCount = outcome.fetchedCount,
                        updatedCount = outcome.updatedCount,
                        skippedCount = outcome.skippedCount,
                        result = if (outcome.updatedCount > 0) ScoreImportResult.Imported else ScoreImportResult.NoChanges,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkageError) {
                // A missing platform API must degrade to a failed import, not kill the process.
                android.util.Log.e("SdgaImport", "import aborted by a missing platform API", error)
                if (generation == currentGeneration) {
                    mutableState.update { it.copy(result = ScoreImportResult.Failed, details = error.message) }
                }
            } catch (error: Exception) {
                android.util.Log.e("SdgaImport", "import failed: ${error.message}", error)
                if (generation == currentGeneration) {
                    mutableState.update {
                        it.copy(result = ScoreImportResult.Failed, details = messageFor(error))
                    }
                }
            } finally {
                if (generation == currentGeneration) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Re-reads the exit address. The value is cached for a minute, so a manual refresh is
     * how the user checks the result of switching the VPN node.
     */
    fun refreshEgress(force: Boolean = true) {
        if (mutableState.value.egressLoading) return
        mutableState.update { it.copy(egressLoading = true, egressFailed = false) }
        viewModelScope.launch {
            val info = runCatching { container.egressIpService.resolve(force) }.getOrNull()
            mutableState.update {
                it.copy(egressLoading = false, egress = info, egressFailed = info == null)
            }
        }
    }

    fun dismissResult() {
        mutableState.update { it.copy(result = null, details = null) }
    }

    fun cancel() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.update {
            it.copy(busy = false, result = null, details = null, fetchedCount = 0, updatedCount = 0, skippedCount = 0)
        }
    }

    /**
     * Joins the two input fields into the `IDm [PMm]` / `AccessCode [M1 UID]` form the
     * protocol expects. A pasted combined value in the first field is passed through.
     */
    private fun credentialRequestValue(card: String, extra: String): String {
        val trimmedCard = card.trim()
        val trimmedExtra = extra.trim()
        if (trimmedCard.isEmpty()) return ""
        if (trimmedCard.contains(' ')) return trimmedCard
        return if (trimmedExtra.isEmpty()) trimmedCard else "$trimmedCard $trimmedExtra"
    }

    /**
     * The protocol speaks in opaque codes; the UI owns their wording so localized builds
     * never surface a raw identifier.
     */
    private fun messageFor(error: Throwable): String? {
        val context = container.applicationContext
        val code = (error as? DirectImportException)?.code ?: error.message ?: return null
        return when {
            code == "sdga_not_configured" -> context.getString(R.string.import_sdga_error_not_configured)
            code == "sdga_invalid_credential" -> context.getString(R.string.import_sdga_error_invalid_credential)
            code == "sdga_pmm_required" -> context.getString(R.string.import_sdga_error_pmm_required)
            code == "sdga_no_access_code" -> context.getString(R.string.import_sdga_error_no_access_code)
            code == "sdga_no_account_no_uid" -> context.getString(R.string.import_sdga_error_no_account_no_uid)
            code == "sdga_no_account" -> context.getString(R.string.import_sdga_error_no_account)
            code == "sdga_locked" -> context.getString(R.string.import_sdga_error_locked)
            code == "game_network" -> context.getString(R.string.import_sdga_error_game_network)
            code == "sdga_no_response" -> context.getString(R.string.import_sdga_error_no_response)
            code == "sdga_network" || code == "sdga_bad_response" ->
                context.getString(R.string.import_sdga_error_network)
            code == "game_empty_response" || code.startsWith("game_empty_text_") ->
                context.getString(R.string.import_sdga_error_empty_response)
            code == "sdga_no_cabinet" -> context.getString(R.string.import_sdga_error_no_cabinet)
            code == "game_account_already_logged_in" || code == "game_login_100" ->
                context.getString(R.string.import_status_game_login_busy)
            code.startsWith("game_login_") -> context.getString(
                R.string.import_sdga_error_login_failed,
                code.removePrefix("game_login_"),
            )
            else -> code
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SdgaImportViewModel(container) as T
    }
}

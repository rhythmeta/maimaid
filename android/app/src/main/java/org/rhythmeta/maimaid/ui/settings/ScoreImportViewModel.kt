package org.rhythmeta.maimaid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.*
import org.rhythmeta.maimaid.core.database.UserProfileEntity

enum class ScoreImportPhase {
    Idle,
    CheckingSession,
    Connecting,
    RefreshingToken,
    ExchangingToken,
    Fetching,
    CheckingConflicts,
    Applying,
}

enum class ScoreImportResult {
    Imported,
    NoChanges,
    LoginRequired,
    TokenExpired,
    Failed,
}

data class ScoreImportUiState(
    val profile: UserProfileEntity? = null,
    val divingFishConnected: Boolean = false,
    val divingFishCanWrite: Boolean = false,
    val userCode: String = "",
    val divingFishUsername: String? = null,
    val lxnsRefreshToken: String = "",
    val lxnsAuthorizationCode: String = "",
    val phase: ScoreImportPhase = ScoreImportPhase.Idle,
    val result: ScoreImportResult? = null,
    val resultDetails: String? = null,
    val fetchedCount: Int = 0,
    val upsertedCount: Int = 0,
    val skippedCount: Int = 0,
) {
    val isBusy: Boolean get() = phase != ScoreImportPhase.Idle
    val hasDivingFishAccount: Boolean get() = divingFishConnected
    val hasLxnsAccount: Boolean get() = lxnsRefreshToken.isNotBlank()
}

class ScoreImportViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(ScoreImportUiState())
    val state = mutableState.asStateFlow()
    private val service = container.thirdPartyImportService
    private var verifier = ""
    private var authorizationProfileId: String? = null
    private var operation: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            container.profileRepository.activeProfile.collect { profile ->
                if (profile?.id != mutableState.value.profile?.id) {
                    cancel()
                    verifier = ""
                    authorizationProfileId = null
                    mutableState.value = ScoreImportUiState(profile = profile)
                } else {
                    mutableState.update { it.copy(profile = profile) }
                }
                refreshBindings()
            }
        }
    }

    private fun refreshBindings() {
        val id = mutableState.value.profile?.id ?: return
        mutableState.update { it.copy(
            divingFishConnected = service.refreshToken(ScoreImportProvider.DivingFish, id).isNotEmpty(),
            lxnsRefreshToken = service.refreshToken(ScoreImportProvider.Lxns, id)) }
    }

    fun setLxnsAuthorizationCode(value: String) { mutableState.update { it.copy(lxnsAuthorizationCode = value) } }

    fun createLxnsAuthorizationUrl(): String {
        val auth = service.createLxnsAuthorization()
        verifier = auth.verifier
        authorizationProfileId = mutableState.value.profile?.id
        return auth.url
    }

    fun authorizeAndImportDivingFish(openAuthorization: (String) -> Boolean) = perform { profile ->
        val device = service.startDivingFish()
        mutableState.update { it.copy(userCode = device.userCode) }
        check(openAuthorization(device.url)) { "browser_unavailable" }
        service.finishDivingFish(device, profile.id)
        import(profile.id, ScoreImportProvider.DivingFish)
    }

    fun quickImportDivingFish() = perform { import(it.id, ScoreImportProvider.DivingFish) }
    fun quickImportLxns() = perform { import(it.id, ScoreImportProvider.Lxns) }

    fun exchangeLxnsCodeAndImport() {
        val code = mutableState.value.lxnsAuthorizationCode
        val codeVerifier = verifier
        val id = authorizationProfileId
        perform { profile ->
            check(profile.id == id && codeVerifier.isNotBlank()) { "missing_pkce" }
            service.exchangeLxns(code, codeVerifier, profile.id)
            verifier = ""
            mutableState.update { it.copy(lxnsAuthorizationCode = "") }
            import(profile.id, ScoreImportProvider.Lxns)
        }
    }

    private suspend fun import(id: String, provider: ScoreImportProvider) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(phase = ScoreImportPhase.Fetching) }
        val result = service.importScores(provider, id)
        mutableState.update { it.copy(fetchedCount = result.fetchedCount, upsertedCount = result.updatedCount,
            skippedCount = result.skippedCount, result = if (result.updatedCount > 0) ScoreImportResult.Imported else ScoreImportResult.NoChanges) }
    }

    private fun perform(body: suspend (UserProfileEntity) -> Unit) {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.isBusy) return
        mutableState.update { it.copy(phase = ScoreImportPhase.Connecting, result = null, resultDetails = null,
            fetchedCount = 0, upsertedCount = 0, skippedCount = 0) }
        val currentGeneration = ++generation
        operation = viewModelScope.launch {
            try { body(profile) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == currentGeneration && mutableState.value.profile?.id == profile.id) {
                    mutableState.update { it.copy(result = if (error is DirectImportException &&
                        error.code in listOf("invalid_grant", "unauthorized")) ScoreImportResult.TokenExpired else ScoreImportResult.Failed,
                        resultDetails = error.message) }
                }
            } finally {
                if (generation == currentGeneration && mutableState.value.profile?.id == profile.id) {
                    mutableState.update { it.copy(phase = ScoreImportPhase.Idle, userCode = "") }
                    refreshBindings()
                }
            }
        }
    }

    fun cancel() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.update {
            it.copy(
                phase = ScoreImportPhase.Idle,
                userCode = "",
                result = null,
                resultDetails = null,
                fetchedCount = 0,
                upsertedCount = 0,
                skippedCount = 0,
            )
        }
    }
    fun disconnectDivingFish() = disconnect(ScoreImportProvider.DivingFish)
    fun disconnectLxns() = disconnect(ScoreImportProvider.Lxns)
    private fun disconnect(provider: ScoreImportProvider) {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.isBusy) return
        try { service.disconnect(provider, profile.id); refreshBindings() }
        catch (error: Exception) { mutableState.update { it.copy(result = ScoreImportResult.Failed, resultDetails = error.message) } }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScoreImportViewModel(container) as T
    }
}

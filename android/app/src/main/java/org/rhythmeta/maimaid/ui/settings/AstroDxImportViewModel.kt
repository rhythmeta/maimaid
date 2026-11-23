package org.rhythmeta.maimaid.ui.settings

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.astrodx.AstroDxCacheReader
import org.rhythmeta.maimaid.core.astrodx.AstroDxImportResult
import org.rhythmeta.maimaid.core.astrodx.AstroDxImportService
import org.rhythmeta.maimaid.core.astrodx.AstroDxCacheCodec
import org.rhythmeta.maimaid.core.database.UserProfileEntity

data class AstroDxImportUiState(
    val profile: UserProfileEntity? = null,
    val best50Rating: Int = 0,
    val profileId: String? = null,
    val profileName: String? = null,
    val authorized: Boolean = false,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val result: AstroDxImportResult? = null,
    val error: Int? = null,
)

class AstroDxImportViewModel(private val container: AppContainer) : ViewModel() {
    private val reader = AstroDxCacheReader()
    private val mutableState = MutableStateFlow(AstroDxImportUiState())
    val state = mutableState.asStateFlow()

    init {
        refreshAuthorization()
        viewModelScope.launch {
            container.best50Repository.observeBest50(b35CountOverride = 35, b15CountOverride = 15).collect { best50 ->
                mutableState.update { it.copy(best50Rating = best50.total) }
            }
        }
        viewModelScope.launch {
            container.profileRepository.activeProfile.collect { profile ->
                mutableState.update { current ->
                    current.copy(profile = profile, profileId = profile?.id, profileName = profile?.name,
                        result = current.result.takeIf { current.profileId == profile?.id },
                        error = current.error.takeIf { current.profileId == profile?.id })
                }
            }
        }
    }

    fun authorize() {
        if (state.value.busy || state.value.authorized) return
        mutableState.update { it.copy(busy = true, importing = false, result = null, error = null) }
        viewModelScope.launch {
            try {
                reader.authorize()
                mutableState.update { it.copy(authorized = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(error = errorResource(error)) }
            } finally {
                mutableState.update { it.copy(busy = false, importing = false) }
            }
        }
    }

    fun import() {
        if (!state.value.authorized) return
        startImport { reader.readAuthorized() }
    }

    fun importManual(uri: Uri, resolver: ContentResolver) {
        if (state.value.busy || state.value.profileId == null) return
        startImport {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw IOException("cache_unreadable")
                input.use { AstroDxCacheCodec.readLimited(it, AstroDxCacheCodec.MAX_INPUT_BYTES) }
            }
        }
    }

    private fun startImport(readCache: suspend () -> ByteArray) {
        val profileId = state.value.profileId ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, importing = true, result = null, error = null) }
        viewModelScope.launch {
            try {
                val bytes = readCache()
                val result = AstroDxImportService(container.database).import(bytes, profileId)
                if (result.updated > 0) container.widgetUpdateCoordinator.requestUpdate()
                mutableState.update { it.copy(result = result) }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(error = R.string.astrodx_error_timeout) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(error = errorResource(error)) }
            } finally {
                mutableState.update { it.copy(busy = false, importing = false) }
            }
        }
    }

    private fun refreshAuthorization() {
        mutableState.update { it.copy(authorized = reader.isAuthorized()) }
    }

    private fun errorResource(error: Throwable): Int {
        val diagnostic = buildString {
            var cause: Throwable? = error
            while (cause != null) {
                cause.message?.let { append(' ').append(it) }
                cause = cause.cause
            }
        }
        return when {
            "shizuku_unavailable" in diagnostic -> R.string.astrodx_error_shizuku
            "shizuku_denied" in diagnostic -> R.string.astrodx_error_denied
            "cache_missing" in diagnostic -> R.string.astrodx_error_missing
            "cache_unreadable" in diagnostic -> R.string.astrodx_error_read
            "cache_too_large" in diagnostic -> R.string.astrodx_error_size
            "cache_version" in diagnostic -> R.string.astrodx_error_version
            "profile_changed" in diagnostic -> R.string.astrodx_error_profile
            "catalog_empty" in diagnostic -> R.string.astrodx_error_catalog
            else -> R.string.astrodx_error_invalid
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AstroDxImportViewModel::class.java))
            return AstroDxImportViewModel(container) as T
        }
    }
}

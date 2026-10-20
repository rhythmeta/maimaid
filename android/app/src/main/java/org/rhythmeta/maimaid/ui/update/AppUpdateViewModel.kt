package org.rhythmeta.maimaid.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import org.rhythmeta.maimaid.core.update.AppUpdatePhase
import org.rhythmeta.maimaid.core.update.AppUpdateRepository
import java.io.File

class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    val state = repository.state
    private val requested = MutableStateFlow(false)
    val dialogRequested = requested.asStateFlow()
    private val installRequest = MutableStateFlow(false)
    val shouldInstall = installRequest.asStateFlow()
    private var foreground = false
    private var checkGeneration = 0

    init {
        viewModelScope.launch {
            state.collect { state ->
                if (state.phase == AppUpdatePhase.Ready) {
                    if (!foreground) installRequest.value = false
                    requested.value = true
                }
            }
        }
    }

    fun check(automatic: Boolean = false) {
        val generation = ++checkGeneration
        if (!automatic) requested.value = true
        viewModelScope.launch {
            val checked = repository.check(automatic)
            if (generation == checkGeneration && (!automatic || checked) && state.value.manifest != null) requested.value = true
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) installRequest.value = false
    }

    fun dismiss() { checkGeneration++; requested.value = false; installRequest.value = false }

    fun download() {
        installRequest.value = foreground
        viewModelScope.launch { repository.download() }
    }

    fun cancel() {
        installRequest.value = false
        viewModelScope.launch { repository.cancel() }
    }

    fun consumeInstallRequest() { installRequest.value = false }

    fun install(onReady: (File) -> Unit) {
        consumeInstallRequest()
        viewModelScope.launch {
            try {
                onReady(repository.verifiedApk())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (state.value.phase == AppUpdatePhase.Ready) repository.installFailed()
            }
        }
    }

    fun installationUnavailable() { repository.installFailed() }

    class Factory(private val repository: AppUpdateRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppUpdateViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AppUpdateViewModel(repository) as T
        }
    }
}

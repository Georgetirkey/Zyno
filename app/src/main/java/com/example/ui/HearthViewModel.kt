package com.example.ui

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.lifecycle.ViewModel
import com.example.permissions.PermissionsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FileChooserRequest(
    val callback: ValueCallback<Array<Uri>>,
    val acceptTypes: List<String>,
    val allowMultiple: Boolean
)

data class HearthUiState(
    val homeUrl: String = DEFAULT_HEARTH_URL,
    val currentUrl: String = DEFAULT_HEARTH_URL,
    val isInitialLoading: Boolean = true,
    val hasError: Boolean = false,
    val errorDescription: String? = null,
    val canGoBack: Boolean = false,
    val permissionsStatus: PermissionsStatus = PermissionsStatus(),
    val showPermissionsSheet: Boolean = false,
    val permissionsBannerDismissed: Boolean = false,
    val hasPromptedInitialPermissions: Boolean = false,
    val showMediaChooserSheet: Boolean = false,
    val pendingFileChooser: FileChooserRequest? = null,
    val pendingWebPermissionRequest: PermissionRequest? = null,
    val reloadNonce: Int = 0
) {
    companion object {
        const val DEFAULT_HEARTH_URL = "https://hearth-voice-talk.lovable.app"
    }
}

class HearthViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HearthUiState())
    val uiState: StateFlow<HearthUiState> = _uiState.asStateFlow()

    fun onPageStarted(url: String?) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                hasError = false,
                errorDescription = null
            )
        }
    }

    fun onPageCommitVisible() {
        _uiState.update { it.copy(isInitialLoading = false) }
    }

    fun onPageFinished(url: String?, canGoBack: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                isInitialLoading = false,
                canGoBack = canGoBack
            )
        }
    }

    fun onVisitedHistoryUpdated(url: String?, canGoBack: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                canGoBack = canGoBack
            )
        }
    }

    fun onNavigationStateChanged(canGoBack: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack) }
    }

    fun onPageError(description: String?) {
        _uiState.update {
            it.copy(
                isInitialLoading = false,
                hasError = true,
                errorDescription = description
            )
        }
    }

    fun triggerReload() {
        _uiState.update {
            it.copy(
                hasError = false,
                errorDescription = null,
                reloadNonce = it.reloadNonce + 1
            )
        }
    }

    fun openDeepLink(url: String) {
        _uiState.update {
            it.copy(
                homeUrl = url,
                currentUrl = url,
                hasError = false,
                errorDescription = null
            )
        }
    }

    fun markInitialPermissionsPrompted() {
        _uiState.update { it.copy(hasPromptedInitialPermissions = true) }
    }

    fun updatePermissionsStatus(status: PermissionsStatus) {
        _uiState.update { it.copy(permissionsStatus = status) }
    }

    fun setPermissionsSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showPermissionsSheet = visible) }
    }

    fun dismissPermissionsBanner() {
        _uiState.update { it.copy(permissionsBannerDismissed = true) }
    }

    fun setPendingFileChooser(request: FileChooserRequest?) {
        _uiState.update { state ->
            state.pendingFileChooser?.callback?.onReceiveValue(null)
            state.copy(
                pendingFileChooser = request,
                showMediaChooserSheet = request != null
            )
        }
    }

    fun dismissMediaChooserSheetOnly() {
        _uiState.update { it.copy(showMediaChooserSheet = false) }
    }

    fun deliverFileChooserResult(uris: Array<Uri>?) {
        val current = _uiState.value.pendingFileChooser
        _uiState.update {
            it.copy(
                pendingFileChooser = null,
                showMediaChooserSheet = false
            )
        }
        current?.callback?.onReceiveValue(uris)
    }

    fun setPendingWebPermissionRequest(request: PermissionRequest?) {
        _uiState.update { it.copy(pendingWebPermissionRequest = request) }
    }
}

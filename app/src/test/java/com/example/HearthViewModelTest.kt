package com.example

import com.example.permissions.PermissionsStatus
import com.example.ui.HearthUiState
import com.example.ui.HearthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HearthViewModelTest {

    @Test
    fun navigationAndBackHistoryTrackRecentPages() {
        val viewModel = HearthViewModel()
        assertEquals(HearthUiState.DEFAULT_HEARTH_URL, viewModel.uiState.value.homeUrl)
        assertFalse(viewModel.uiState.value.canGoBack)

        viewModel.onVisitedHistoryUpdated("https://hearth-voice-talk.lovable.app/rooms", canGoBack = true)
        assertEquals("https://hearth-voice-talk.lovable.app/rooms", viewModel.uiState.value.currentUrl)
        assertTrue(viewModel.uiState.value.canGoBack)

        viewModel.onNavigationStateChanged(false)
        assertFalse(viewModel.uiState.value.canGoBack)
    }

    @Test
    fun permissionsAndDeepLinkUpdateStateCleanly() {
        val viewModel = HearthViewModel()
        viewModel.openDeepLink("https://hearth-voice-talk.lovable.app/post/123")
        assertEquals("https://hearth-voice-talk.lovable.app/post/123", viewModel.uiState.value.homeUrl)

        val grantedStatus = PermissionsStatus(
            microphoneGranted = true,
            cameraGranted = true,
            notificationsGranted = true,
            bluetoothGranted = true
        )
        viewModel.updatePermissionsStatus(grantedStatus)
        assertTrue(viewModel.uiState.value.permissionsStatus.allGranted)
    }
}

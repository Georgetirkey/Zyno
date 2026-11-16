package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.VoiceAudioController
import com.example.permissions.HearthPermissions
import com.example.ui.HearthViewModel
import com.example.ui.components.HearthWebViewContainer
import com.example.ui.components.PermissionsBottomSheet
import com.example.ui.components.PostMediaChooserSheet
import com.example.ui.theme.HearthVoiceTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: HearthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)

        setContent {
            HearthVoiceTheme {
                HearthVoiceApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "https" && data.host == "hearth-voice-talk.lovable.app") {
            viewModel.openDeepLink(data.toString())
        }
    }
}

@Composable
fun HearthVoiceApp(viewModel: HearthViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val audioController = remember { VoiceAudioController(context) }
    var pendingCameraCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var launchCameraAfterPermission by remember { mutableStateOf(false) }

    // Sync permission status whenever activity resumes (e.g., returning from App Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updatePermissionsStatus(HearthPermissions.checkStatus(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            audioController.release()
        }
    }

    // Configure voice communication audio focus once microphone is available
    LaunchedEffect(uiState.permissionsStatus.microphoneGranted) {
        if (uiState.permissionsStatus.microphoneGranted) {
            audioController.setSpeakerphoneEnabled(true)
        }
    }

    // Camera photo capture launcher (for posts)
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraCaptureUri
        pendingCameraCaptureUri = null
        if (success && capturedUri != null) {
            viewModel.deliverFileChooserResult(arrayOf(capturedUri))
        } else {
            viewModel.deliverFileChooserResult(null)
        }
    }

    fun startCameraCapture() {
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val photoFile = File(capturesDir, "hearth_post_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        pendingCameraCaptureUri = uri
        takePictureLauncher.launch(uri)
    }

    // Runtime permissions launcher (handles initial prompt, WebRTC permission requests, and camera capture)
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val updatedStatus = HearthPermissions.checkStatus(context)
        viewModel.updatePermissionsStatus(updatedStatus)
        viewModel.markInitialPermissionsPrompted()

        val pendingWebRequest = uiState.pendingWebPermissionRequest
        if (pendingWebRequest != null) {
            viewModel.setPendingWebPermissionRequest(null)
            val grantedResources = pendingWebRequest.resources.filter { res ->
                when (res) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> updatedStatus.microphoneGranted
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> updatedStatus.cameraGranted
                    else -> true
                }
            }.toTypedArray()

            if (grantedResources.isNotEmpty()) {
                pendingWebRequest.grant(grantedResources)
            } else {
                pendingWebRequest.deny()
            }
        }

        if (launchCameraAfterPermission) {
            launchCameraAfterPermission = false
            if (updatedStatus.cameraGranted) {
                startCameraCapture()
            } else {
                viewModel.deliverFileChooserResult(null)
            }
        }
    }

    // Request all core voice/camera/notification/bluetooth permissions on first launch
    LaunchedEffect(Unit) {
        val status = HearthPermissions.checkStatus(context)
        viewModel.updatePermissionsStatus(status)
        val missing = HearthPermissions.missingRuntimePermissions(context)
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing)
        } else {
            viewModel.markInitialPermissionsPrompted()
        }
    }

    // Zero-permission Photo Picker launchers for posts (single & multiple)
    val singleMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.deliverFileChooserResult(uri?.let { arrayOf(it) })
    }

    val multiMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        viewModel.deliverFileChooserResult(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    // Storage Access Framework launchers for audio clips & documents in posts
    val singleDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.deliverFileChooserResult(uri?.let { arrayOf(it) })
    }

    val multiDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.deliverFileChooserResult(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    // Full-screen native app container with zero browser chrome
    Box(modifier = Modifier.fillMaxSize()) {
        HearthWebViewContainer(
            uiState = uiState,
            onPageStarted = viewModel::onPageStarted,
            onPageCommitVisible = viewModel::onPageCommitVisible,
            onPageFinished = viewModel::onPageFinished,
            onNavigationStateChanged = viewModel::onNavigationStateChanged,
            onPageError = viewModel::onPageError,
            onRequestWebPermissions = { request, neededPermissions ->
                viewModel.setPendingWebPermissionRequest(request)
                permissionsLauncher.launch(neededPermissions)
            },
            onShowFileChooser = { request ->
                viewModel.setPendingFileChooser(request)
            },
            onRetryClick = { viewModel.triggerReload() },
            modifier = Modifier.fillMaxSize()
        )

        // Subtle dismissible native prompt shown ONLY if permissions were denied after prompting
        val showMissingPermsBanner = uiState.hasPromptedInitialPermissions &&
            !uiState.isInitialLoading &&
            !uiState.permissionsStatus.allGranted &&
            !uiState.permissionsBannerDismissed

        AnimatedVisibility(
            visible = showMissingPermsBanner,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(id = R.string.perm_banner_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.setPermissionsSheetVisible(true) },
                        modifier = Modifier.testTag("open_permissions_banner_button")
                    ) {
                        Text(
                            text = stringResource(id = R.string.perm_banner_action),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    IconButton(
                        onClick = { viewModel.dismissPermissionsBanner() },
                        modifier = Modifier.testTag("dismiss_permissions_banner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.cd_dismiss_banner),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Permissions review & Grant All sheet
    if (uiState.showPermissionsSheet) {
        PermissionsBottomSheet(
            permissionsStatus = uiState.permissionsStatus,
            onDismiss = { viewModel.setPermissionsSheetVisible(false) },
            onGrantAllClick = {
                val missing = HearthPermissions.missingRuntimePermissions(context)
                if (missing.isNotEmpty()) {
                    permissionsLauncher.launch(missing)
                }
            },
            onOpenSettingsClick = {
                val settingsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(settingsIntent)
            }
        )
    }

    // Post media/audio/camera chooser sheet
    val fileChooser = uiState.pendingFileChooser
    if (uiState.showMediaChooserSheet && fileChooser != null) {
        PostMediaChooserSheet(
            onPickPhotoOrVideo = {
                viewModel.dismissMediaChooserSheetOnly()
                val accept = fileChooser.acceptTypes.joinToString(",")
                val visualType = when {
                    accept.contains("video") && !accept.contains("image") ->
                        ActivityResultContracts.PickVisualMedia.VideoOnly
                    accept.contains("image") && !accept.contains("video") ->
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                }
                if (fileChooser.allowMultiple) {
                    multiMediaPickerLauncher.launch(PickVisualMediaRequest(visualType))
                } else {
                    singleMediaPickerLauncher.launch(PickVisualMediaRequest(visualType))
                }
            },
            onTakePhoto = {
                viewModel.dismissMediaChooserSheetOnly()
                if (HearthPermissions.isCameraGranted(context)) {
                    startCameraCapture()
                } else {
                    launchCameraAfterPermission = true
                    permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            },
            onPickAudioOrFiles = {
                viewModel.dismissMediaChooserSheetOnly()
                val mimeTypes = fileChooser.acceptTypes
                    .filter { it.contains("/") }
                    .ifEmpty { listOf("audio/*", "image/*", "video/*", "*/*") }
                    .toTypedArray()
                if (fileChooser.allowMultiple) {
                    multiDocumentLauncher.launch(mimeTypes)
                } else {
                    singleDocumentLauncher.launch(mimeTypes)
                }
            },
            onCancel = {
                viewModel.deliverFileChooserResult(null)
            }
        )
    }
}

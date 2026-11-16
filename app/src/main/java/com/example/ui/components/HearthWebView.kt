package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.permissions.HearthPermissions
import com.example.ui.FileChooserRequest
import com.example.ui.HearthUiState

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HearthWebViewContainer(
    uiState: HearthUiState,
    onPageStarted: (String?) -> Unit,
    onPageCommitVisible: () -> Unit,
    onPageFinished: (String?, Boolean) -> Unit,
    onNavigationStateChanged: (Boolean) -> Unit,
    onPageError: (String?) -> Unit,
    onRequestWebPermissions: (PermissionRequest, Array<String>) -> Unit,
    onShowFileChooser: (FileChooserRequest) -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = uiState.canGoBack) {
        webViewRef?.goBack()
    }

    LaunchedEffect(uiState.reloadNonce) {
        if (uiState.reloadNonce > 0) {
            webViewRef?.reload()
        }
    }

    LaunchedEffect(uiState.homeUrl) {
        val currentLoaded = webViewRef?.url
        if (webViewRef != null && currentLoaded != null && currentLoaded != uiState.homeUrl && uiState.homeUrl != HearthUiState.DEFAULT_HEARTH_URL) {
            webViewRef?.loadUrl(uiState.homeUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            popupWebView?.destroy()
            popupWebView = null
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(surfaceBackgroundArgb)
                    configureNativeAppWebSettings(this)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            if (scheme == "http" || scheme == "https") {
                                val host = uri.host?.lowercase().orEmpty()
                                return if (isInAppHost(host)) {
                                    false
                                } else {
                                    openExternalUri(ctx, uri)
                                    true
                                }
                            }
                            openExternalUri(ctx, uri)
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            onPageStarted(url)
                            onNavigationStateChanged(view?.canGoBack() == true)
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            injectNativeFeelStyles(view)
                            onPageCommitVisible()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            injectNativeFeelStyles(view)
                            CookieManager.getInstance().flush()
                            onPageFinished(url, view?.canGoBack() == true)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                onPageError(error?.description?.toString())
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null) return
                            val resources = request.resources ?: emptyArray()
                            val missing = mutableListOf<String>()

                            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                                !HearthPermissions.isMicrophoneGranted(ctx)
                            ) {
                                missing.add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                                !HearthPermissions.isCameraGranted(ctx)
                            ) {
                                missing.add(Manifest.permission.CAMERA)
                            }

                            if (missing.isEmpty()) {
                                request.grant(resources)
                            } else {
                                onRequestWebPermissions(request, missing.toTypedArray())
                            }
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (filePathCallback == null) return false
                            val acceptTypes = fileChooserParams?.acceptTypes
                                ?.map { it.trim() }
                                ?.filter { it.isNotBlank() }
                                ?: emptyList()
                            val allowMultiple =
                                fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                            onShowFileChooser(
                                FileChooserRequest(
                                    callback = filePathCallback,
                                    acceptTypes = acceptTypes,
                                    allowMultiple = allowMultiple
                                )
                            )
                            return true
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val childWebView = WebView(ctx).apply {
                                setBackgroundColor(surfaceBackgroundArgb)
                                configureNativeAppWebSettings(this)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return false
                                        val host = uri.host?.lowercase().orEmpty()
                                        if (host == "hearth-voice-talk.lovable.app") {
                                            webViewRef?.loadUrl(uri.toString())
                                            popupWebView?.destroy()
                                            popupWebView = null
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView?) {
                                        popupWebView?.destroy()
                                        popupWebView = null
                                    }
                                }
                            }
                            popupWebView = childWebView
                            transport.webView = childWebView
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webViewRef = this
                    loadUrl(uiState.homeUrl)
                }
            }
        )

        // Native branded launch splash overlay (fades out as soon as first app frame paints)
        AnimatedVisibility(
            visible = uiState.isInitialLoading && !uiState.hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            HearthSplashOverlay()
        }

        // Offline / connection recovery view
        AnimatedVisibility(
            visible = uiState.hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            HearthOfflineOverlay(
                errorDetail = uiState.errorDescription,
                onRetryClick = onRetryClick
            )
        }
    }

    // In-app authentication popup dialog (for OAuth / Google Sign-In popups)
    popupWebView?.let { activePopup ->
        Dialog(
            onDismissRequest = {
                activePopup.destroy()
                popupWebView = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(id = R.string.popup_window_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = {
                                activePopup.destroy()
                                popupWebView = null
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(id = R.string.cd_close_popup)
                            )
                        }
                    }
                    AndroidView(
                        factory = { activePopup },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureNativeAppWebSettings(webView: WebView) {
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.overScrollMode = View.OVER_SCROLL_NEVER

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = true
        allowContentAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = userAgentString.replace("; wv", "")
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
}

private fun injectNativeFeelStyles(webView: WebView?) {
    webView?.evaluateJavascript(
        """
        (function() {
          if (document.getElementById('hearth-native-style')) return;
          var style = document.createElement('style');
          style.id = 'hearth-native-style';
          style.textContent = '* { -webkit-tap-highlight-color: transparent; } html, body { overscroll-behavior: none; }';
          (document.head || document.documentElement).appendChild(style);
        })();
        """.trimIndent(),
        null
    )
}

private fun isInAppHost(host: String): Boolean {
    return host == "hearth-voice-talk.lovable.app" ||
        host.endsWith(".lovable.app") ||
        host.endsWith("accounts.google.com") ||
        host.endsWith(".supabase.co") ||
        host.endsWith(".firebaseapp.com") ||
        host.endsWith("appleid.apple.com") ||
        host.endsWith("github.com")
}

private fun openExternalUri(context: android.content.Context, uri: Uri) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
private fun HearthSplashOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_zyno_logo),
                contentDescription = stringResource(id = R.string.cd_hearth_brand),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(30.dp))
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.loading_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun HearthOfflineOverlay(
    errorDetail: String?,
    onRetryClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(id = R.string.offline_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.offline_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!errorDetail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorDetail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetryClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("retry_connection_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.action_retry),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.VioletTertiary
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HistoryStore.purgeDemoRecords(applicationContext)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                NotifyTraceAppScreen(
                    onWebViewCreated = { wv -> webViewRef = wv },
                    onPageLoaded = { pageLoaded = true },
                    openAccessSettings = ::openAccessSettings,
                    openAppInfo = ::openAppInfo,
                    exportHistoryJson = ::exportHistoryJson,
                    refreshWebView = ::refreshWebViewFromNative
                )
            }
        }
    }

    override fun onDestroy() {
        NotifyTraceListenerService.onNewNotificationCallback = null
        webViewRef?.destroy()
        webViewRef = null
        super.onDestroy()
    }

    fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun exportHistoryJson() {
        try {
            val jsonArray = HistoryStore.readRecent(applicationContext, 1000)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "NotifyTrace 365-Day Notification Archive")
                putExtra(Intent.EXTRA_TEXT, jsonArray.toString(2))
            }
            startActivity(Intent.createChooser(shareIntent, "Export NotifyTrace History JSON"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun refreshWebViewFromNative() {
        runOnUiThread {
            if (pageLoaded) {
                webViewRef?.evaluateJavascript(
                    "if(window.refreshFromNative) window.refreshFromNative();",
                    null
                )
            }
        }
    }

    inner class AndroidBridge(private val onStateChanged: () -> Unit) {
        @JavascriptInterface
        fun getHistoricalNotifications(): String =
            HistoryStore.readRecent(applicationContext).toString()

        @JavascriptInterface
        fun getAppIcons(): String =
            HistoryStore.readIconMap(applicationContext).toString()

        @JavascriptInterface
        fun openNotificationAccessSettings() {
            runOnUiThread { openAccessSettings() }
        }

        @JavascriptInterface
        fun openAppInfo() {
            runOnUiThread { this@MainActivity.openAppInfo() }
        }

        @JavascriptInterface
        fun isServiceEnabled(): Boolean = isNotificationListenerEnabled()

        @JavascriptInterface
        fun deleteNotification(id: String) {
            HistoryStore.deleteById(applicationContext, id)
            runOnUiThread { onStateChanged() }
        }

        @JavascriptInterface
        fun clearHistory() {
            HistoryStore.clearAll(applicationContext)
            runOnUiThread { onStateChanged() }
        }

    }

}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NotifyTraceAppScreen(
    onWebViewCreated: (WebView) -> Unit,
    onPageLoaded: () -> Unit,
    openAccessSettings: () -> Unit,
    openAppInfo: () -> Unit,
    exportHistoryJson: () -> Unit,
    refreshWebView: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isListenerActive by remember { mutableStateOf(activity.isNotificationListenerEnabled()) }
    var savedCount by remember { mutableIntStateOf(HistoryStore.count(context)) }
    var showSetupSheet by remember { mutableStateOf(false) }
    var canGoBackInWebView by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    fun syncNativeState() {
        isListenerActive = activity.isNotificationListenerEnabled()
        savedCount = HistoryStore.count(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    syncNativeState()
                    NotifyTraceListenerService.onNewNotificationCallback = { jsonStr ->
                        activity.runOnUiThread {
                            syncNativeState()
                            val escaped = JSONObject.quote(jsonStr)
                            webViewInstance?.evaluateJavascript(
                                "if(window.onNativeNotificationPosted) window.onNativeNotificationPosted($escaped);",
                                null
                            )
                        }
                    }
                    refreshWebView()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    NotifyTraceListenerService.onNewNotificationCallback = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            NotifyTraceListenerService.onNewNotificationCallback = null
        }
    }

    BackHandler(enabled = canGoBackInWebView || showSetupSheet) {
        if (showSetupSheet) {
            showSetupSheet = false
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SlateBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            NativeControlHeader(
                isListenerActive = isListenerActive,
                savedCount = savedCount,
                onToggleAccessClick = openAccessSettings,
                onOpenSetupSheetClick = { showSetupSheet = true }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SlateBackground)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("notifytrace_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#020617"))
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            builtInZoomControls = false
                            displayZoomControls = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                onPageLoaded()
                                canGoBackInWebView = view?.canGoBack() == true
                                syncNativeState()
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBackInWebView = view?.canGoBack() == true
                            }
                        }
                        webChromeClient = WebChromeClient()
                        addJavascriptInterface(
                            activity.AndroidBridge(onStateChanged = {
                                syncNativeState()
                            }),
                            "AndroidBridge"
                        )
                        loadUrl("file:///android_asset/index.html")
                        webViewInstance = this
                        onWebViewCreated(this)
                    }
                }
            )
        }
    }

    if (showSetupSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSetupSheet = false },
            sheetState = sheetState,
            containerColor = SlateSurface,
            contentColor = Color.White
        ) {
            NativeApkSetupSheetContent(
                isListenerActive = isListenerActive,
                savedCount = savedCount,
                onOpenAccessSettings = {
                    showSetupSheet = false
                    openAccessSettings()
                },
                onOpenAppInfo = {
                    showSetupSheet = false
                    openAppInfo()
                },
                onExportJson = {
                    showSetupSheet = false
                    exportHistoryJson()
                },
                onClearHistory = {
                    HistoryStore.clearAll(context)
                    syncNativeState()
                    refreshWebView()
                    showSetupSheet = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Cleared notification history; listening for live OS notifications")
                    }
                }
            )
        }
    }
}

@Composable
private fun NativeControlHeader(
    isListenerActive: Boolean,
    savedCount: Int,
    onToggleAccessClick: () -> Unit,
    onOpenSetupSheetClick: () -> Unit
) {
    Surface(
        color = SlateSurface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Brand + Live Native Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(IndigoPrimary, VioletTertiary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = stringResource(R.string.cd_app_logo),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$savedCount saved",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SlateMuted,
                                    fontSize = 11.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val badgeColor = if (isListenerActive) EmeraldAccent else AmberWarning
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(badgeColor.copy(alpha = 0.15f))
                                .border(
                                    width = 1.dp,
                                    color = badgeColor.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable(onClick = onToggleAccessClick)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("listener_status_chip")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isListenerActive) {
                                    stringResource(R.string.status_listener_active)
                                } else {
                                    stringResource(R.string.status_listener_off)
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = badgeColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }

                // Native Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onToggleAccessClick,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateSurfaceVariant)
                            .testTag("notification_access_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSetupSheetClick,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateSurfaceVariant)
                            .testTag("device_setup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = stringResource(R.string.cd_open_setup),
                            tint = IndigoPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = SlateSurfaceVariant, thickness = 1.dp)
        }
    }
}

@Composable
private fun NativeApkSetupSheetContent(
    isListenerActive: Boolean,
    savedCount: Int,
    onOpenAccessSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onExportJson: () -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(IndigoPrimary, VioletTertiary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.sheet_title_setup),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = stringResource(R.string.sheet_subtitle_setup),
                    style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted)
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurfaceVariant),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isListenerActive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (isListenerActive) EmeraldAccent else AmberWarning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isListenerActive) {
                            "NotificationListenerService Connected ($savedCount logs archived)"
                        } else {
                            "Notification Access is OFF ($savedCount logs in archive)"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    )
                }
                Text(
                    text = "1. Tap 'Open Notification Access' and enable NotifyTrace.\n" +
                        "2. On Android 13+, if the switch says 'Restricted setting', tap 'Open App Info', open the 3-dot menu (top right), and select 'Allow restricted settings'.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SlateMuted,
                        lineHeight = 18.sp
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenAccessSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("sheet_open_access_button")
                    ) {
                        Text("Notification Access", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenAppInfo,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("sheet_open_app_info_button")
                    ) {
                        Text("Open App Info", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        Text(
            text = "365-Day Archive Tools",
            style = MaterialTheme.typography.labelLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onExportJson,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("export_history_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_share_export), fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onClearHistory,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("sheet_clear_history_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear Archive", fontSize = 12.sp, color = AmberWarning)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

package com.mobilespeaker.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilespeaker.app.BuildConfig
import com.mobilespeaker.app.model.AndroidUiState
import com.mobilespeaker.app.service.PlaybackForegroundService
import com.mobilespeaker.app.stream.StreamRuntime
import com.mobilespeaker.app.ui.MainViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var launchAutomation: LaunchAutomation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchAutomation = parseLaunchAutomation(intent)

        val runtime = StreamRuntime.get(this)
        setContent { MobileSpeakerApp(vm = viewModel()) }

        if (launchAutomation.autoStartScan || launchAutomation.autoConnectIp.isNotBlank()) {
            window.decorView.postDelayed({
                if (launchAutomation.autoStartScan) {
                    runtime.startDiscovery()
                }
                if (launchAutomation.autoConnectIp.isNotBlank()) {
                    runtime.setManualIp(launchAutomation.autoConnectIp)
                    if (launchAutomation.autoStartForeground) {
                        startPlaybackService()
                    }
                    runtime.connect(
                        launchAutomation.autoConnectIp,
                        launchAutomation.autoConnectName.ifBlank { "ADB Auto" }
                    )
                }
            }, 350L)
        }
        if (BuildConfig.DEBUG && launchAutomation.debugStressCase.isNotBlank()) {
            window.decorView.postDelayed({
                runtime.runDebugStress(
                    testCase = launchAutomation.debugStressCase,
                    targetIp = launchAutomation.debugTargetIp.ifBlank { launchAutomation.autoConnectIp },
                    requestedName = launchAutomation.debugTargetName.ifBlank {
                        launchAutomation.autoConnectName.ifBlank { "Debug Stress" }
                    }
                )
            }, 1200L)
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MobileSpeakerApp(vm: MainViewModel) {
        val ui by vm.ui.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableStateOf(MobilePage.Home) }
        var followSystemLanguage by rememberSaveable { mutableStateOf(true) }
        var languageOverride by rememberSaveable { mutableStateOf(getSystemLanguage()) }
        val language = if (followSystemLanguage) getSystemLanguage() else languageOverride

        val text = remember(language) { copyFor(language) }
        val primary = Color(0xFF1296F3)
        val primaryDark = Color(0xFF0C63D4)
        val surfaceBg = Color(0xFFF2F7FE)

        MaterialTheme {
            Scaffold(
                containerColor = surfaceBg,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(text.appTitle, fontWeight = FontWeight.SemiBold) },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = primary,
                            titleContentColor = Color.White
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        MobilePage.entries.forEach { target ->
                            NavigationBarItem(
                                selected = page == target,
                                onClick = { page = target },
                                label = {
                                    Text(
                                        pageTitle(target, text),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 10.sp
                                    )
                                },
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (page == target) primary else Color(0xFFB0BEC5))
                                    )
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                when (page) {
                    MobilePage.Home -> HomePage(
                        ui = ui,
                        text = text,
                        primary = primary,
                        primaryDark = primaryDark,
                        onVolumeUp = vm::volumeUp,
                        onVolumeDown = vm::volumeDown,
                        onToggleMute = vm::toggleMute,
                        onDisconnect = vm::disconnect,
                        onReconnect = {
                            startPlaybackService()
                            vm.reconnect()
                        },
                        controlsEnabled = !ui.isConnectionBusy,
                        modifier = Modifier.padding(innerPadding)
                    )

                    MobilePage.Devices -> DevicesPage(
                        ui = ui,
                        text = text,
                        primary = primary,
                        onScan = vm::startScan,
                        onStop = vm::stopScan,
                        onConnectDiscovered = { ip, name ->
                            startPlaybackService()
                            vm.connect(ip, name)
                        },
                        controlsEnabled = !ui.isConnectionBusy,
                        modifier = Modifier.padding(innerPadding)
                    )

                    MobilePage.Settings -> SettingsPage(
                        ui = ui,
                        text = text,
                        primary = primary,
                        followSystemLanguage = followSystemLanguage,
                        selectedLanguage = language,
                        onFollowSystemLanguage = {
                            followSystemLanguage = true
                            languageOverride = getSystemLanguage()
                        },
                        onSelectLanguage = { selected ->
                            followSystemLanguage = false
                            languageOverride = selected
                        },
                        onToggleDontShow = vm::setDontShowAgain,
                        modifier = Modifier.padding(innerPadding)
                    )

                    MobilePage.Feedback -> FeedbackPage(
                        text = text,
                        primary = primary,
                        modifier = Modifier.padding(innerPadding)
                    )

                    MobilePage.About -> AboutPage(
                        text = text,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @Composable
    private fun HomePage(
        ui: AndroidUiState,
        text: AppText,
        primary: Color,
        primaryDark: Color,
        onVolumeUp: () -> Unit,
        onVolumeDown: () -> Unit,
        onToggleMute: () -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
        controlsEnabled: Boolean,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = primary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (ui.connectionStatus == "connected") Color(0xFF4CAF50) else Color(0xFFFFB300)
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (ui.connectionStatus == "connected") text.connectedToPc else text.connectingOrIdle,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        AudioWaveCard(
                            animateWave = ui.connectionStatus == "connected" && ui.playbackState == "playing" && !ui.isConnectionBusy,
                            primaryDark = primaryDark,
                            waveLevel = ui.waveLevel
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("${text.deviceLabel}: ${ui.connectedPcName}", color = Color.White)
                        Text("IP: ${ui.connectedPcIp}", color = Color.White)
                        Text("${text.networkLabel}: ${ui.networkName}", color = Color.White)
                        Text("${text.volumeLabel}: ${ui.volumePercent}%", color = Color.White)
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text.audioControls, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            PrimaryActionButton(text = text.volumeDown, color = primary, onClick = onVolumeDown, enabled = controlsEnabled, modifier = Modifier.weight(1f))
                            PrimaryActionButton(
                                text = if (ui.isMuted) text.unmute else text.mute,
                                color = primary,
                                onClick = onToggleMute,
                                enabled = controlsEnabled,
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryActionButton(text = text.volumeUp, color = primary, onClick = onVolumeUp, enabled = controlsEnabled, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            PrimaryActionButton(
                                text = text.disconnectConnection,
                                color = primary,
                                onClick = onDisconnect,
                                enabled = controlsEnabled,
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryActionButton(
                                text = text.reconnect,
                                color = primary,
                                onClick = onReconnect,
                                enabled = controlsEnabled,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text.connectionDetails, fontWeight = FontWeight.SemiBold)
                        Text("${text.playbackStatus}: ${toPlaybackText(ui.playbackState, text)}")
                        Text("${text.discoveredCount}: ${ui.discoveredPcs.size}")
                        Text("${text.recentLog}: ${ui.logs.firstOrNull().orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun DevicesPage(
        ui: AndroidUiState,
        text: AppText,
        primary: Color,
        onScan: () -> Unit,
        onStop: () -> Unit,
        onConnectDiscovered: (String, String) -> Unit,
        controlsEnabled: Boolean,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text.deviceConnection, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            PrimaryActionButton(text.deviceScanStart, primary, onScan, enabled = controlsEnabled, modifier = Modifier.weight(1f))
                            PrimaryActionButton(text.deviceScanStop, primary, onStop, enabled = controlsEnabled, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            items(ui.discoveredPcs) { pc ->
                val isConnected = ui.connectionStatus == "connected" && ui.connectedPcIp == pc.ip
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(pc.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pc.ip, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (isConnected) text.statusConnected else pc.status,
                                color = if (isConnected) Color(0xFF2E7D32) else Color(0xFF607D8B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isConnected) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFE8F5E9)
                            ) {
                                Text(
                                    text.statusConnected,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            PrimaryActionButton(
                                text.connect,
                                primary,
                                { onConnectDiscovered(pc.ip, pc.name) },
                                enabled = controlsEnabled,
                                modifier = Modifier.width(96.dp)
                            )
                        }
                    }
                }
            }

            if (ui.discoveredPcs.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, tonalElevation = 1.dp) {
                        Text(
                            text.emptyDeviceHint,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF607D8B)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPage(
        ui: AndroidUiState,
        text: AppText,
        primary: Color,
        followSystemLanguage: Boolean,
        selectedLanguage: AppLanguage,
        onFollowSystemLanguage: () -> Unit,
        onSelectLanguage: (AppLanguage) -> Unit,
        onToggleDontShow: (Boolean) -> Unit,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text.preferencesTitle, fontWeight = FontWeight.SemiBold)
                        Text(text.languageTitle, color = Color(0xFF607D8B))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            PrimaryActionButton(
                                text = text.chinese,
                                color = if (selectedLanguage == AppLanguage.Chinese) primary else Color(0xFF90CAF9),
                                onClick = { onSelectLanguage(AppLanguage.Chinese) },
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryActionButton(
                                text = text.english,
                                color = if (selectedLanguage == AppLanguage.English) primary else Color(0xFF90CAF9),
                                onClick = { onSelectLanguage(AppLanguage.English) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF5F9FF),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onFollowSystemLanguage)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text.followSystemLanguage, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (followSystemLanguage) text.followSystemEnabled else text.followSystemDisabled,
                                        color = Color(0xFF607D8B)
                                    )
                                }
                                Switch(checked = followSystemLanguage, onCheckedChange = { onFollowSystemLanguage() })
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text.dontShowGuide)
                            Switch(checked = ui.isDontShowAgain, onCheckedChange = onToggleDontShow)
                        }
                        AssistChip(onClick = {}, label = { Text("${text.currentStatus}: ${toStatusText(ui.connectionStatus, text)}") })
                        AssistChip(onClick = {}, label = { Text("${text.mutedLabel}: ${if (ui.isMuted) text.yes else text.no}") })
                        AssistChip(onClick = {}, label = { Text("${text.volumeLabel}: ${ui.volumePercent}%") })
                    }
                }
            }
        }
    }

    @Composable
    private fun FeedbackPage(
        text: AppText,
        primary: Color,
        modifier: Modifier = Modifier
    ) {
        val context = LocalContext.current
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryActionButton(
                text = text.openFeedbackLink,
                color = primary,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.feedbackUrl)))
                },
                modifier = Modifier.fillMaxWidth()
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFF5F9FF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadsImagesAutomatically = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                    webViewClient = WebViewClient()
                                    loadUrl(text.feedbackUrl)
                                }
                            },
                            update = { webView ->
                                if (webView.url != text.feedbackUrl) {
                                    webView.loadUrl(text.feedbackUrl)
                                }
                            }
                        )
                    }
                    Text(
                        text.feedbackUrl,
                        color = Color(0xFF0C63D4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun AboutPage(
        text: AppText,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text.firstUseSteps, fontWeight = FontWeight.SemiBold)
                        Text(text.aboutStep1)
                        Text(text.aboutStep2)
                        Text(text.aboutStep3)
                        Text(text.aboutStep4)
                        Spacer(Modifier.height(8.dp))
                        Text(text.aboutPricingTitle, fontWeight = FontWeight.SemiBold)
                        Text(text.aboutPricingBody)
                    }
                }
            }
        }
    }

    @Composable
    private fun AudioWaveCard(
        animateWave: Boolean,
        primaryDark: Color,
        waveLevel: Float
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0x260B4FA8)
        ) {
            if (!animateWave) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(primaryDark.copy(alpha = 0.7f))
                    )
                }
                return@Surface
            }
            val phase = if (animateWave) {
                val infinite = rememberInfiniteTransition(label = "wave")
                val animatedPhase by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1600, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "wave-phase"
                )
                animatedPhase
            } else {
                0f
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val safeMargin = 6f
                val maxCombinedWaveFactor = 1.46f
                val requestedAmplitude = 16f + (waveLevel.coerceIn(0f, 1f) * 36f)
                val amplitude =
                    requestedAmplitude.coerceAtMost(((height / 2f) - safeMargin) / maxCombinedWaveFactor)
                val colors =
                    listOf(
                        Color(0xFF79CBFF),
                        Color(0xFFA8E0FF),
                        Color(0xFFE7F6FF)
                    )
                val configs =
                    listOf(
                        Triple(1.45f, 0f, 1f),
                        Triple(1.95f, 1.15f, 0.78f),
                        Triple(2.45f, 2.25f, 0.54f)
                    )
                configs.forEachIndexed { index, (frequency, shift, weight) ->
                    val step = width / 42f
                    var previousPoint = Offset(0f, centerY)
                    for (i in 0..42) {
                        val x = i * step
                        val progress = if (width == 0f) 0f else x / width
                        val main =
                            kotlin.math.sin((progress * 6.28318f * frequency) + (phase * 6.28318f) + shift).toFloat()
                        val sub =
                            kotlin.math.cos((progress * 6.28318f * (frequency * 0.92f)) - (phase * 4.8f) + shift).toFloat()
                        val y = centerY + (main * amplitude * weight) + (sub * amplitude * 0.46f * weight)
                        val point = Offset(x, y)
                        if (i > 0) {
                            drawLine(
                                color = colors[index],
                                start = previousPoint,
                                end = point,
                                strokeWidth = (3.8f - index * 0.75f)
                            )
                        }
                        previousPoint = point
                    }
                }
            }
        }
    }

    @Composable
    private fun PrimaryActionButton(
        text: String,
        color: Color,
        onClick: () -> Unit,
        enabled: Boolean = true,
        modifier: Modifier = Modifier
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                disabledContainerColor = Color(0xFFBFC9D8),
                disabledContentColor = Color(0xFF5D6978)
            ),
            enabled = enabled,
            modifier = modifier
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    private fun pageTitle(page: MobilePage, text: AppText): String {
        return when (page) {
            MobilePage.Home -> text.pageHome
            MobilePage.Devices -> text.pageDevices
            MobilePage.Settings -> text.pageSettings
            MobilePage.Feedback -> text.pageFeedback
            MobilePage.About -> text.pageAbout
        }
    }
    private fun toStatusText(status: String, text: AppText): String {
        return when (status) {
            "idle" -> text.statusIdle
            "scanning" -> text.statusScanning
            "connecting" -> text.statusConnecting
            "connected" -> text.statusConnected
            "reconnecting" -> text.statusReconnecting
            "disconnected" -> text.statusDisconnected
            "error" -> text.statusError
            else -> status
        }
    }

    private fun toPlaybackText(state: String, text: AppText): String {
        return when (state) {
            "playing" -> text.playing
            "buffering" -> text.buffering
            "optimizing" -> text.optimizingAudio
            "stopped" -> text.stopped
            else -> state
        }
    }

    private fun getSystemLanguage(): AppLanguage {
        return if (Locale.getDefault().language.lowercase(Locale.ROOT).startsWith("zh")) {
            AppLanguage.Chinese
        } else {
            AppLanguage.English
        }
    }

    private fun copyFor(language: AppLanguage): AppText {
        return when (language) {
            AppLanguage.Chinese -> AppText(
                appTitle = "手机变音箱",
                pageHome = "首页",
                pageDevices = "设备",
                pageSettings = "设置",
                pageFeedback = "反馈",
                pageAbout = "关于",
                connectedToPc = "已连接到 Windows 电脑",
                connectingOrIdle = "连接中或未连接",
                deviceLabel = "设备",
                networkLabel = "网络",
                volumeLabel = "音量",
                audioControls = "音频控制",
                volumeUp = "音量+",
                volumeDown = "音量-",
                mute = "静音",
                unmute = "取消静音",
                disconnectConnection = "断开连接",
                reconnect = "重新连接",
                connectionDetails = "连接详情",
                playbackStatus = "播放状态",
                discoveredCount = "发现设备数量",
                recentLog = "最近日志",
                deviceConnection = "设备连接",
                deviceScanStart = "开始扫描",
                deviceScanStop = "停止扫描",
                manualPcIp = "手动输入 PC IP",
                manualIpConnect = "手动 IP 连接",
                connect = "连接",
                emptyDeviceHint = "暂无发现设备，先点“开始扫描”",
                preferencesTitle = "播放与偏好",
                languageTitle = "界面语言",
                chinese = "中文",
                english = "English",
                followSystemLanguage = "跟随系统语言",
                followSystemEnabled = "当前会自动跟随手机系统语言",
                followSystemDisabled = "当前使用手动选择的语言",
                dontShowGuide = "不再显示首次引导",
                currentStatus = "当前状态",
                mutedLabel = "静音",
                yes = "是",
                no = "否",
                feedbackTitle = "软件使用反馈",
                feedbackHint = "如果你在使用过程中遇到问题或有建议，欢迎直接填写反馈问卷。",
                openFeedbackLink = "打开反馈问卷",
                feedbackFallbackTitle = "问卷未能直接加载",
                feedbackFallbackHint = "你可以点击下方按钮，在浏览器中打开完整反馈问卷。",
                feedbackUrl = "https://tally.so/r/jaWqeY",
                firstUseSteps = "首次使用步骤",
                aboutStep1 = "1. 电脑和手机连接同一 WiFi",
                aboutStep2 = "2. 电脑端启动服务并确认虚拟声卡",
                aboutStep3 = "3. 手机端进入设备页扫描并连接",
                aboutStep4 = "4. 在电脑把输出切到 VB-CABLE Input",
                aboutPricingTitle = "📄 关于费用",
                aboutPricingBody = "当前版本免费使用（安卓端 + 电脑端）。\n\n如果你觉得这个工具有用，欢迎继续使用和反馈。\n后续会在稳定性、延迟和更多设备支持上持续优化。\n\n未来可能推出部分高级功能或专业版本（可选），\n但基础使用会尽量保持简单、可用。",
                statusIdle = "空闲",
                statusScanning = "扫描中",
                statusConnecting = "连接中",
                statusConnected = "已连接",
                statusReconnecting = "重连中",
                statusDisconnected = "已断开",
                statusError = "异常",
                playing = "播放中",
                buffering = "缓冲中",
                optimizingAudio = "音效优化中...",
                stopped = "已停止"
            )

            AppLanguage.English -> AppText(
                appTitle = "Mobile Speaker",
                pageHome = "Home",
                pageDevices = "Devices",
                pageSettings = "Settings",
                pageFeedback = "Survey",
                pageAbout = "About",
                connectedToPc = "Connected to Windows PC",
                connectingOrIdle = "Connecting or idle",
                deviceLabel = "Device",
                networkLabel = "Network",
                volumeLabel = "Volume",
                audioControls = "Audio Controls",
                volumeUp = "Vol+",
                volumeDown = "Vol-",
                mute = "Mute",
                unmute = "Unmute",
                disconnectConnection = "Disconnect",
                reconnect = "Reconnect",
                connectionDetails = "Connection Details",
                playbackStatus = "Playback",
                discoveredCount = "Discovered devices",
                recentLog = "Recent log",
                deviceConnection = "Device Connection",
                deviceScanStart = "Scan",
                deviceScanStop = "Stop",
                manualPcIp = "Enter PC IP manually",
                manualIpConnect = "Manual Connect",
                connect = "Connect",
                emptyDeviceHint = "No devices found yet. Start scanning first.",
                preferencesTitle = "Playback and Preferences",
                languageTitle = "Language",
                chinese = "Chinese",
                english = "English",
                followSystemLanguage = "Follow system language",
                followSystemEnabled = "The app is currently following the phone system language",
                followSystemDisabled = "The app is currently using a manually selected language",
                dontShowGuide = "Do not show the first-use guide again",
                currentStatus = "Current status",
                mutedLabel = "Muted",
                yes = "Yes",
                no = "No",
                feedbackTitle = "Software Feedback",
                feedbackHint = "If you run into issues or have suggestions, you can submit feedback here.",
                openFeedbackLink = "Open Form",
                feedbackFallbackTitle = "The form could not load here",
                feedbackFallbackHint = "Tap the button below to open the full feedback form in your browser.",
                feedbackUrl = "https://tally.so/r/WOYkzk",
                firstUseSteps = "First-time Setup",
                aboutStep1 = "1. Connect your phone and PC to the same Wi-Fi",
                aboutStep2 = "2. Start the PC service and confirm the virtual sound card",
                aboutStep3 = "3. Scan and connect from the devices page on your phone",
                aboutStep4 = "4. Route PC output to VB-CABLE Input",
                aboutPricingTitle = "📄 Pricing",
                aboutPricingBody = "The current version is free to use (Android + PC).\n\nIf this tool is useful to you, you are welcome to keep using it and send feedback.\nWe will continue improving stability, latency, and support for more devices.\n\nIn the future, some advanced features or a professional edition may be offered as optional upgrades,\nbut the basic experience will remain as simple and usable as possible.",
                statusIdle = "Idle",
                statusScanning = "Scanning",
                statusConnecting = "Connecting",
                statusConnected = "Connected",
                statusReconnecting = "Reconnecting",
                statusDisconnected = "Disconnected",
                statusError = "Error",
                playing = "Playing",
                buffering = "Buffering",
                optimizingAudio = "Optimizing audio...",
                stopped = "Stopped"
            )
        }
    }

    private fun parseLaunchAutomation(intent: Intent?): LaunchAutomation {
        val i = intent ?: return LaunchAutomation()
        return LaunchAutomation(
            autoConnectIp = i.getStringExtra("auto_connect_ip").orEmpty(),
            autoConnectName = i.getStringExtra("auto_connect_name").orEmpty(),
            autoStartScan = i.getBooleanExtra("auto_start_scan", false),
            autoStartForeground = i.getBooleanExtra("auto_start_foreground", false),
            debugStressCase = i.getStringExtra("debug_stress_case").orEmpty(),
            debugTargetIp = i.getStringExtra("debug_target_ip").orEmpty(),
            debugTargetName = i.getStringExtra("debug_target_name").orEmpty()
        )
    }

    data class LaunchAutomation(
        val autoConnectIp: String = "",
        val autoConnectName: String = "",
        val autoStartScan: Boolean = false,
        val autoStartForeground: Boolean = false,
        val debugStressCase: String = "",
        val debugTargetIp: String = "",
        val debugTargetName: String = ""
    )

    private enum class MobilePage {
        Home,
        Devices,
        Settings,
        Feedback,
        About
    }

    private enum class AppLanguage {
        Chinese,
        English
    }
    private data class AppText(
        val appTitle: String,
        val pageHome: String,
        val pageDevices: String,
        val pageSettings: String,
        val pageFeedback: String,
        val pageAbout: String,
        val connectedToPc: String,
        val connectingOrIdle: String,
        val deviceLabel: String,
        val networkLabel: String,
        val volumeLabel: String,
        val audioControls: String,
        val volumeUp: String,
        val volumeDown: String,
        val mute: String,
        val unmute: String,
        val disconnectConnection: String,
        val reconnect: String,
        val connectionDetails: String,
        val playbackStatus: String,
        val discoveredCount: String,
        val recentLog: String,
        val deviceConnection: String,
        val deviceScanStart: String,
        val deviceScanStop: String,
        val manualPcIp: String,
        val manualIpConnect: String,
        val connect: String,
        val emptyDeviceHint: String,
        val preferencesTitle: String,
        val languageTitle: String,
        val chinese: String,
        val english: String,
        val followSystemLanguage: String,
        val followSystemEnabled: String,
        val followSystemDisabled: String,
        val dontShowGuide: String,
        val currentStatus: String,
        val mutedLabel: String,
        val yes: String,
        val no: String,
        val feedbackTitle: String,
        val feedbackHint: String,
        val openFeedbackLink: String,
        val feedbackFallbackTitle: String,
        val feedbackFallbackHint: String,
        val feedbackUrl: String,
        val firstUseSteps: String,
        val aboutStep1: String,
        val aboutStep2: String,
        val aboutStep3: String,
        val aboutStep4: String,
        val aboutPricingTitle: String,
        val aboutPricingBody: String,
        val statusIdle: String,
        val statusScanning: String,
        val statusConnecting: String,
        val statusConnected: String,
        val statusReconnecting: String,
        val statusDisconnected: String,
        val statusError: String,
        val playing: String,
        val buffering: String,
        val optimizingAudio: String,
        val stopped: String
    )
}

package com.angos.freeform

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.angos.freeform.core.AppEntry
import com.angos.freeform.core.AppRepository
import com.angos.freeform.core.DockPrefs
import com.angos.freeform.core.FreeformLauncher
import com.angos.freeform.service.DockService
import com.angos.freeform.ui.GlassSurface
import com.angos.freeform.ui.MacColors
import com.angos.freeform.ui.MacFreeformTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MacFreeformTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var dockItems by remember { mutableStateOf(DockPrefs.items(context)) }
    var dockRunning by remember { mutableStateOf(DockService.isRunning(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { apps = AppRepository.loadLaunchableApps(context) }
    LaunchedEffect(refreshTick) { dockRunning = DockService.isRunning(context) }

    val overlayGranted = Settings.canDrawOverlays(context)
    val freeformOn = FreeformLauncher.isFreeformEnabledOnDevice(context)
    val hiddenApiOk = FreeformLauncher.hiddenApiPolicyOk(context)

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0x33101018), Color(0x66202030), Color(0x33101018))
                )
            )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            WindowChrome(title = "MacFreeform")

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                StatusPanel(
                    overlayGranted = overlayGranted,
                    freeformOn = freeformOn,
                    hiddenApiOk = hiddenApiOk,
                    dockRunning = dockRunning,
                    onGrantOverlay = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    onOpenDevOptions = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }
                    },
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onToggleDock = {
                        if (dockRunning) DockService.stop(context) else DockService.start(context)
                        refreshTick++
                    }
                )

                Spacer(Modifier.height(14.dp))
                SearchField(query) { query = it }
                Spacer(Modifier.height(14.dp))

                GlassSurface(Modifier.fillMaxWidth().weight(1f), cornerRadius = 22.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppTile(
                                app = app,
                                inDock = app.packageName in dockItems,
                                onLaunch = { FreeformLauncher.launch(context, app.packageName) },
                                onToggleDock = {
                                    DockPrefs.toggle(context, app.packageName)
                                    dockItems = DockPrefs.items(context)
                                    if (dockRunning) DockService.refresh(context)
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ chrome */

@Composable
private fun WindowChrome(title: String) {
    val context = LocalContext.current
    GlassSurface(
        Modifier.fillMaxWidth().height(44.dp),
        cornerRadius = 0.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrafficLight(MacColors.TrafficRed) { (context as? ComponentActivity)?.finish() }
            Spacer(Modifier.width(8.dp))
            TrafficLight(MacColors.TrafficAmber) { (context as? ComponentActivity)?.moveTaskToBack(true) }
            Spacer(Modifier.width(8.dp))
            TrafficLight(MacColors.TrafficGreen) { }
            Spacer(Modifier.weight(1f))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
    }
}

@Composable
private fun TrafficLight(color: Color, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, label = "light")
    Box(
        Modifier
            .size(13.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(color)
            .clickable {
                pressed = true
                onClick()
                pressed = false
            }
    )
}

/* ------------------------------------------------------------------ panels */

@Composable
private fun StatusPanel(
    overlayGranted: Boolean,
    freeformOn: Boolean,
    hiddenApiOk: Boolean,
    dockRunning: Boolean,
    onGrantOverlay: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onToggleDock: () -> Unit
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Setup",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))

            StatusRow(
                "Freeform windows enabled",
                freeformOn,
                "Developer options → Enable freeform windows",
                onOpenDevOptions
            )
            StatusRow(
                "Hidden API access",
                hiddenApiOk,
                "adb shell settings put global hidden_api_policy 1",
                null
            )
            StatusRow("Display over other apps", overlayGranted, "Required for the dock", onGrantOverlay)
            StatusRow(
                "Window decorations service",
                false,
                "Enable MacFreeform in Accessibility",
                onOpenAccessibility
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onToggleDock,
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MacColors.Accent)
            ) {
                Text(if (dockRunning) "Stop Dock" else "Start Dock")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, hint: String, onAction: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (onAction != null) Modifier.clickable { onAction() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (ok) MacColors.TrafficGreen else MacColors.TrafficAmber)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        if (onAction != null) {
            Text("Open", style = MaterialTheme.typography.labelSmall, color = MacColors.Accent)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().height(42.dp), cornerRadius = 12.dp) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    "Search apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                cursorBrush = SolidColor(MacColors.Accent),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    inDock: Boolean,
    onLaunch: () -> Unit,
    onToggleDock: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "tile")

    Column(
        modifier = Modifier
            .padding(4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                pressed = true
                onLaunch()
                pressed = false
            }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Image(
                bitmap = remember(app.packageName) { app.icon.toBitmap(144, 144).asImageBitmap() },
                contentDescription = app.label,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (inDock) "In dock ✓" else "Add to dock",
            style = MaterialTheme.typography.labelSmall,
            color = if (inDock) MacColors.TrafficGreen else MacColors.Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleDock() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Suppress("unused")
private val strokeRef = Stroke(width = 1f) // keeps import churn out of diffs

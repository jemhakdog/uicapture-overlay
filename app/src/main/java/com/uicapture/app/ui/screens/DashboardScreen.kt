package com.uicapture.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uicapture.app.service.OverlayService
import com.uicapture.app.service.UICaptureAccessibilityService
import com.uicapture.app.ui.theme.AccentCyan
import com.uicapture.app.ui.theme.ErrorRed
import com.uicapture.app.ui.theme.PrimaryBlue
import com.uicapture.app.ui.theme.SuccessGreen
import com.uicapture.app.ui.theme.WarningAmber

/**
 * Main dashboard screen displaying permission diagnostics, floating overlay controls,
 * storage guidance, and quick navigation to captured sessions.
 */
@Composable
fun DashboardScreen(
    hasOverlayPermission: Boolean,
    isAccessibilityRunning: Boolean,
    isOverlayRunning: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onNavigateToGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var showAccessibilityWarningDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header Banner
        DashboardHeader(
            allReady = hasOverlayPermission && isAccessibilityRunning,
            isOverlayRunning = isOverlayRunning
        )

        // Floating Overlay Controller Card
        OverlayToggleCard(
            isOverlayRunning = isOverlayRunning,
            hasOverlayPermission = hasOverlayPermission,
            isAccessibilityRunning = isAccessibilityRunning,
            onToggle = { enable ->
                if (enable) {
                    if (!hasOverlayPermission) {
                        showOverlayPermissionDialog = true
                    } else if (!isAccessibilityRunning) {
                        showAccessibilityWarningDialog = true
                    } else {
                        onToggleOverlay(true)
                    }
                } else {
                    onToggleOverlay(false)
                }
            }
        )

        // Section: Permissions & System Diagnostics
        Text(
            text = "System Diagnostics & Permissions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1. Overlay Permission Card
        DiagnosticCard(
            title = "Display Over Other Apps",
            description = "Enables the floating capture bubble to remain accessible across any application.",
            isGranted = hasOverlayPermission,
            grantedText = "Permission Granted",
            deniedText = "Permission Required",
            icon = Icons.Default.Layers,
            actionLabel = if (hasOverlayPermission) "Manage Settings" else "Grant Permission",
            onAction = onOpenOverlaySettings
        )

        // 2. Accessibility Service Card
        DiagnosticCard(
            title = "Accessibility Inspection Service",
            description = "Enables real-time UI hierarchy traversal and native screenshot capture.",
            isGranted = isAccessibilityRunning,
            grantedText = "Service Active",
            deniedText = "Service Inactive",
            icon = Icons.Default.Accessibility,
            actionLabel = if (isAccessibilityRunning) "Accessibility Settings" else "Enable Service",
            onAction = onOpenAccessibilitySettings
        )

        // Section: Captures Quick Access
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = null,
                            tint = PrimaryBlue
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Captured Sessions Gallery",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Browse, inspect raw XML/JSON trees, and share exports.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onNavigateToGallery,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Captures Gallery")
                }
            }
        }

        // Storage Directory Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = "Storage Location",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Download/UICaptures/",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Overlay Permission Guidance Dialog
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(text = "Overlay Permission Required")
            },
            text = {
                Text(
                    text = "Android requires explicit permission to display floating windows over other apps. Please grant 'Display over other apps' in System Settings."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        onOpenOverlaySettings()
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Accessibility Service Inactive Warning Dialog
    if (showAccessibilityWarningDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(text = "Accessibility Service Inactive")
            },
            text = {
                Text(
                    text = "UICapture Accessibility Service is currently turned off. The floating bubble can be shown, but captures will fail until you enable the accessibility service."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityWarningDialog = false
                        onOpenAccessibilitySettings()
                    }
                ) {
                    Text("Enable Service")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAccessibilityWarningDialog = false
                        onToggleOverlay(true)
                    }
                ) {
                    Text("Start Anyway")
                }
            }
        )
    }
}

/**
 * Top header banner summarizing app readiness and active overlay state.
 */
@Composable
private fun DashboardHeader(
    allReady: Boolean,
    isOverlayRunning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UICapture Overlay",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Status Chip
                val chipColor = when {
                    isOverlayRunning -> SuccessGreen
                    allReady -> AccentCyan
                    else -> WarningAmber
                }
                val chipText = when {
                    isOverlayRunning -> "Overlay Active"
                    allReady -> "Ready"
                    else -> "Setup Required"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = chipColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = chipText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = chipColor
                    )
                }
            }

            Text(
                text = "Instant, zero-latency UI hierarchy inspection and screenshot capture floating tool for Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Interactive toggle card to show/hide the floating overlay bubble.
 */
@Composable
private fun OverlayToggleCard(
    isOverlayRunning: Boolean,
    hasOverlayPermission: Boolean,
    isAccessibilityRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOverlayRunning) {
                PrimaryBlue.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOverlayRunning) SuccessGreen.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isOverlayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isOverlayRunning) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column {
                    Text(
                        text = "Floating Capture Bubble",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isOverlayRunning) "Overlay bubble is active and draggable"
                        else "Tap switch to launch the floating capture bubble",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = isOverlayRunning,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PrimaryBlue,
                    checkedTrackColor = PrimaryBlue.copy(alpha = 0.4f)
                )
            )
        }
    }
}

/**
 * Diagnostic status card for system permissions and services.
 */
@Composable
private fun DiagnosticCard(
    title: String,
    description: String,
    isGranted: Boolean,
    grantedText: String,
    deniedText: String,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) SuccessGreen.copy(alpha = 0.15f)
                            else WarningAmber.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) SuccessGreen else WarningAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Icon
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isGranted) SuccessGreen else WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isGranted) grantedText else deniedText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isGranted) SuccessGreen else WarningAmber
                )

                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = actionLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Convenient stateful wrapper around [DashboardScreen] connected to Android context and service flows.
 */
@Composable
fun DashboardScreen(
    hasOverlayPermission: Boolean,
    isAccessibilityRunning: Boolean,
    onNavigateToGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isOverlayRunning by OverlayService.isRunning.collectAsState()

    DashboardScreen(
        hasOverlayPermission = hasOverlayPermission,
        isAccessibilityRunning = isAccessibilityRunning,
        isOverlayRunning = isOverlayRunning,
        onOpenOverlaySettings = {
            openOverlaySettings(context)
        },
        onOpenAccessibilitySettings = {
            openAccessibilitySettings(context)
        },
        onToggleOverlay = { enable ->
            if (enable) {
                OverlayService.start(context)
            } else {
                OverlayService.stop(context)
            }
        },
        onNavigateToGallery = onNavigateToGallery,
        modifier = modifier
    )
}

/**
 * Launches Android Settings screen for granting overlay permission.
 */
fun openOverlaySettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(fallback)
        } catch (_: Throwable) {
            // Safe ignore
        }
    }
}

/**
 * Launches Android Settings screen for enabling accessibility services.
 */
fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        // Safe ignore
    }
}

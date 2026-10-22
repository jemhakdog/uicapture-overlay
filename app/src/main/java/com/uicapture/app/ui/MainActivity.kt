package com.uicapture.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.uicapture.app.service.OverlayService
import com.uicapture.app.service.UICaptureAccessibilityService
import com.uicapture.app.ui.screens.CapturesGalleryScreen
import com.uicapture.app.ui.screens.DashboardScreen
import com.uicapture.app.ui.screens.openAccessibilitySettings
import com.uicapture.app.ui.screens.openOverlaySettings
import com.uicapture.app.ui.theme.PrimaryBlue
import com.uicapture.app.ui.theme.UICaptureTheme

/**
 * Top-level navigation destinations within UICapture app.
 */
enum class AppScreen(val label: String) {
    DASHBOARD("Dashboard"),
    GALLERY("Gallery")
}

/**
 * Main application activity hosting Compose navigation between Dashboard and Gallery,
 * managing permission polling on resume, and requesting runtime notification permissions.
 */
class MainActivity : ComponentActivity() {

    internal val hasOverlayPermissionState = mutableStateOf(false)
    internal val isAccessibilityEnabledState = mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // No-op: notification permission granted or dismissed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            UICaptureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isServiceRunningFlow by UICaptureAccessibilityService.isServiceRunningState.collectAsState()
                    val isOverlayRunningFlow by OverlayService.isRunning.collectAsState()

                    val effectiveAccessibilityRunning =
                        isServiceRunningFlow || isAccessibilityEnabledState.value

                    MainContent(
                        hasOverlayPermission = hasOverlayPermissionState.value,
                        isAccessibilityRunning = effectiveAccessibilityRunning,
                        isOverlayRunning = isOverlayRunningFlow,
                        onOpenOverlaySettings = { openOverlaySettings(this) },
                        onOpenAccessibilitySettings = { openAccessibilitySettings(this) },
                        onToggleOverlay = { enable ->
                            if (enable) {
                                OverlayService.start(this)
                            } else {
                                OverlayService.stop(this)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    /**
     * Refreshes permission and accessibility service active states.
     */
    internal fun refreshPermissionStates() {
        hasOverlayPermissionState.value = checkOverlayPermission(this)
        isAccessibilityEnabledState.value = checkAccessibilityStatus(this)
    }

    companion object {
        /**
         * Checks if the app has permission to draw overlays (SYSTEM_ALERT_WINDOW).
         */
        fun checkOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    Settings.canDrawOverlays(context)
                } catch (_: Throwable) {
                    false
                }
            } else {
                true
            }
        }

        /**
         * Checks if the accessibility service is actively connected or enabled in system settings.
         */
        fun checkAccessibilityStatus(context: Context): Boolean {
            if (UICaptureAccessibilityService.isServiceRunning) return true

            // Check Settings.Secure first for immediate feedback after returning from settings
            try {
                val enabledSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (!enabledSetting.isNullOrBlank() && enabledSetting.contains("UICaptureAccessibilityService")) {
                    return true
                }
            } catch (_: Throwable) {
                // Safe ignore
            }

            // Fallback to AccessibilityManager enabled services list
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                val enabledServices = am?.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ) ?: emptyList()

                val expectedPackage = context.packageName
                enabledServices.any { info ->
                    val idMatch = info.id?.contains("UICaptureAccessibilityService") == true
                    val resolveMatch = info.resolveInfo?.serviceInfo?.let { sInfo ->
                        sInfo.packageName == expectedPackage && sInfo.name.contains("UICaptureAccessibilityService")
                    } == true
                    idMatch || resolveMatch
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * Main navigation shell hosting Bottom Navigation and screens.
 */
@Composable
fun MainContent(
    hasOverlayPermission: Boolean,
    isAccessibilityRunning: Boolean,
    isOverlayRunning: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }

    // Intercept back button to return to dashboard from gallery
    BackHandler(enabled = currentScreen == AppScreen.GALLERY) {
        currentScreen = AppScreen.DASHBOARD
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.DASHBOARD,
                    onClick = { currentScreen = AppScreen.DASHBOARD },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryBlue,
                        indicatorColor = PrimaryBlue.copy(alpha = 0.15f)
                    )
                )

                NavigationBarItem(
                    selected = currentScreen == AppScreen.GALLERY,
                    onClick = { currentScreen = AppScreen.GALLERY },
                    icon = { Icon(Icons.Default.Collections, contentDescription = "Gallery") },
                    label = { Text("Gallery") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryBlue,
                        indicatorColor = PrimaryBlue.copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        when (currentScreen) {
            AppScreen.DASHBOARD -> {
                DashboardScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    isAccessibilityRunning = isAccessibilityRunning,
                    isOverlayRunning = isOverlayRunning,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onToggleOverlay = onToggleOverlay,
                    onNavigateToGallery = { currentScreen = AppScreen.GALLERY },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AppScreen.GALLERY -> {
                CapturesGalleryScreen(
                    onNavigateBack = { currentScreen = AppScreen.DASHBOARD },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

package com.uicapture.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.model.CaptureSessionItem
import com.uicapture.app.ui.theme.AccentCyan
import com.uicapture.app.ui.theme.ErrorRed
import com.uicapture.app.ui.theme.PrimaryBlue
import com.uicapture.app.ui.theme.SuccessGreen
import com.uicapture.app.util.GalleryLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gallery screen that scans and lists saved captures from `Download/UICaptures/`,
 * with full screenshot thumbnails, element counts, detail dialog, raw JSON/XML inspection,
 * and quick share/export actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturesGalleryScreen(
    onNavigateBack: (() -> Unit)? = null,
    baseDirectory: File = HierarchyExporter.defaultBaseDirectory(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<CaptureSessionItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSession by remember { mutableStateOf<CaptureSessionItem?>(null) }
    var sessionToDelete by remember { mutableStateOf<CaptureSessionItem?>(null) }

    fun refreshSessions() {
        coroutineScope.launch {
            isLoading = true
            val items = withContext(Dispatchers.IO) {
                GalleryLogic.scanCapturesDirectory(baseDirectory)
            }
            sessions = items
            isLoading = false
        }
    }

    LaunchedEffect(baseDirectory) {
        refreshSessions()
    }

    val filteredSessions = remember(sessions, searchQuery) {
        GalleryLogic.filterSessions(sessions, searchQuery)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Capture Gallery",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isLoading && sessions.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = PrimaryBlue.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${filteredSessions.size}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshSessions() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by package, activity, or ID...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Content Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    sessions.isEmpty() -> {
                        EmptyGalleryView(
                            baseDirectory = baseDirectory,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    filteredSessions.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "No captures matching '$searchQuery'",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 24.dp
                            )
                        ) {
                            items(filteredSessions, key = { it.captureId }) { session ->
                                CaptureSessionCard(
                                    session = session,
                                    onClick = { selectedSession = session },
                                    onShare = { shareSession(context, session) },
                                    onDelete = { sessionToDelete = session }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedSession?.let { session ->
        CaptureDetailDialog(
            session = session,
            onDismiss = { selectedSession = null },
            onShare = { shareSession(context, session) },
            onOpenInFiles = { openInFiles(context, session.directory) },
            onDelete = {
                selectedSession = null
                sessionToDelete = session
            }
        )
    }

    // Delete Confirmation Dialog
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Delete Capture Session?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete this capture for '${session.packageName}'? All files (${session.directory.name}) will be removed from storage."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = sessionToDelete
                        sessionToDelete = null
                        if (toDelete != null) {
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    GalleryLogic.deleteCaptureSession(toDelete)
                                }
                                refreshSessions()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Empty state view shown when no captures are found on disk.
 */
@Composable
private fun EmptyGalleryView(
    baseDirectory: File,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "No Captures Yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Tap the floating bubble over any app to capture screen hierarchies and screenshots.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = baseDirectory.absolutePath,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Individual card item representing a saved capture session.
 */
@Composable
private fun CaptureSessionCard(
    session: CaptureSessionItem,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var thumbnailBitmap by remember(session.screenshotFile) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(session.screenshotFile) {
        val file = session.screenshotFile
        if (file != null && file.exists()) {
            thumbnailBitmap = withContext(Dispatchers.IO) {
                loadThumbnailBitmap(file, 160)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnailBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Screenshot thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Info column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = session.packageName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!session.activityName.isNullOrBlank()) {
                    Text(
                        text = session.activityName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = GalleryLogic.formatRelativeTime(session.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryBlue.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "${session.elementCount} elements",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (session.interactiveCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentCyan.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${session.interactiveCount} tap",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                color = AccentCyan,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = ErrorRed.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen modal dialog allowing inspection of raw XML hierarchy, JSON tree,
 * screenshot, and metadata summary with sharing and export options.
 */
@Composable
private fun CaptureDetailDialog(
    session: CaptureSessionItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenInFiles: () -> Unit,
    onDelete: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Summary", "Hierarchy XML", "Hierarchy JSON", "Screenshot")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.packageName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = GalleryLogic.formatTimestamp(session.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShare) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = onOpenInFiles) {
                            Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Open in Files")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                // Tab Row
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title, fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }

                // Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    when (selectedTabIndex) {
                        0 -> SummaryTab(session = session)
                        1 -> CodeViewerTab(file = session.hierarchyXmlFile, format = "XML")
                        2 -> CodeViewerTab(file = session.hierarchyJsonFile, format = "JSON")
                        3 -> ScreenshotTab(file = session.screenshotFile)
                    }
                }
            }
        }
    }
}

/**
 * Summary detail view displaying key session attributes in a structured key-value format.
 */
@Composable
private fun SummaryTab(session: CaptureSessionItem) {
    val scrollState = rememberScrollState()
    val totalSize = remember(session.directory) {
        GalleryLogic.formatFileSize(GalleryLogic.getDirectoryTotalSize(session.directory))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryItemRow("Capture ID", session.captureId, isMonospace = true)
        SummaryItemRow("Target Package", session.packageName)
        SummaryItemRow("Activity", session.activityName ?: "N/A")
        SummaryItemRow("Resolution", session.screenResolution ?: "Unknown")
        SummaryItemRow("Total Elements", "${session.elementCount}")
        SummaryItemRow("Interactive Elements", "${session.interactiveCount}")
        SummaryItemRow("Recorded At", GalleryLogic.formatTimestamp(session.timestamp))
        SummaryItemRow("Directory Size", totalSize)
        SummaryItemRow("Directory Path", session.directory.absolutePath, isMonospace = true)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Exported Files",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        FileItemRow("summary.json", session.summaryJsonFile)
        FileItemRow("hierarchy.json", session.hierarchyJsonFile)
        FileItemRow("hierarchy.xml", session.hierarchyXmlFile)
        FileItemRow("screenshot.png", session.screenshotFile)
    }
}

@Composable
private fun SummaryItemRow(label: String, value: String, isMonospace: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun FileItemRow(name: String, file: File?) {
    val exists = file != null && file.exists()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        if (exists) {
            val size = GalleryLogic.formatFileSize(file!!.length())
            Text(
                text = size,
                style = MaterialTheme.typography.labelSmall,
                color = SuccessGreen
            )
        } else {
            Text(
                text = "Missing",
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed
            )
        }
    }
}

/**
 * Scrollable code viewer tab for XML and JSON files with copy-to-clipboard button.
 */
@Composable
private fun CodeViewerTab(file: File?, format: String) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var content by remember(file) { mutableStateOf<String?>(null) }
    var isLoading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        if (file != null && file.exists()) {
            content = withContext(Dispatchers.IO) {
                try {
                    file.readText(Charsets.UTF_8)
                } catch (e: Throwable) {
                    "Error reading file: ${e.message}"
                }
            }
        } else {
            content = "File not found on disk."
        }
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$format Tree (${file?.length()?.let { GalleryLogic.formatFileSize(it) } ?: "0 B"})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = {
                        val text = content
                        if (!text.isNullOrBlank()) {
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "$format copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy $format")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                val scrollStateV = rememberScrollState()
                val scrollStateH = rememberScrollState()

                Text(
                    text = content ?: "",
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollStateV)
                        .horizontalScroll(scrollStateH),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Screenshot full preview tab.
 */
@Composable
private fun ScreenshotTab(file: File?) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        if (file != null && file.exists()) {
            bitmap = withContext(Dispatchers.IO) {
                loadFullBitmap(file)
            }
        }
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val bmp = bitmap
        if (bmp != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Full capture screenshot",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No screenshot available for this capture session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Decodes a downsampled thumbnail bitmap from a file path.
 */
fun loadThumbnailBitmap(file: File, targetDimension: Int): ImageBitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        var sampleSize = 1
        while (maxDim / (sampleSize * 2) >= targetDimension) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        bmp?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Decodes a full-resolution bitmap from a file path.
 */
fun loadFullBitmap(file: File): ImageBitmap? {
    return try {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        bmp?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Dispatches an intent to share capture artifacts (screenshot, json, xml, summary).
 */
fun shareSession(context: Context, session: CaptureSessionItem) {
    val uris = ArrayList<Uri>()
    val files = listOfNotNull(
        session.screenshotFile,
        session.hierarchyJsonFile,
        session.hierarchyXmlFile,
        session.summaryJsonFile
    )

    for (file in files) {
        if (file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                uris.add(uri)
            } catch (_: Throwable) {
                // Ignore failure for individual missing file
            }
        }
    }

    if (uris.isEmpty()) {
        Toast.makeText(context, "No files found to share", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val chooser = Intent.createChooser(intent, "Share Capture: ${session.packageName}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: Throwable) {
        Toast.makeText(context, "No application available to share files", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Attempts to launch system file browser pointing to the capture session directory.
 */
fun openInFiles(context: Context, directory: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            directory
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open Directory"))
    } catch (_: Throwable) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(context, "Directory: ${directory.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}

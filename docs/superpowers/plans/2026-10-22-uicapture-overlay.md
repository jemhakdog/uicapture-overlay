# UICapture Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Spec: [docs/superpowers/specs/2026-10-22-uicapture-spec.md](docs/superpowers/specs/2026-10-22-uicapture-spec.md)

## Global Constraints
- **Zero Device Testing**: DO NOT run UI automator commands, Shizuku, accessibility dumps, or tap actions on the host Android device during implementation. Verification must be performed via unit tests and structural checks.
- **Android Target**: Android 8.0+ (minSdk 26, targetSdk 34, compileSdk 34).
- **Package Name**: `com.uicapture.app`
- **Output Storage Path**: `Download/UICaptures/` on public external storage.
- **Architecture Alignment**: The exported data format must strictly match the element properties and hierarchy patterns established in `andorid-mcp/aicore_bridge.py`.

---

## Task 1: Project Scaffolding, Gradle Configuration, and CI Pipeline

**Goal**: Setup the Android project structure with Gradle wrapper, dependencies (Compose, Material3, Coroutines, Serialization), Android Manifest with necessary permissions, and a GitHub Actions workflow to build and release APKs.

- [ ] Initialize Android project files: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, and `gradle/wrapper/gradle-wrapper.properties`.
- [ ] Configure `app/build.gradle.kts` with plugins (`com.android.application`, `org.jetbrains.kotlin.android`), dependencies (Compose, Lifecycle, Kotlinx Serialization, Coroutines), and signing configuration.
- [ ] Create `app/src/main/AndroidManifest.xml` declaring permissions:
  - `SYSTEM_ALERT_WINDOW`
  - `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28)
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_SPECIAL_USE` (or media projection/accessibility)
  - Declare `MainActivity`, `UICaptureAccessibilityService`, and `OverlayService`.
- [ ] Add `.github/workflows/build-apk.yml` for automated GitHub Actions CI building debug & release APKs and publishing releases with direct `.apk` downloads.
- [ ] Add `.gitignore` ignoring build artifacts, `.gradle`, and local caches.

---

## Task 2: Core Domain Models and Bounds Parsing

**Goal**: Implement the core data models for UI elements, screen hierarchy, and bounds coordinate math matching `andorid-mcp/aicore_bridge.py`.

- [ ] Create `com.uicapture.app.model.UIElement`:
  - Fields: `id`, `className`, `text`, `contentDesc`, `bounds` (`[x1,y1][x2,y2]`), `center` `(x, y)`, `size` `(w, h)`, `clickable`, `scrollable`, `editable`, `checkable`, `checked`, `focused`, `selected`, `depth`, `action` (`tap`, `type`, `scroll`, `read`).
- [ ] Create `com.uicapture.app.model.ScreenCaptureData`:
  - Contains `captureId`, `timestamp`, `packageName`, `activityName`, `screenResolution`, `elements: List<UIElement>`, `rawXml`, and optional `screenshotPath`.
- [ ] Implement `com.uicapture.app.util.BoundsParser`:
  - Parsing coordinate strings formatted as `[x1,y1][x2,y2]`.
  - Computing center points, width, height, and containment checks.
- [ ] Write unit tests verifying bounds parsing, element formatting, and serialization in `app/src/test/java/com/uicapture/app/BoundsParserTest.kt`.

---

## Task 3: Hierarchy Tree Builder and Exporter Engine

**Goal**: Implement the tree traversal logic for `AccessibilityNodeInfo` and the file exporter that outputs JSON, XML, and metadata summary into shared storage.

- [ ] Create `com.uicapture.app.engine.ScreenTreeBuilder`:
  - Recursively traverses `AccessibilityNodeInfo`.
  - Filters out the app's own package (`com.uicapture.app`) to prevent overlay self-capture.
  - Sanitizes text and attributes (handles special characters, unescaped XML entities).
  - Determines action types (`tap`, `scroll`, `type`, `read`) based on node interactivity.
- [ ] Create `com.uicapture.app.engine.HierarchyExporter`:
  - Formats tree into structured JSON matching `andorid-mcp`.
  - Formats tree into clean XML hierarchy with standard node attributes.
  - Writes files to target capture directory: `Download/UICaptures/cap_<timestamp>_<pkg>/`.
- [ ] Write unit tests for JSON and XML generation in `app/src/test/java/com/uicapture/app/HierarchyExporterTest.kt`.

---

## Task 4: Accessibility Service & Native Screen Capture

**Goal**: Implement the native `AccessibilityService` that monitors active windows, handles capture requests, and acquires screenshots via native Android APIs.

- [ ] Create `app/src/main/res/xml/accessibility_service_config.xml`:
  - `accessibilityFeedbackType="feedbackGeneric"`
  - `accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"`
  - `canRetrieveWindowContent="true"`
  - `canPerformGestures="true"`
- [ ] Create `com.uicapture.app.service.UICaptureAccessibilityService`:
  - Singleton/Service instance binding for app-wide capture triggers.
  - Method `captureActiveScreen(onSuccess: (ScreenCaptureData, Bitmap?) -> Unit, onError: (String) -> Unit)`.
  - Captures `rootInActiveWindow`.
  - On API 30+, calls `takeScreenshot(Display.DEFAULT_DISPLAY, ...)` to retrieve the `Bitmap` asynchronously.
  - Passes results to `HierarchyExporter` to write data and PNG to disk.

---

## Task 5: Floating Draggable Overlay Controller

**Goal**: Build the draggable floating bubble overlay that floats over any Android app and provides one-tap capture with visual and haptic feedback.

- [ ] Create `com.uicapture.app.overlay.FloatingOverlayController`:
  - Uses `WindowManager` with `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
  - Flags: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`.
- [ ] Implement touch drag listener supporting:
  - Drag to reposition freely on the screen.
  - Snap-to-edge animation on release.
  - Discrimination between short tap (trigger capture) vs move/drag.
- [ ] Implement floating bubble UI:
  - Capture icon (camera/crosshair).
  - Real-time capture count badge (`#1`, `#2`, ...).
  - Visual pulse/ripple animation when capture completes.
  - Toast / snackbar notification showing summary (e.g., `"Captured 48 elements for com.target.app"`).
- [ ] Add service lifecycle management (`OverlayService`) to maintain overlay in the foreground.

---

## Task 6: Compose Dashboard, Permission Management, and Gallery

**Goal**: Build the main Jetpack Compose application screen providing status diagnostics, permission onboarding, and a gallery of saved captures.

- [ ] Create `com.uicapture.app.ui.screens.DashboardScreen`:
  - Status cards for Overlay permission (`Settings.canDrawOverlays`) and Accessibility Service enabled status.
  - One-tap buttons to jump directly to Android system settings for granting permissions.
  - Toggle switch to Show / Hide the floating overlay bubble.
- [ ] Create `com.uicapture.app.ui.screens.CapturesGalleryScreen`:
  - Lists all captured sessions stored in `Download/UICaptures/`.
  - Shows screenshot thumbnail, target package name, timestamp, and element count.
  - Detail dialog displaying raw JSON / XML viewer.
  - Quick share intent or "Open in Files" button.
- [ ] Wire everything into `com.uicapture.app.ui.MainActivity` using modern Compose theme.

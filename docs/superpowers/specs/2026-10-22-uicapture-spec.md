# Specification: UICapture Overlay Android App

## 1. Problem Statement & Motivation
Android UI reverse-engineering, dataset collection, and automation mapping (as done in `andorid-mcp`) require capturing screen hierarchies, element coordinates, and screenshots across arbitrary target apps. Traditional `uiautomator dump` blocks on active animations/video and requires ADB tethering. 

The `UICapture Overlay` app provides an on-device floating bubble/popup overlay that remains active over any application. When tapped, it immediately captures the active window's UI tree (without idle freezes), captures a screenshot, formats the data into structured JSON and XML, and exports them to shared device storage (`/sdcard/Download/UICaptures/`).

## 2. Key Requirements & Constraints
- **Strict Prohibition on Device Testing**: No automation tests, dumps, or touch events may be executed against the host device during development. Testing is reserved exclusively for the user once the APK is generated.
- **Rootless & Standalone**: Operates using Android's native `AccessibilityService` (with `takeScreenshot` API 30+) and `TYPE_APPLICATION_OVERLAY`.
- **Zero-Latency Traversal**: Uses `rootInActiveWindow` with direct recursive node visitor, skipping internal overlay nodes.
- **Export Artifacts per Capture**:
  - `metadata.json` / `hierarchy.json`: JSON structure matching `andorid-mcp` (class, resource-id, text, content-desc, bounds, center, clickable, scrollable, action hints).
  - `hierarchy.xml`: Well-formed, entity-sanitized XML tree.
  - `screenshot.png`: Full-screen snapshot.
- **Floating Controls**:
  - Draggable floating bubble with capture badge counter.
  - Single tap: instant capture with tactile/visual feedback.
  - Drag to move, snap to screen edges.
- **Management UI**:
  - Android Compose dashboard with permission diagnostics (Overlay + Accessibility).
  - List of past captures with thumbnail, element counts, package name, and one-tap share/open.
- **Automated CI Build**:
  - Complete Gradle build structure + `.github/workflows/build-apk.yml` to compile and release direct `.apk` downloads.

## 3. Architecture & Data Flow

```
+-----------------------------------------------------------+
|                      Active Screen                        |
|                                                           |
|       +-------------------+                               |
|       | Floating Overlay  | <--- User Tap                 |
|       +---------+---------+                               |
+-----------------|-----------------------------------------+
                  |
                  v
+-----------------------------------------------------------+
|            UICaptureAccessibilityService                  |
|  - Queries rootInActiveWindow                             |
|  - Invokes takeScreenshot(Display.DEFAULT_DISPLAY)        |
+-----------------+-------------------+---------------------+
                  |                   |
                  v                   v
     +---------------------+  +-----------------+
     | HierarchyTreeBuilder|  | ScreenshotSaver |
     +----------+----------+  +--------+--------+
                |                      |
                v                      v
+-----------------------------------------------------------+
|                      ExportEngine                         |
|  Writes to: /sdcard/Download/UICaptures/cap_<ts>_<pkg>/   |
|   - hierarchy.json                                        |
|   - hierarchy.xml                                         |
|   - screenshot.png                                        |
|   - summary.json                                          |
+-----------------------------------------------------------+
```

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 * GPL-3.0-only.
 */
package app.hushgram.nativerunner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Captures the active window without UiAutomator's video-sensitive idle wait. */
public final class WalkSnapshot extends Instrumentation {
    private Bundle arguments;
    private int nodes;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : supplied;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo after = null;
        Bitmap screenshot = null;
        UiAutomation automation = null;
        try {
            String runId = arguments.getString("run_id", "");
            String captureId = arguments.getString("capture_id", "");
            String expectedPackage = arguments.getString("expected_package", "");
            if (!runId.matches("[a-f0-9]{32}") || !captureId.matches("[a-zA-Z0-9_-]{1,80}")
                    || !expectedPackage.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) {
                throw new IllegalArgumentException("Invalid walk capture identity");
            }
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(service);
            root = awaitActiveRoot(automation, expectedPackage, result);
            int windowId = root.getWindowId();
            long capturedUptime = SystemClock.uptimeMillis();
            StringBuilder xml = new StringBuilder(16384);
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy");
            attribute(xml, "run-id", runId);
            attribute(xml, "capture-id", captureId);
            attribute(xml, "uptime-ms", Long.toString(capturedUptime));
            attribute(xml, "window-id", Integer.toString(windowId));
            xml.append('>');
            appendNode(xml, root, 0);
            xml.append("</hierarchy>");
            screenshot = automation.takeScreenshot();
            if (screenshot == null) throw new IOException("The active window cannot be captured");
            after = automation.getRootInActiveWindow();
            if (after != null && !after.refresh()) throw new IOException("The captured window became stale");
            requireActivePackage(after, expectedPackage);
            if (after.getWindowId() != windowId) throw new IOException("The active window changed during capture");
            StringBuilder afterXml = new StringBuilder(16384);
            afterXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy");
            attribute(afterXml, "run-id", runId);
            attribute(afterXml, "capture-id", captureId);
            attribute(afterXml, "uptime-ms", Long.toString(SystemClock.uptimeMillis()));
            attribute(afterXml, "window-id", Integer.toString(windowId));
            afterXml.append('>');
            nodes = 0;
            appendNode(afterXml, after, 0);
            afterXml.append("</hierarchy>");
            File directory = new File(new File(getTargetContext().getFilesDir(), "walk-captures"), runId);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the capture directory");
            File uiFile = new File(directory, captureId + ".xml");
            File afterFile = new File(directory, captureId + "-after.xml");
            File imageFile = new File(directory, captureId + ".png");
            try (FileOutputStream stream = new FileOutputStream(uiFile)) {
                stream.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream stream = new FileOutputStream(afterFile)) {
                stream.write(afterXml.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream stream = new FileOutputStream(imageFile)) {
                if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Cannot write the active window image");
                }
            }
            result.putString("status", "captured");
            result.putString("run_id", runId);
            result.putString("capture_id", captureId);
            result.putString("expected_package", expectedPackage);
            result.putString("ui_path", uiFile.getAbsolutePath());
            result.putString("after_ui_path", afterFile.getAbsolutePath());
            result.putString("image_path", imageFile.getAbsolutePath());
            result.putLong("uptime_ms", capturedUptime);
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("status", "failed");
            result.putString("reason", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            if (automation != null) {
                try { writeUnavailableWindow(automation, result); }
                catch (Exception diagnosticFailure) { result.putString("diagnostic_error", diagnosticFailure.getClass().getSimpleName()); }
            }
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (root != null) root.recycle();
            if (after != null && after != root) after.recycle();
            if (screenshot != null) screenshot.recycle();
        }
    }

    private void writeUnavailableWindow(UiAutomation automation, Bundle result) throws IOException {
        String runId = arguments.getString("run_id", "");
        String captureId = arguments.getString("capture_id", "");
        if (!runId.matches("[a-f0-9]{32}") || !captureId.matches("[a-zA-Z0-9_-]{1,80}")) return;
        AccessibilityNodeInfo diagnosticRoot = automation.getRootInActiveWindow();
        if (diagnosticRoot == null) return;
        Bitmap image = null;
        try {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy diagnostic-only=\"true\">");
            nodes = 0;
            appendNode(xml, diagnosticRoot, 0);
            xml.append("</hierarchy>");
            File directory = new File(new File(getTargetContext().getFilesDir(), "walk-captures"), runId);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostic directory");
            File uiFile = new File(directory, captureId + "-unavailable.xml");
            try (FileOutputStream stream = new FileOutputStream(uiFile)) { stream.write(xml.toString().getBytes(StandardCharsets.UTF_8)); }
            result.putString("diagnostic_ui_path", uiFile.getAbsolutePath());
            image = automation.takeScreenshot();
            if (image != null) {
                File imageFile = new File(directory, captureId + "-unavailable.png");
                try (FileOutputStream stream = new FileOutputStream(imageFile)) {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Cannot write diagnostic image");
                }
                result.putString("diagnostic_image_path", imageFile.getAbsolutePath());
            }
        } finally {
            diagnosticRoot.recycle();
            if (image != null) image.recycle();
        }
    }

    private static void requireActivePackage(AccessibilityNodeInfo root, String expected) throws IOException {
        if (root == null || !root.isVisibleToUser() || root.getPackageName() == null
                || !expected.contentEquals(root.getPackageName())) {
            throw new IOException("The requested app is not the active visible window ("
                    + (root == null ? "no root" : "package=" + root.getPackageName()
                    + ", class=" + root.getClassName() + ", visible=" + root.isVisibleToUser()) + ")");
        }
    }

    private static AccessibilityNodeInfo awaitActiveRoot(UiAutomation automation, String expected, Bundle result) throws IOException {
        long deadline = SystemClock.uptimeMillis() + 2000;
        IOException lastFailure = new IOException("No active window was received");
        int attempts = 0;
        do {
            attempts++;
            AccessibilityNodeInfo candidate = automation.getRootInActiveWindow();
            boolean fresh = candidate != null && candidate.refresh();
            try {
                if (!fresh) throw new IOException("The active window root is not fresh");
                requireActivePackage(candidate, expected);
                result.putInt("root_attempts", attempts);
                return candidate;
            } catch (IOException unavailable) {
                lastFailure = unavailable;
                if (candidate != null) candidate.recycle();
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw lastFailure;
    }

    private void appendNode(StringBuilder xml, AccessibilityNodeInfo node, int depth) throws IOException {
        if (depth > 128 || ++nodes > 10000 || xml.length() > 4 * 1024 * 1024) {
            throw new IOException("The active window exceeds capture bounds");
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.append("<node");
        attribute(xml, "package", node.getPackageName());
        attribute(xml, "class", node.getClassName());
        attribute(xml, "resource-id", node.getViewIdResourceName());
        attribute(xml, "text", node.getText());
        attribute(xml, "content-desc", node.getContentDescription());
        attribute(xml, "hint", node.getHintText());
        attribute(xml, "bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]");
        attribute(xml, "visible-to-user", Boolean.toString(node.isVisibleToUser()));
        attribute(xml, "selected", Boolean.toString(node.isSelected()));
        attribute(xml, "enabled", Boolean.toString(node.isEnabled()));
        attribute(xml, "clickable", Boolean.toString(node.isClickable()));
        attribute(xml, "scrollable", Boolean.toString(node.isScrollable()));
        attribute(xml, "drawing-order", Integer.toString(node.getDrawingOrder()));
        xml.append('>');
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { appendNode(xml, child, depth + 1); }
            finally { child.recycle(); }
        }
        xml.append("</node>");
    }

    private static void attribute(StringBuilder xml, String key, CharSequence raw) {
        String value = raw == null ? "" : raw.toString();
        if (value.length() > 1024 * 1024) throw new IllegalArgumentException("An accessibility value exceeds capture bounds");
        xml.append(' ').append(key).append("=\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&': xml.append("&amp;"); break;
                case '<': xml.append("&lt;"); break;
                case '>': xml.append("&gt;"); break;
                case '"': xml.append("&quot;"); break;
                case '\r': xml.append("&#13;"); break;
                case '\n': xml.append("&#10;"); break;
                case '\t': xml.append("&#9;"); break;
                default:
                    // XML 1.0 rejects these control characters even as numeric entities.
                    if (character >= 0x20 && character != 0xfffe && character != 0xffff) xml.append(character);
            }
        }
        xml.append('"');
    }
}

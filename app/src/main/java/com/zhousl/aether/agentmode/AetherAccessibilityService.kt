package com.zhousl.aether.agentmode

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility service that enables [dumpUiTree] to capture the UI hierarchy
 * of apps running on Aether virtual displays.
 *
 * Android's uiautomator (shell command) runs under the calling app's UID and
 * can only traverse the view hierarchy of its own process.  On a virtual display
 * where the target app is a separate process, uiautomator returns nothing
 * useful.  This service uses [windowsForDisplay] (API 30+) to find the target
 * application window and dump its accessibility tree directly.
 */
class AetherAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No-op: we only need direct window-tree access, not event streaming.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AetherAccessibilityService connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "AetherAccessibilityService destroyed")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Dump the accessibility tree for every APPLICATION-type window on
     * [displayId] whose package does *not* start with [aetherPackage].
     *
     * Returns a JSON array of element objects compatible with the existing
     * `buildElementJson` format, so callers see the same schema regardless of
     * whether the tree came from uiautomator or the accessibility service.
     */
    fun dumpDisplayTree(
        displayId: Int,
        aetherPackage: String,
    ): JSONArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "windowsForDisplay requires API 30+")
            return JSONArray()
        }

        val result = JSONArray()
        val windows: List<AccessibilityWindowInfo> = windowsForDisplay(displayId)
        Log.i(TAG, "windowsForDisplay($displayId) returned ${windows.size} window(s)")

        for (window in windows) {
            try {
                val root = window.root ?: continue
                val windowPackage = root.packageName?.toString().orEmpty()
                val windowType = when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
                    else -> "unknown(${window.type})"
                }

                Log.i(TAG, "  window type=$windowType package=$windowPackage " +
                    "focused=${window.isFocused} active=${window.isActive}")

                // Skip Aether's own windows — we want the *target* app.
                if (windowPackage.lowercase().startsWith(aetherPackage.lowercase())) {
                    Log.i(TAG, "  -> skipping Aether window")
                    continue
                }

                // Only dump application windows.
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    Log.i(TAG, "  -> skipping non-application window")
                    continue
                }

                dumpNodeRecursive(root, result, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error dumping window: ${e.message}", e)
            } finally {
                window.recycle()
            }
        }

        Log.i(TAG, "dumpDisplayTree($displayId) -> ${result.length()} element(s)")
        return result
    }

    /**
     * Return diagnostic info about all windows visible to this service,
     * keyed by displayId.
     */
    fun listAllWindowsDiagnostics(): JSONObject {
        val root = JSONObject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.put("error", "API 30+ required")
            return root
        }
        try {
            val allWindows = windows
            root.put("total_windows", allWindows.size)
            val byDisplay = JSONObject()
            for (window in allWindows) {
                try {
                    val displayId = window.displayId
                    val key = displayId.toString()
                    var arr = byDisplay.optJSONArray(key)
                    if (arr == null) {
                        arr = JSONArray()
                        byDisplay.put(key, arr)
                    }
                    val w = JSONObject()
                    w.put("type", when (window.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
                        else -> "unknown(${window.type})"
                    })
                    w.put("focused", window.isFocused)
                    w.put("active", window.isActive)
                    w.put("layer", window.layer)
                    w.put("title", window.title?.toString().orEmpty())
                    val pkg = window.root?.packageName?.toString().orEmpty()
                    w.put("package", pkg)
                    arr.put(w)
                } finally {
                    window.recycle()
                }
            }
            root.put("windows_by_display", byDisplay)
        } catch (e: Exception) {
            root.put("error", e.message ?: "unknown")
        }
        return root
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun dumpNodeRecursive(
        node: AccessibilityNodeInfo,
        result: JSONArray,
        depth: Int,
    ) {
        if (depth > 64) return // safety limit

        val text = node.text?.toString().orEmpty()
        val contentDesc = node.contentDescription?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        val resourceId = node.viewIdResourceName.orEmpty()
        val packageName = node.packageName?.toString().orEmpty()
        val clickable = node.isClickable
        val focusable = node.isFocusable
        val isInteractive = clickable || focusable ||
            text.isNotBlank() || contentDesc.isNotBlank() ||
            className.contains("Button") || className.contains("EditText") ||
            className.contains("CheckBox") || className.contains("Switch") ||
            className.contains("ImageView") || className.contains("ImageButton")

        if (isInteractive) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val element = JSONObject().apply {
                put("text", text)
                put("content_desc", contentDesc)
                put("class", className)
                put("resource_id", resourceId)
                put("package_name", packageName)
                put("clickable", clickable)
                put("focusable", focusable)
                put("bounds", JSONObject().apply {
                    put("left", rect.left)
                    put("top", rect.top)
                    put("right", rect.right)
                    put("bottom", rect.bottom)
                })
                put("center_x", (rect.left + rect.right) / 2)
                put("center_y", (rect.top + rect.bottom) / 2)
            }
            result.put(element)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNodeRecursive(child, result, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "AetherAccessibility"

        @Volatile
        var instance: AetherAccessibilityService? = null
            private set

        /**
         * Non-blocking check — users must enable the service in
         * Settings → Accessibility → Aether before it will work.
         */
        val isAvailable: Boolean get() = instance != null
    }
}
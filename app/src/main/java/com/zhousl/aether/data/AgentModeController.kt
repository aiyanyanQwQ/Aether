package com.zhousl.aether.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.Display
import android.view.Surface
import androidx.core.content.getSystemService
import com.rosan.app_process.AppProcess
import com.zhousl.aether.agentmode.AetherAgentModeShizukuService
import com.zhousl.aether.agentmode.IAetherAgentModeService
import com.zhousl.aether.termux.TermuxBashTool
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

private const val FallbackAgentDisplayWidth = 720
private const val FallbackAgentDisplayHeight = 1280
private const val FallbackAgentDisplayDensityDpi = 320
private const val AgentDisplayName = "aether-agent-mode"
private const val AgentModeCaptureExtension = "jpg"
private const val AgentModeCaptureMimeType = "image/jpeg"
private const val AgentModeCaptureMaxEdge = 1280
private const val AgentModeCaptureJpegQuality = 85
private const val ShizukuPermissionRequestCode = 4201
private const val RootAuthorizationProbeTimeoutMillis = 2_000L
private const val ShizukuUserServiceBindTimeoutMillis = 20_000L
private const val ShizukuUserServiceTag = "aether-agent-mode"
private const val ShizukuUserServiceVersion = 1

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

data class AgentModeDisplayState(
    val isActive: Boolean = false,
    val displayId: Int? = null,
    val width: Int = FallbackAgentDisplayWidth,
    val height: Int = FallbackAgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val cursorX: Int? = null,
    val cursorY: Int? = null,
    val cursorAnimationDurationMillis: Int = 220,
    val isLivePreviewActive: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

data class AgentModeInstalledAppInfo(
    val packageName: String,
    val appName: String,
    val activityName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
)

enum class AgentModeAuthorizationIssue {
    Disabled,
    Ready,
    ShizukuNotInstalled,
    ShizukuNotRunning,
    ShizukuPermissionMissing,
    ShizukuPermissionDenied,
    RootUnavailable,
    RootPermissionMissing,
    RootPermissionDenied,
    Error,
}

data class AgentModeAuthorizationState(
    val issue: AgentModeAuthorizationIssue = AgentModeAuthorizationIssue.Disabled,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == AgentModeAuthorizationIssue.Ready
}

class AgentModeController(
    private val context: Context,
    private val bashTool: TermuxBashTool,
    private val workspaceFileBridge: WorkspaceFileBridge,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val displayManager = context.getSystemService<DisplayManager>()!!
    private val cacheDirectory = File(context.cacheDir, "agent-mode").apply { mkdirs() }
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val shizukuServiceMutex = Mutex()
    private val _displayState = MutableStateFlow(AgentModeDisplayState())
    private val _authorizationState = MutableStateFlow(AgentModeAuthorizationState())
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuPermissionRequestCode) {
                AetherAnalytics.capture(
                    event = "permission result",
                    properties = mapOf(
                        "permission" to "shizuku",
                        "source" to "agent_mode_authorization",
                        "granted" to (grantResult == PackageManager.PERMISSION_GRANTED),
                        "result" to if (grantResult == PackageManager.PERMISSION_GRANTED) "granted" else "denied",
                    ),
                )
                _authorizationState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_granted",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.Ready,
                        detail = "Shizuku permission is granted.",
                    )
                } else {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_denied",
                        level = "warn",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.ShizukuPermissionDenied,
                        detail = "Shizuku permission was denied. Grant Aether permission in Shizuku before using Agent Mode.",
                    )
                }
            }
        }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (_authorizationState.value.issue != AgentModeAuthorizationIssue.Disabled) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku stopped. Start Shizuku, then refresh Agent Mode status.",
            )
        }
        clearShizukuService("Shizuku stopped. Agent Mode virtual display was reset.")
    }

    private var shizukuDisplayId: Int? = null
    private var displayOwnerMethod: AgentModeAuthorizationMethod? = null
    private var displayOwnerBinder: IBinder? = null
    private var shizukuService: IAetherAgentModeService? = null
    private var shizukuServiceArgs: Shizuku.UserServiceArgs? = null
    private var shizukuServiceConnection: ServiceConnection? = null
    private var rootService: IAetherAgentModeService? = null
    private var rootProcess: AppProcess.Terminal? = null
    @Volatile
    private var previewSurface: Surface? = null
    // When true, Ensures that the preview surface stays detached so launched
    // apps can render on the virtual display without Aether's composable overlay.
    private var previewDetachedForApp: Boolean = false
    private var lastLaunchedPackageForDisplay: String? = null
    // Freeform mode: component name for focus switching during input
    private var lastLaunchedComponent: String? = null
    // Cached Aether launcher component for returning focus after freeform input
    private val aetherLauncherComponent: String by lazy {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Cannot resolve Aether launcher activity.")
        intent.component?.flattenToString()
            ?: error("Cannot resolve Aether launcher component.")
    }

    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()
    val authorizationState: StateFlow<AgentModeAuthorizationState> = _authorizationState.asStateFlow()

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        runCatching {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }
    }

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        argumentsJson: String,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.agentModeAuthorizationEnabled) {
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = "authorization_disabled",
                message = "Agent Mode is not authorized.",
            )
            return@withContext JSONObject().apply {
                put("ok", false)
                put("errmsg", "Agent Mode is not authorized. Enable it in Settings > Agent Mode first.")
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = "unknown",
                    reason = "invalid_arguments",
                    message = "Arguments were not valid JSON.",
                )
            }
        val action = arguments.optString("action").trim().lowercase()
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_start",
            details = mapOf(
                "action" to action.ifBlank { "unknown" },
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )

        runCatching {
            when (action) {
            "start" -> {
                ensureDisplay(settings)
                captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
            }
            "status" -> statusResult(settings)
            "list_apps", "apps", "installed_apps" -> listInstalledAppsResult(settings, arguments)
            "launch" -> {
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else if (settings.agentModeFreeform) {
                    launchFreeform(settings, target)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 900)
                } else {
                    ensureDisplay(settings)
                    detachPreviewForLaunch(settings)
                    launchTarget(settings, target)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 900)
                }
            }
            "tap" -> {
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else if (settings.agentModeFreeform) {
                    freeformInput(settings, "tap $x $y")
                    updateCursorPosition(x, y, animationDurationMillis = 180)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                } else {
                    val displayId = ensureDisplay(settings)
                    requireAgentModeService(settings).tap(displayId, x, y)
                    updateCursorPosition(x, y, animationDurationMillis = 180)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                }
            }
            "swipe" -> {
                val x1 = normalizedX(arguments.optDouble("x1", Double.NaN))
                val y1 = normalizedY(arguments.optDouble("y1", Double.NaN))
                val x2 = normalizedX(arguments.optDouble("x2", Double.NaN))
                val y2 = normalizedY(arguments.optDouble("y2", Double.NaN))
                val durationMs = arguments.optInt("duration_ms", arguments.optInt("durationMs", 500))
                    .coerceIn(50, 10_000)
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    invalidArguments("x1, y1, x2, and y2 are required, using 0..1000 screen coordinates.")
                } else if (settings.agentModeFreeform) {
                    updateCursorPosition(x1, y1, animationDurationMillis = 80)
                    controllerScope.launch {
                        delay(40)
                        updateCursorPosition(x2, y2, animationDurationMillis = durationMs)
                    }
                    freeformInput(settings, "swipe $x1 $y1 $x2 $y2 $durationMs")
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = durationMs.toLong() + 250)
                } else {
                    val displayId = ensureDisplay(settings)
                    updateCursorPosition(x1, y1, animationDurationMillis = 80)
                    controllerScope.launch {
                        delay(40)
                        updateCursorPosition(x2, y2, animationDurationMillis = durationMs)
                    }
                    requireAgentModeService(settings).swipe(displayId, x1, y1, x2, y2, durationMs)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = durationMs.toLong() + 250)
                }
            }
            "key" -> {
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("Missing required 'key' argument.")
                } else if (settings.agentModeFreeform) {
                    freeformInput(settings, "keyevent $keyCode")
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 300)
                } else {
                    val displayId = ensureDisplay(settings)
                    requireAgentModeService(settings).key(displayId, keyCode)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 300)
                }
            }
            "text" -> {
                val text = arguments.optString("text")
                if (text.isBlank()) {
                    invalidArguments("Missing required 'text' argument.")
                } else if (settings.agentModeFreeform) {
                    // Use a temp approach: replace %s in a base64 command
                    val b64 = android.util.Base64.encodeToString(
                        text.toByteArray(), android.util.Base64.NO_WRAP
                    )
                    freeformInput(settings, "text \"\$(echo $b64 | base64 -d)\"")
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                } else {
                    val displayId = ensureDisplay(settings)
                    requireAgentModeService(settings).text(displayId, text)
                    captureAfterDelay(settings, workspaceDirectory, delayMillis = 350)
                }
            }
            "screenshot" -> {
                if (!settings.agentModeFreeform) {
                    ensureDisplay(settings)
                }
                captureAfterDelay(settings, workspaceDirectory, delayMillis = 0)
            }
            "dump_ui_tree", "ui_tree", "elements" -> {
                if (settings.agentModeFreeform) {
                    freeformDumpUiTree(settings)
                } else {
                    val displayId = ensureDisplay(settings)
                    val elements = dumpUiTreeWithPreviewHidden(settings, displayId)
                    JSONObject().apply {
                        put("ok", true)
                        put("display_id", displayId)
                        put("target_package", lastLaunchedPackageForDisplay)
                        put("ui_tree_elements", elements)
                        put("accessibility_windows", accessibilityWindowDiagnostics())
                        put("dumpsys_window_diagnostics", runCatching {
                            requireAgentModeService(settings).listWindowsDiagnosticsJson()
                                .let { JSONObject(it) }
                        }.getOrElse { JSONObject().apply { put("error", it.message) } })
                        put("stdout", "UI tree dumped for display $displayId with ${elements.length()} interactive elements.")
                    }.toString()
                }
            }
            "stop" -> {
                releaseDisplay()
                JSONObject().apply {
                    put("ok", true)
                    put("stdout", "Agent Mode virtual display stopped.")
                }.toString()
            }
            else -> invalidArguments("Unsupported action '$action'.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }.also {
                val result = runCatching { JSONObject(it) }.getOrNull()
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "action_end",
                    level = if (result?.optBoolean("ok", true) == false) "warn" else "info",
                    details = mapOf(
                        "action" to action.ifBlank { "unknown" },
                        "ok" to (result?.optBoolean("ok", true) ?: true),
                        "display_id" to _displayState.value.displayId,
                        "display_active" to _displayState.value.isActive,
                        "message" to result?.optString("errmsg").orEmpty(),
                    ),
                )
            }
        }.getOrElse { throwable ->
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = "exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
            toolError(
                message = throwable.message ?: throwable.javaClass.simpleName,
                action = action,
            )
        }
    }

    suspend fun refreshAuthorization(settings: AppSettings) {
        if (
            shizukuDisplayId != null &&
            displayOwnerMethod != null &&
            displayOwnerMethod != settings.agentModeAuthorizationMethod
        ) {
            releaseDisplay("Virtual display reset because Agent Mode authorization method changed.")
        }
        _authorizationState.value = inspectAuthorization(settings).also { state ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "authorization_refreshed",
                level = if (state.isReady || state.issue == AgentModeAuthorizationIssue.Disabled) "info" else "warn",
                details = mapOf(
                    "issue" to state.issue.name,
                    "detail" to state.detail,
                    "method" to settings.agentModeAuthorizationMethod.storageValue,
                    "enabled" to settings.agentModeAuthorizationEnabled,
                ),
            )
        }
    }

    fun requestShizukuPermission(): AgentModeAuthorizationState {
        val current = inspectShizukuAuthorization()
        if (current.issue == AgentModeAuthorizationIssue.Ready) {
            _authorizationState.value = current
            return current
        }
        if (
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionMissing &&
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionDenied
        ) {
            _authorizationState.value = current
            return current
        }

        return runCatching {
            AetherAnalytics.capture(
                event = "permission requested",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "current_issue" to current.issue.name.lowercase(),
                ),
            )
            Shizuku.requestPermission(ShizukuPermissionRequestCode)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                detail = "Confirm the Shizuku permission prompt, then refresh Agent Mode status.",
            )
        }.getOrElse { throwable ->
            AetherAnalytics.capture(
                event = "permission result",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "granted" to false,
                    "result" to "request_failed",
                    "error" to (throwable.message ?: throwable.javaClass.simpleName),
                ),
            )
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Failed to request Shizuku permission.",
            )
        }.also { _authorizationState.value = it }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int {
        val service = requireAgentModeService(settings)
        val serviceBinder = service.asBinder()
        shizukuDisplayId?.let { displayId ->
            if (isCurrentDisplayOwner(settings, serviceBinder)) {
                return displayId
            }
            releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        }
        val displaySpec = currentDeviceDisplaySpec()
        val displayId = service.createOwnedDisplay(
            AgentDisplayName,
            displaySpec.width,
            displaySpec.height,
            displaySpec.densityDpi,
        )
        shizukuDisplayId = displayId
        displayOwnerMethod = settings.agentModeAuthorizationMethod
        displayOwnerBinder = serviceBinder
        diagnosticLogger.event(
            category = "agent_mode",
            event = "display_created",
            details = mapOf(
                "display_id" to displayId,
                "width" to displaySpec.width,
                "height" to displaySpec.height,
                "density_dpi" to displaySpec.densityDpi,
                "method" to settings.agentModeAuthorizationMethod.storageValue,
            ),
        )
        _displayState.value = AgentModeDisplayState(
            isActive = true,
            displayId = displayId,
            width = displaySpec.width,
            height = displaySpec.height,
            displays = currentDisplays(settings, displayId),
            status = "${settings.agentModeAuthorizationMethod.displayName} virtual display ready",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        if (!previewDetachedForApp) {
            attachCurrentPreviewSurface(settings, displayId)
        }
        return displayId
    }

    fun stopDisplay() {
        releaseDisplay()
    }

    suspend fun attachPreviewSurface(settings: AppSettings, surface: Surface) = withContext(Dispatchers.IO) {
        previewSurface = surface
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        attachCurrentPreviewSurface(settings, displayId)
    }

    suspend fun detachPreviewSurface(settings: AppSettings, surface: Surface) = withContext(Dispatchers.IO) {
        if (previewSurface !== surface) return@withContext
        previewSurface = null
        val displayId = currentManagedDisplayId(settings) ?: run {
            _displayState.value = _displayState.value.copy(
                isLivePreviewActive = false,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
            return@withContext
        }
        runCatching {
            requireAgentModeService(settings).detachPreviewSurface(displayId)
        }
        val state = _displayState.value
        _displayState.value = state.copy(
            isLivePreviewActive = false,
            status = if (state.isActive) {
                "${settings.agentModeAuthorizationMethod.displayName} virtual display ready"
            } else {
                state.status
            },
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Detach the preview surface and set a persistent flag so that
     * [ensureDisplay] does not reattach it on subsequent actions.
     * This lets launched apps take over the virtual display rendering.
     */
    private suspend fun detachPreviewForLaunch(settings: AppSettings) {
        val displayId = currentManagedDisplayId(settings) ?: return
        val surface = previewSurface ?: return
        previewSurface = null
        previewDetachedForApp = true
        runCatching {
            requireAgentModeService(settings).detachPreviewSurface(displayId)
        }
        val state = _displayState.value
        _displayState.value = state.copy(
            isLivePreviewActive = false,
            status = "App launched on virtual display $displayId",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        _displayState.value = state.copy(
            displays = currentDisplays(settings, state.displayId),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun attachCurrentPreviewSurface(settings: AppSettings, displayId: Int) {
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        runCatching {
            requireAgentModeService(settings).attachPreviewSurface(displayId, surface)
        }.onSuccess {
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = true,
                    status = "Streaming virtual display",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }.onFailure { throwable ->
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = false,
                    status = throwable.message ?: "Live preview surface is not available.",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ) {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(settings, target)
            ?: error("No launchable app matched '$target'. Try a package name such as com.android.chrome, or a shorter app label.")
        requireAgentModeService(settings).launchPackage(launchPackage, displayId)
        lastLaunchedPackageForDisplay = launchPackage
    }

    // ── Freeform mode helpers ───────────────────────────────────────────

    private suspend fun launchFreeform(
        settings: AppSettings,
        target: String,
    ) {
        val launchPackage = resolveLaunchPackage(settings, target)
            ?: error("No launchable app matched '$target'.")
        val intent = context.packageManager.getLaunchIntentForPackage(launchPackage)
            ?: error("No launchable activity for $launchPackage.")
        val component = intent.component?.flattenToString()
            ?: error("Cannot resolve component for $launchPackage.")
        lastLaunchedPackageForDisplay = launchPackage
        lastLaunchedComponent = component
        // Launch in freeform windowing mode on the main display.
        requireAgentModeService(settings).runInputCommand(
            "am start --windowingMode 5 -n $component"
        )
    }

    /**
     * Execute an [input] shell command on the main display, temporarily
     * switching focus to the freeform target app so the input event lands
     * on the correct window, then returning focus to Aether.
     */
    private suspend fun freeformInput(
        settings: AppSettings,
        inputCmd: String,
    ) {
        val component = lastLaunchedComponent
            ?: error("No app launched yet. Use launch first.")
        // Escape single quotes in the input command by wrapping in double quotes.
        // The inputCmd is already built by the action handlers (e.g. "tap 500 800").
        val escapedCmd = inputCmd.replace("'", "'\\''")
        // Focus target → input → focus Aether back.  The semicolon-chained
        // shell commands keep the total latency around 200-400 ms per action.
        requireAgentModeService(settings).runInputCommand(
            "am start --windowingMode 5 -n $component ; sleep 0.08 ; input $escapedCmd ; am start -n $aetherLauncherComponent"
        )
    }

    /** Dump the UI tree from the main display (display 0) and filter to the target app. */
    private suspend fun freeformDumpUiTree(settings: AppSettings): String {
        // dumpUiTree(0) uses uiautomator dump --display 0 which captures all
        // windows on the main display (including freeform apps).
        val rawElements = JSONArray(
            requireAgentModeService(settings).dumpUiTree(0)
        )
        val filtered = filterUiTreeElements(rawElements, lastLaunchedPackageForDisplay)
        return JSONObject().apply {
            put("ok", true)
            put("mode", "freeform")
            put("display_id", 0)
            put("target_package", lastLaunchedPackageForDisplay)
            put("ui_tree_elements", filtered)
            put("stdout", "UI tree dumped from main display (freeform mode) with ${filtered.length()} interactive elements.")
        }.toString()
    }

    private suspend fun dumpUiTreeWithPreviewHidden(
        settings: AppSettings,
        displayId: Int,
    ): JSONArray {
        val service = requireAgentModeService(settings)
        val shouldRestorePreview = !previewDetachedForApp && previewSurface?.isValid == true
        if (shouldRestorePreview) {
            runCatching { service.detachPreviewSurface(displayId) }
            delay(150)
        }
        val rawElements = try {
            // Priority 1: UiAutomation.getWindowsOnAllDisplays() via reflection.
            // This is the ONLY reliable way to get UI trees from virtual-display
            // apps because uiautomator dump and AccessibilityService both miss them.
            val uiAutomationResult = runCatching {
                JSONArray(service.dumpUiTreeViaUiAutomation(displayId))
            }.getOrNull()
            if (uiAutomationResult != null && uiAutomationResult.length() > 0) {
                val hasOnlyError = uiAutomationResult.length() == 1 &&
                    uiAutomationResult.optJSONObject(0)?.has("_error") == true
                if (!hasOnlyError) {
                    uiAutomationResult
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val finalElements = if (rawElements != null) {
            rawElements
        } else {
            // Priority 2: AccessibilityService
            val a11yResult = dumpUiTreeViaAccessibility(displayId)
            if (a11yResult.length() > 0) {
                a11yResult
            } else {
                // Priority 3 (last resort): uiautomator dump
                JSONArray(service.dumpUiTree(displayId))
            }
        }

        return try {
            filterUiTreeElements(finalElements, lastLaunchedPackageForDisplay)
        } finally {
            if (shouldRestorePreview) {
                runCatching { attachCurrentPreviewSurface(settings, displayId) }
            }
        }
    }

    /**
     * Use the [AetherAccessibilityService] to get the target app's UI tree
     * on the given virtual display.
     *
     * Returns a JSONArray (possibly empty if the service is unavailable or
     * no matching windows were found).
     */
    private fun dumpUiTreeViaAccessibility(displayId: Int): JSONArray {
        val a11y = com.zhousl.aether.agentmode.AetherAccessibilityService.instance
            ?: return JSONArray()
        return a11y.dumpDisplayTree(displayId, context.packageName)
    }

    /**
     * Return diagnostic info about all windows visible to the accessibility
     * service, for inclusion in [dump_ui_tree] tool output.
     */
    private fun accessibilityWindowDiagnostics(): JSONObject {
        val a11y = com.zhousl.aether.agentmode.AetherAccessibilityService.instance
            ?: return JSONObject().apply {
                put("available", false)
                put("message", "AetherAccessibilityService is not running. Enable it in Settings → Accessibility → Aether.")
            }
        return a11y.listAllWindowsDiagnostics().also {
            it.put("available", true)
        }
    }

    private fun filterUiTreeElements(
        elements: JSONArray,
        targetPackage: String?,
    ): JSONArray {
        val appPackage = context.packageName.lowercase()
        val normalizedTarget = targetPackage?.trim()?.lowercase().orEmpty()
        val matchingTarget = JSONArray()
        val nonAether = JSONArray()
        val original = JSONArray()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            original.put(element)
            val elementPackage = element.optString("package_name").trim().lowercase()
            if (normalizedTarget.isNotBlank() && elementPackage == normalizedTarget) {
                matchingTarget.put(element)
            }
            if (!elementPackage.startsWith(appPackage)) {
                nonAether.put(element)
            }
        }
        return when {
            matchingTarget.length() > 0 -> matchingTarget
            nonAether.length() > 0 -> nonAether
            else -> original
        }
    }

    private suspend fun resolveLaunchPackage(
        settings: AppSettings,
        target: String,
    ): String? {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget.isBlank()) return null
        context.packageManager.getLaunchIntentForPackage(target)?.let { return target }

        val launchables = currentInstalledApps(settings)
        val tokens = normalizedTarget.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("app", "browser", "managed") }
        return launchables.firstOrNull { app ->
            app.packageName.equals(normalizedTarget, ignoreCase = true) ||
                app.appName.equals(target, ignoreCase = true)
        }?.packageName ?: launchables.firstOrNull { app ->
            app.packageName.lowercase().contains(normalizedTarget) ||
                app.appName.lowercase().contains(normalizedTarget) ||
                app.activityName.lowercase().contains(normalizedTarget) ||
                tokens.any { token ->
                    app.packageName.lowercase().contains(token) ||
                        app.appName.lowercase().contains(token) ||
                        app.activityName.lowercase().contains(token)
                }
        }?.packageName ?: target.takeIf {
            it.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+"""))
        }
    }

    private suspend fun listInstalledAppsResult(
        settings: AppSettings,
        arguments: JSONObject,
    ): String {
        val query = arguments.optString("query").trim()
        val normalizedQuery = query.lowercase()
        val includeSystem = arguments.optBoolean(
            "include_system",
            arguments.optBoolean("includeSystem", true),
        )
        val maxResults = arguments.optInt(
            "max_results",
            arguments.optInt("maxResults", 500),
        ).coerceIn(1, 1_000)
        val apps = currentInstalledApps(settings)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.packageName.lowercase().contains(normalizedQuery) ||
                    app.appName.lowercase().contains(normalizedQuery) ||
                    app.activityName.lowercase().contains(normalizedQuery)
            }
            .toList()
        val visibleApps = apps.take(maxResults)
        return JSONObject().apply {
            put("ok", true)
            put("count", apps.size)
            put("truncated", apps.size > visibleApps.size)
            put(
                "apps",
                JSONArray().apply {
                    visibleApps.forEach { app ->
                        put(
                            JSONObject().apply {
                                put("app_name", app.appName)
                                put("package_name", app.packageName)
                                put("activity_name", app.activityName)
                                put("enabled", app.isEnabled)
                                put("system", app.isSystemApp)
                                put("launchable", true)
                            }
                        )
                    }
                },
            )
            put(
                "stdout",
                buildString {
                    append("Found ")
                    append(apps.size)
                    append(if (includeSystem) " launchable apps." else " non-system launchable apps.")
                    if (apps.size > visibleApps.size) {
                        append(" Showing ")
                        append(visibleApps.size)
                        append(".")
                    }
                    visibleApps.forEach { app ->
                        append('\n')
                        append(app.appName)
                        append(" -> ")
                        append(app.packageName)
                    }
                },
            )
        }.toString()
    }

    private suspend fun currentInstalledApps(settings: AppSettings): List<AgentModeInstalledAppInfo> {
        val privilegedApps = runCatching {
            parseInstalledApps(requireAgentModeService(settings).listInstalledAppsJson())
        }.getOrNull()
        return (privilegedApps?.takeIf { it.isNotEmpty() } ?: currentInstalledAppsLocal())
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.packageName }))
    }

    @Suppress("DEPRECATION")
    private fun currentInstalledAppsLocal(): List<AgentModeInstalledAppInfo> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            AgentModeInstalledAppInfo(
                packageName = activityInfo.packageName.orEmpty(),
                appName = info.loadLabel(packageManager).toString(),
                activityName = activityInfo.name.orEmpty(),
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isEnabled = activityInfo.enabled && applicationInfo.enabled,
            )
        }
    }

    private fun parseInstalledApps(rawValue: String): List<AgentModeInstalledAppInfo> {
        val array = JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("package_name").ifBlank {
                    item.optString("packageName")
                }
                if (packageName.isBlank()) continue
                add(
                    AgentModeInstalledAppInfo(
                        packageName = packageName,
                        appName = item.optString("app_name").ifBlank {
                            item.optString("appName").ifBlank { packageName }
                        },
                        activityName = item.optString("activity_name").ifBlank {
                            item.optString("activityName")
                        },
                        isSystemApp = item.optBoolean("system"),
                        isEnabled = item.optBoolean("enabled", true),
                    )
                )
            }
        }
    }

    private suspend fun captureAfterDelay(
        settings: AppSettings,
        workspaceDirectory: String,
        delayMillis: Long,
    ): String {
        if (delayMillis > 0) delay(delayMillis)
        val freeform = settings.agentModeFreeform
        val displayId = if (freeform) 0 else currentManagedDisplayId(settings)
        val state = _displayState.value
        val uiTreeOnly = settings.agentModeUiTreeOnly

        if (uiTreeOnly) {
            // UI-tree-only path: skip the expensive screenshot capture entirely.
            val elements = if (freeform) {
                JSONArray(requireAgentModeService(settings).dumpUiTree(0))
                    .let { filterUiTreeElements(it, lastLaunchedPackageForDisplay) }
            } else {
                dumpUiTreeWithPreviewHidden(settings, displayId!!)
            }
            _displayState.value = state.copy(
                isActive = true,
                displayId = displayId,
                displays = currentDisplays(settings, displayId),
                lastUpdatedMillis = System.currentTimeMillis(),
                status = if (freeform) "Freeform UI tree" else "Captured virtual display",
            )
            return JSONObject().apply {
                put("ok", true)
                put("display_id", displayId)
                put("mode", if (freeform) "freeform" else "virtual_display")
                put("target_package", lastLaunchedPackageForDisplay)
                put("width", state.width)
                put("height", state.height)
                state.cursorX?.let { put("cursor_x", it) }
                state.cursorY?.let { put("cursor_y", it) }
                put("ui_tree_elements", elements)
                put("stdout", "Captured ${if (freeform) "main display (freeform)" else "virtual display $displayId"} UI tree with " +
                    "${elements.length()} interactive elements (UI-tree-only mode).")
            }.toString()
        }

        // Screenshot path
        val captureId = "capture-${System.currentTimeMillis()}"
        val previewFile = File(cacheDirectory, "$captureId.$AgentModeCaptureExtension")
        captureImageFile(settings, previewFile, displayId)
        if (!previewFile.isFile || previewFile.length() <= 0L) {
            error("Agent Mode screenshot capture produced an empty file.")
        }
        val bytes = previewFile.readBytes()
        val latestPreviewFile = File(cacheDirectory, "latest.$AgentModeCaptureExtension")
        previewFile.copyTo(latestPreviewFile, overwrite = true)
        val previewPath = previewFile.absolutePath
        val workspacePath = "$workspaceDirectory/agent-mode/$captureId.$AgentModeCaptureExtension"
        workspaceFileBridge.writeWorkspaceBytes(
            absolutePath = workspacePath,
            bytes = bytes,
        ).getOrThrow()
        _displayState.value = state.copy(
            isActive = displayId != null,
            displayId = displayId,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = previewPath,
            latestWorkspacePath = workspacePath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = if (freeform) "Captured main display" else "Captured virtual display",
        )
        return JSONObject().apply {
            put("ok", true)
            put("display_id", displayId)
            put("mode", if (freeform) "freeform" else "virtual_display")
            put("width", state.width)
            put("height", state.height)
            put("screenshot_path", workspacePath)
            put("preview_path", previewPath)
            state.cursorX?.let { put("cursor_x", it) }
            state.cursorY?.let { put("cursor_y", it) }
            put("screenshot_mime_type", AgentModeCaptureMimeType)
            put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("stdout", "Captured Agent Mode screenshot: $workspacePath")
        }.toString()
    }

    private fun updateCursorPosition(
        x: Int,
        y: Int,
        animationDurationMillis: Int = 220,
    ) {
        val state = _displayState.value
        _displayState.value = state.copy(
            cursorX = x.coerceIn(0, state.width),
            cursorY = y.coerceIn(0, state.height),
            cursorAnimationDurationMillis = animationDurationMillis.coerceIn(80, 1_200),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun captureImageFile(
        settings: AppSettings,
        outputFile: File,
        displayId: Int = currentManagedDisplayId(settings) ?: ensureDisplay(settings),
    ) {
        captureMutex.withLock {
            outputFile.parentFile?.mkdirs()
            runCatching { outputFile.delete() }
            if (displayId == 0 && settings.agentModeFreeform) {
                // Use screencap via root for the physical display (freeform mode).
                val tmpPath = "/data/local/tmp/aether_capture_$$.png"
                requireAgentModeService(settings).runInputCommand(
                    "screencap -p $tmpPath && chmod 644 $tmpPath"
                )
                delay(80)
                val tmpFile = File(tmpPath)
                try {
                    tmpFile.copyTo(outputFile, overwrite = true)
                } finally {
                    runCatching { tmpFile.delete() }
                }
            } else {
                try {
                    ParcelFileDescriptor.open(
                        outputFile,
                        ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    ).use { descriptor ->
                        requireAgentModeService(settings).captureImageToFd(
                            displayId,
                            descriptor,
                            AgentModeCaptureMaxEdge,
                            AgentModeCaptureJpegQuality,
                        )
                    }
                } catch (throwable: Throwable) {
                    runCatching { outputFile.delete() }
                    throw throwable
                }
            }
        }
    }

    private suspend fun statusResult(settings: AppSettings): String {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        val displays = currentDisplays(settings, state.displayId)
        _displayState.value = state.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("active", state.isActive)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put("live_preview", state.isLivePreviewActive)
            put(
                "displays",
                org.json.JSONArray().apply {
                    displays.forEach { display ->
                        put(
                            JSONObject().apply {
                                put("display_id", display.displayId)
                                put("name", display.name)
                                put("width", display.width)
                                put("height", display.height)
                                put("is_aether_display", display.isAetherDisplay)
                            }
                        )
                    }
                },
            )
            put("screenshot_path", state.latestWorkspacePath)
            put("stdout", if (state.isActive) "Agent Mode display is active." else "Agent Mode display is stopped.")
        }.toString()
    }

    private fun releaseDisplay(status: String = "Virtual display stopped") {
        shizukuDisplayId?.let { displayId ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "display_released",
                details = mapOf(
                    "display_id" to displayId,
                    "method" to displayOwnerMethod?.storageValue.orEmpty(),
                    "status" to status,
                ),
            )
            when (displayOwnerMethod) {
                AgentModeAuthorizationMethod.Shizuku -> runCatching { shizukuService?.releaseDisplay(displayId) }
                AgentModeAuthorizationMethod.Root -> runCatching { rootService?.releaseDisplay(displayId) }
                null -> {
                    runCatching { shizukuService?.releaseDisplay(displayId) }
                    runCatching { rootService?.releaseDisplay(displayId) }
                }
            }
        }
        shizukuDisplayId = null
        displayOwnerMethod = null
        displayOwnerBinder = null
        previewDetachedForApp = false
        lastLaunchedPackageForDisplay = null
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            displays = currentDisplaysLocal(null),
            status = status,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun currentManagedDisplayId(settings: AppSettings): Int? {
        val displayId = shizukuDisplayId ?: return null
        val service = runCatching { requireAgentModeService(settings) }
            .getOrElse { throwable ->
                if (displayOwnerMethod == settings.agentModeAuthorizationMethod) {
                    releaseDisplay(
                        throwable.message ?: "Virtual display reset because Agent Mode service is unavailable."
                    )
                }
                return null
            }
        if (isCurrentDisplayOwner(settings, service.asBinder())) {
            return displayId
        }
        releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        return null
    }

    private fun isCurrentDisplayOwner(
        settings: AppSettings,
        binder: IBinder,
    ): Boolean =
        displayOwnerMethod == settings.agentModeAuthorizationMethod &&
            displayOwnerBinder === binder &&
            binder.isBinderAlive

    private fun clearShizukuService(displayStatus: String) {
        shizukuService = null
        shizukuServiceArgs = null
        shizukuServiceConnection = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Shizuku) {
            releaseDisplay(displayStatus)
        }
    }

    private fun clearRootService(displayStatus: String) {
        rootService = null
        rootProcess = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Root) {
            releaseDisplay(displayStatus)
        }
    }

    private fun captureAgentModeFailed(
        settings: AppSettings,
        action: String,
        reason: String,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_failed",
            level = "warn",
            details = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message,
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
        AetherAnalytics.capture(
            event = "agent mode failed",
            properties = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message.take(280),
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
    }

    private suspend fun currentDisplays(
        settings: AppSettings,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val privilegedDisplays = runCatching {
            parseDisplays(requireAgentModeService(settings).listDisplaysJson(), aetherDisplayId)
        }.onFailure {
        }.getOrNull()
        if (privilegedDisplays != null) return privilegedDisplays
        return currentDisplaysLocal(aetherDisplayId)
    }

    private fun currentDisplaysLocal(aetherDisplayId: Int?): List<AgentModeDisplayInfo> =
        displayManager.displays.map { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            AgentModeDisplayInfo(
                displayId = display.displayId,
                name = display.name.orEmpty(),
                width = display.mode?.physicalWidth ?: size.x,
                height = display.mode?.physicalHeight ?: size.y,
                isAetherDisplay = display.displayId == aetherDisplayId ||
                    display.name.orEmpty().contains(AgentDisplayName, ignoreCase = true),
            )
        }.sortedBy { it.displayId }

    private fun parseDisplays(
        rawValue: String,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val array = org.json.JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val displayId = item.optInt("display_id")
                add(
                    AgentModeDisplayInfo(
                        displayId = displayId,
                        name = item.optString("name"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        isAetherDisplay = item.optBoolean("is_aether_display") ||
                            displayId == aetherDisplayId ||
                            item.optString("name").contains(AgentDisplayName, ignoreCase = true),
                    )
                )
            }
        }.sortedBy { it.displayId }
    }

    private suspend fun requireAgentModeService(settings: AppSettings): IAetherAgentModeService =
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> requireShizukuService()
            AgentModeAuthorizationMethod.Root -> requireRootService()
        }

    private suspend fun requireRootService(): IAetherAgentModeService = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withContext existing
            }
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
            error("No su binary was detected on this device.")
        }
        val process = object : AppProcess.Terminal() {
            override fun newTerminal(): List<String?> = listOf(suPath)
        }
        if (!process.init(context)) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = "Root Agent Mode service failed to start. Check that su can be granted to Aether.",
            )
            error("Root Agent Mode service failed to start. Check that su can be granted to Aether.")
        }
        val binder = process.serviceBinder(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
        val service = IAetherAgentModeService.Stub.asInterface(binder)
            ?: error("Root Agent Mode service returned an invalid binder.")
        rootProcess = process
        rootService = service
        _authorizationState.value = AgentModeAuthorizationState(
            issue = AgentModeAuthorizationIssue.Ready,
            detail = "Root Agent Mode service is connected.",
        )
        service
    }

    private suspend fun requireShizukuService(): IAetherAgentModeService = shizukuServiceMutex.withLock {
        val existing = shizukuService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withLock existing
            }
            clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
        }
        if (!Shizuku.pingBinder()) {
            error("Shizuku is not running.")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Aether does not have Shizuku permission. Grant it in Shizuku first.")
        }
        val deferred = CompletableDeferred<IAetherAgentModeService>()
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
            .processNameSuffix("agentmode")
            .tag(ShizukuUserServiceTag)
            .version(ShizukuUserServiceVersion)
            .daemon(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bound = IAetherAgentModeService.Stub.asInterface(service)
                if (shizukuServiceConnection !== this) return
                shizukuService = bound
                if (bound == null) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(
                            IllegalStateException("Shizuku Agent Mode service returned an invalid binder.")
                        )
                    }
                } else if (!deferred.isCompleted) {
                    deferred.complete(bound)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (shizukuServiceConnection === this) {
                    clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
                }
            }
        }
        shizukuServiceArgs = args
        shizukuServiceConnection = connection
        diagnosticLogger.event(
            category = "agent_mode",
            event = "shizuku_service_bind_start",
            details = mapOf(
                "timeout_ms" to ShizukuUserServiceBindTimeoutMillis,
                "tag" to ShizukuUserServiceTag,
                "version" to ShizukuUserServiceVersion,
            ),
        )
        val bindStartMillis = System.currentTimeMillis()
        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onSuccess {
            diagnosticLogger.event(
                category = "agent_mode",
                event = "shizuku_service_bind_dispatched",
                details = mapOf(
                    "duration_ms" to (System.currentTimeMillis() - bindStartMillis),
                ),
            )
        }.onFailure { throwable ->
            if (shizukuServiceConnection === connection) {
                shizukuService = null
                shizukuServiceArgs = null
                shizukuServiceConnection = null
            }
            throw throwable
        }
        return@withLock try {
            withTimeout(ShizukuUserServiceBindTimeoutMillis) { deferred.await() }.also {
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "shizuku_service_bound",
                )
            }
        } catch (throwable: TimeoutCancellationException) {
            if (shizukuServiceConnection === connection) {
                shizukuService = null
                shizukuServiceArgs = null
                shizukuServiceConnection = null
            }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            diagnosticLogger.event(
                category = "agent_mode",
                event = "shizuku_service_bind_timeout",
                level = "warn",
                details = mapOf(
                    "timeout_ms" to ShizukuUserServiceBindTimeoutMillis,
                    "shizuku_version" to runCatching { Shizuku.getVersion() }.getOrDefault(-1),
                    "shizuku_server_patch_version" to runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1),
                ),
            )
            error(
                "Timed out starting Shizuku Agent Mode service after " +
                    "$ShizukuUserServiceBindTimeoutMillis ms. Restart Shizuku or update Shizuku, then refresh Agent Mode status."
            )
        }
    }

    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        when {
            !settings.agentModeAuthorizationEnabled -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Disabled,
                detail = "Agent Mode authorization is disabled.",
            )

            settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root -> inspectRootAuthorization()

            else -> inspectShizukuAuthorization()
        }

    private suspend fun inspectRootAuthorization(): AgentModeAuthorizationState = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null && existing.asBinder().isBinderAlive) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent Mode service is already connected.",
            )
        }
        if (existing != null) {
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }

        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
        }

        val probe = runRootAuthorizationProbe(suPath)
        when {
            probe.launchError.isNotBlank() -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = probe.launchError,
            )

            probe.exitCode == 0 -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root authorization is granted.",
            )

            probe.timedOut -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionMissing,
                detail = "Root authorization timed out. Grant su to Aether, then refresh Agent Mode status.",
            )

            else -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = probe.combinedOutput().ifBlank {
                    "Root authorization was not granted. Grant su to Aether, then refresh Agent Mode status."
                }.take(280),
            )
        }
    }

    private fun inspectShizukuAuthorization(): AgentModeAuthorizationState {
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "Install Shizuku before using Shizuku Agent Mode.",
            )
        }
        val isRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isRunning) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Start Shizuku, then refresh Agent Mode status.",
            )
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.Ready,
                    detail = "Shizuku permission is granted.",
                )
            } else {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                    detail = "Grant Aether permission in Shizuku before using Agent Mode.",
                )
            }
        }.getOrElse { throwable ->
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Unable to inspect Shizuku permission.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isAnyPackageInstalled(packageNames: List<String>): Boolean =
        packageNames.any { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }

    private fun findSuPath(): String {
        val commonPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        commonPaths.firstOrNull { path ->
            File(path).let { it.exists() && it.canExecute() }
        }?.let { return it }

        val result = runProcess(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )
        return result.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun runRootAuthorizationProbe(suPath: String): RootCommandResult =
        runProcess(
            command = listOf(suPath, "-c", "true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )

    private fun runProcess(
        command: List<String>,
        timeoutMillis: Long,
    ): RootCommandResult {
        val process = runCatching {
            ProcessBuilder(command).start()
        }.getOrElse { throwable ->
            return RootCommandResult(
                exitCode = -1,
                launchError = throwable.message.orEmpty(),
            )
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val stderr = runCatching {
            process.errorStream.bufferedReader().readText()
        }.getOrDefault("")
        return RootCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
        )
    }


    private fun normalizedX(value: Double): Int? =
        value.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1000.0) * _displayState.value.width / 1000.0).toInt() }

    private fun normalizedY(value: Double): Int? =
        value.takeIf { !it.isNaN() }?.let { (it.coerceIn(0.0, 1000.0) * _displayState.value.height / 1000.0).toInt() }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }.toString()

    private fun toolError(
        message: String,
        action: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action)
        put("errmsg", message)
        put("stdout", "")
    }.toString()

    private fun currentDeviceDisplaySpec(): DisplaySpec {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val metrics = context.resources.displayMetrics
        return DisplaySpec(
            width = (display?.mode?.physicalWidth ?: size.x).takeIf { it > 0 }
                ?: metrics.widthPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayWidth,
            height = (display?.mode?.physicalHeight ?: size.y).takeIf { it > 0 }
                ?: metrics.heightPixels.takeIf { it > 0 }
                ?: FallbackAgentDisplayHeight,
            densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: FallbackAgentDisplayDensityDpi,
        )
    }

    private data class DisplaySpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val launchError: String = "",
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr, launchError)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }
}
package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

var g_AccessibilityService: MeshAccessibilityService? = null

class MeshAccessibilityService : AccessibilityService() {

    // Drag/swipe state — @Volatile so main-thread longPressRunnable sees writes from WebSocket thread
    @Volatile private var isDragging = false
    @Volatile private var hasMoved = false
    @Volatile private var longPressDispatched = false
    @Volatile private var touchStartX = 0f
    @Volatile private var touchStartY = 0f
    private val dragPath = Path()
    private var leftDownTime = 0L
    private val HOLD_LONG_PRESS_MS = 500L

    // Fires after HOLD_LONG_PRESS_MS while finger is still down and hasn't moved
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        println("longPressTimer: isDragging=$isDragging hasMoved=$hasMoved at ($touchStartX,$touchStartY)")
        if (isDragging && !hasMoved) {
            longPressDispatched = true
            dispatchLongPress(touchStartX, touchStartY)
        }
    }

    // Right-click start position
    private var rightStartX = 0f
    private var rightStartY = 0f

    // MediaProjection dialog auto-accept state
    // false = need to change spinner; true = spinner clicked, waiting to pick "entire screen"
    private var awaitingFullScreenSelection = false

    // Text injection tracking — we maintain our own copy because node.text is often null or
    // stale after ACTION_SET_TEXT, especially in custom search bars and WebView-based fields.
    private var typingNodeId: Int = -1
    private var typingText: String = ""

    override fun onServiceConnected() {
        g_AccessibilityService = this
        println("MeshAccessibilityService: connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        g_AccessibilityService = null
        isDragging = false
        println("MeshAccessibilityService: disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (g_autoConsent) tryAutoAcceptMediaProjection()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Reset text tracker when focus moves to a different node
                val src = event.source ?: return
                val newId = System.identityHashCode(src)
                if (newId != typingNodeId) {
                    typingText = src.text?.toString() ?: ""
                    typingNodeId = newId
                    println("typingFocus: new node cls=${src.className} text='$typingText'")
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Keep our buffer in sync when text changes from other sources (on-screen keyboard,
                // autocomplete, paste from system UI, etc.)
                val src = event.source ?: return
                val newId = System.identityHashCode(src)
                val newText = src.text?.toString() ?: ""
                if (newId == typingNodeId && newText != typingText) {
                    typingText = newText
                }
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    // -------------------------------------------------------------------------
    // Mouse event — called from MeshTunnel
    // flags: 0x00=move, 0x02=left-dn, 0x04=left-up, 0x08=right-dn, 0x10=right-up, 0x88=dbl-click
    // -------------------------------------------------------------------------
    fun injectMouseEvent(scaledX: Float, scaledY: Float, flags: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val sx = toScreen(scaledX, true)
        val sy = toScreen(scaledY, false)
        println("injectMouse: flags=0x${flags.toString(16)} screen=($sx,$sy) drag=$isDragging")

        when (flags) {
            0x00 -> { // Move — accumulate path, dispatch nothing until mouse-up
                if (isDragging) {
                    dragPath.lineTo(sx, sy)
                    // Only count as real movement if displaced > 20px from start.
                    if (!hasMoved && (abs(sx - touchStartX) > 20f || abs(sy - touchStartY) > 20f)) {
                        hasMoved = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        println("longPressCancel: moved ${abs(sx-touchStartX)}x,${abs(sy-touchStartY)}y — timer cancelled")
                    }
                }
            }
            0x02 -> { // Left button DOWN — record start position
                touchStartX = sx; touchStartY = sy
                dragPath.reset()
                dragPath.moveTo(sx, sy)
                isDragging = true
                hasMoved = false
                longPressDispatched = false
                leftDownTime = System.currentTimeMillis()
                println("mouseDown: ($sx,$sy) — starting ${HOLD_LONG_PRESS_MS}ms longPress timer")
                longPressHandler.postDelayed(longPressRunnable, HOLD_LONG_PRESS_MS)
            }
            0x04 -> { // Left button UP — dispatch the full gesture at once
                longPressHandler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    isDragging = false
                    if (longPressDispatched) {
                        // Long press already fired via timer, nothing more to do
                        longPressDispatched = false
                    } else if (!hasMoved) {
                        dispatchTap(touchStartX, touchStartY)
                    } else {
                        dragPath.lineTo(sx, sy)
                        val heldMs = System.currentTimeMillis() - leftDownTime
                        val dy = touchStartY - sy  // positive = finger moved up
                        val dx = sx - touchStartX  // positive = finger moved right

                        // Bottom-edge system gestures (start in bottom 8% of screen)
                        val screenH = (g_ScreenCaptureService?.mHeight
                            ?: android.content.res.Resources.getSystem().displayMetrics.heightPixels).toFloat()
                        if (touchStartY > screenH * 0.92f && dy > 60f) {
                            if (dy > 250f) {
                                println("bottomEdge: long up → RECENTS")
                                performGlobalAction(GLOBAL_ACTION_RECENTS)
                            } else {
                                println("bottomEdge: short up → HOME")
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        } else {
                            // Normal swipe/drag gesture.
                            // heldMs < 400ms → fast swipe/fling (200ms); else slow drag (capped 1s).
                            val duration = if (heldMs < 400L) 200L else heldMs.coerceAtMost(1000L)

                            // For fast swipes: axis-align to dominant direction so slight diagonals
                            // don't get misread (e.g. a mostly-horizontal swipe won't become a scroll).
                            val gesturePath = if (heldMs < 400L) {
                                val absDx = abs(sx - touchStartX)
                                val absDy = abs(sy - touchStartY)
                                if (absDx >= absDy) {
                                    // Horizontal dominant → lock Y to midpoint
                                    val midY = (touchStartY + sy) / 2f
                                    Path().apply { moveTo(touchStartX, midY); lineTo(sx, midY) }
                                } else {
                                    // Vertical dominant → lock X to midpoint
                                    val midX = (touchStartX + sx) / 2f
                                    Path().apply { moveTo(midX, touchStartY); lineTo(midX, sy) }
                                }
                            } else {
                                dragPath // slow drag: keep actual path for precision
                            }

                            println("dispatchGesture: (${touchStartX},${touchStartY})→(${sx},${sy}) held=${heldMs}ms dur=${duration}ms dx=${sx-touchStartX} dy=${sy-touchStartY}")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                val stroke = GestureDescription.StrokeDescription(gesturePath, 0, duration)
                                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                            }
                        }
                    }
                }
            }
            0x08 -> { // Right button DOWN — save position for long press
                rightStartX = sx; rightStartY = sy
            }
            0x10 -> { // Right button UP — long press = context menu
                dispatchLongPress(rightStartX, rightStartY)
            }
            0x88 -> { // Double click
                dispatchDoubleTap(sx, sy)
            }
        }
    }

    // Scroll event — called from MeshTunnel when cmdsize=12
    // delta > 0: scroll up (content moves down) → swipe down gesture
    // delta < 0: scroll down (content moves up) → swipe up gesture
    fun injectScrollEvent(scaledX: Float, scaledY: Float, delta: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val sx = toScreen(scaledX, true)
        val sy = toScreen(scaledY, false)
        println("injectScroll: delta=$delta screen=($sx,$sy)")
        // Positive delta = wheel up → page content moves down → swipe DOWN on device
        dispatchScroll(sx, sy, scrollDown = delta < 0)
    }

    enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

    // Directional swipe from the center of the screen
    fun injectSwipe(dir: SwipeDir) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val w = (g_ScreenCaptureService?.mWidth ?: dm.widthPixels).toFloat()
        val h = (g_ScreenCaptureService?.mHeight ?: dm.heightPixels).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dist = minOf(w, h) * 0.35f  // ~35% of shorter dimension
        val (x1, y1, x2, y2) = when (dir) {
            SwipeDir.UP    -> floatArrayOf(cx, cy + dist, cx, cy - dist)
            SwipeDir.DOWN  -> floatArrayOf(cx, cy - dist, cx, cy + dist)
            SwipeDir.LEFT  -> floatArrayOf(cx + dist, cy, cx - dist, cy)
            SwipeDir.RIGHT -> floatArrayOf(cx - dist, cy, cx + dist, cy)
        }
        println("injectSwipe: $dir ($x1,$y1)→($x2,$y2)")
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // Unicode character input — called from MeshTunnel cmd=85.
    fun injectUnicodeChar(char: Int, isDown: Boolean) {
        // Log ALL events (including key-up) so we can diagnose what the client sends
        println("injectUnicode: U+${char.toString(16)} down=$isDown")
        if (!isDown) return
        // Control chars (< 0x20), DEL (0x7F), X11 keysym range (>= 0xFF00) → special-key handler
        if (char < 0x20 || char == 0x7F || char >= 0xFF00) {
            injectKeyEvent(char, isDown)
            return
        }
        val node = inputFocusNode() ?: run {
            println("injectUnicode: no focused node")
            return
        }
        syncTypingNode(node)
        typingText += String(Character.toChars(char))
        setNodeText(node, typingText)
    }

    // Special-key event — called from MeshTunnel cmd=1 (Windows VK codes and X11 keysym).
    fun injectKeyEvent(keyChar: Int, isDown: Boolean) {
        println("injectKey: char=0x${keyChar.toString(16)} down=$isDown")
        if (!isDown) return
        when (keyChar) {
            0x08, 0x7F, 0xFF08 -> { // Backspace / Delete / XK_BackSpace
                val node = inputFocusNode() ?: run {
                    println("injectKey: backspace but no focused node")
                    return
                }
                // Always read current text from node (don't rely on potentially stale buffer)
                val currentText = node.text?.toString() ?: typingText
                println("injectKey: backspace currentText='$currentText'")
                if (currentText.isNotEmpty()) {
                    val newText = currentText.dropLast(1)
                    typingText = newText
                    typingNodeId = System.identityHashCode(node)
                    setNodeText(node, newText)
                }
            }
            0x0D, 0x0A, 0xFF0D -> { // Enter / XK_Return
                val node = inputFocusNode()
                if (node != null) {
                    syncTypingNode(node)
                    typingText += "\n"
                    setNodeText(node, typingText)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            0x1B, 0xFF1B -> performGlobalAction(GLOBAL_ACTION_BACK) // Escape / XK_Escape
            // Arrow keys — Windows VK codes
            0x25 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.LEFT)
            0x26 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.UP)
            0x27 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.RIGHT)
            0x28 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.DOWN)
            // Arrow keys — X11 keysym (XK_Left/Up/Right/Down)
            0xFF51 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.LEFT)
            0xFF52 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.UP)
            0xFF53 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.RIGHT)
            0xFF54 -> if (inputFocusNode() == null) injectSwipe(SwipeDir.DOWN)
            // PageUp/PageDown — Windows VK + X11 keysym
            0x21, 0xFF55 -> injectSwipe(SwipeDir.UP)
            0x22, 0xFF56 -> injectSwipe(SwipeDir.DOWN)
            // F1-F3 system nav — Windows VK
            0x70 -> performGlobalAction(GLOBAL_ACTION_BACK)
            0x71 -> performGlobalAction(GLOBAL_ACTION_HOME)
            0x72 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            // F1-F3 system nav — X11 keysym (XK_F1/F2/F3)
            0xFFBE -> performGlobalAction(GLOBAL_ACTION_BACK)
            0xFFBF -> performGlobalAction(GLOBAL_ACTION_HOME)
            0xFFC0 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            // F7-F10 swipe — Windows VK
            0x76 -> injectSwipe(SwipeDir.LEFT)
            0x77 -> injectSwipe(SwipeDir.RIGHT)
            0x78 -> injectSwipe(SwipeDir.UP)
            0x79 -> injectSwipe(SwipeDir.DOWN)
            // F7-F10 swipe — X11 keysym (XK_F7/F8/F9/F10)
            0xFFC4 -> injectSwipe(SwipeDir.LEFT)
            0xFFC5 -> injectSwipe(SwipeDir.RIGHT)
            0xFFC6 -> injectSwipe(SwipeDir.UP)
            0xFFC7 -> injectSwipe(SwipeDir.DOWN)
            else -> {
                // Printable ASCII/Unicode only; skip X11 keysym special values (0xFF00+)
                if (keyChar < 0xFF00 && (keyChar in 0x20..0x24 || keyChar > 0x28)) {
                    val node = inputFocusNode()
                    println("injectKey: printable U+${keyChar.toString(16)} node=${node?.className}")
                    if (node != null) {
                        syncTypingNode(node)
                        typingText += String(Character.toChars(keyChar))
                        setNodeText(node, typingText)
                    }
                }
            }
        }
    }

    // Find the currently input-focused (keyboard focus) node across all windows.
    private fun inputFocusNode(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        val wins = try { windows } catch (e: Exception) { null } ?: return null
        for (win in wins) {
            val root = try { win.root } catch (e: Exception) { null } ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        return null
    }

    // Sync our typingText buffer when we switch to a new node.
    private fun syncTypingNode(node: AccessibilityNodeInfo) {
        val id = System.identityHashCode(node)
        if (id != typingNodeId) {
            typingText = node.text?.toString() ?: ""
            typingNodeId = id
            println("typingSync: new node cls=${node.className} text='$typingText'")
        }
    }

    // Apply text to node via ACTION_SET_TEXT.
    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        println("setNodeText: '${text.takeLast(20)}' ok=$ok")
    }

    // -------------------------------------------------------------------------
    // Private gesture dispatch helpers
    // -------------------------------------------------------------------------

    private fun dispatchTap(x: Float, y: Float) {
        println("dispatchTap: ($x,$y)")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        // Try gesture tap at exact coordinates. Falls back to node click only when gesture is
        // blocked by a SECURE window (e.g. system permission dialog).
        val cb = object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                println("dispatchTap: gesture cancelled → node click at ($x,$y)")
                performNodeClick(x.toInt(), y.toInt())
            }
        }
        val dispatched = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, null)
        if (!dispatched) {
            println("dispatchTap: not dispatched → node click at ($x,$y)")
            performNodeClick(x.toInt(), y.toInt())
        }
    }

    // Search ALL accessible windows (including system dialogs from other processes)
    // and click the deepest clickable node that contains (x, y).
    private fun performNodeClick(x: Int, y: Int): Boolean {
        // windows is ordered front-to-back, so the first window with a hit wins.
        val allWindows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) windows else null
        println("nodeClick: windowCount=${allWindows?.size ?: 0} at ($x,$y)")

        // Helper: try clicking at (x,y) in a given root node, clickable-only first then any node
        fun tryClickInRoot(root: AccessibilityNodeInfo, tag: String): Boolean {
            // Pass 1: strict — clickable + enabled
            var node = findDeepestClickableNode(root, x, y, requireClickable = true)
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                println("nodeClick[$tag]: strict cls='${node.className}' ok=$ok")
                if (ok) return true
            }
            // Pass 2: relaxed — any node, try ACTION_CLICK anyway (works for non-clickable TextViews in dialogs)
            node = findDeepestClickableNode(root, x, y, requireClickable = false)
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                println("nodeClick[$tag]: relaxed cls='${node.className}' clickable=${node.isClickable} ok=$ok")
                if (ok) return true
                // Try parent nodes (walk up to root)
                var parent = node.parent
                while (parent != null) {
                    val parentRect = Rect()
                    parent.getBoundsInScreen(parentRect)
                    if (parentRect.contains(x, y)) {
                        val pok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        println("nodeClick[$tag]: parent cls='${parent.className}' clickable=${parent.isClickable} ok=$pok")
                        if (pok) return true
                    }
                    parent = parent.parent
                }
            }
            println("nodeClick[$tag]: no node worked at ($x,$y)")
            return false
        }


        if (allWindows != null) {
            for (window in allWindows) {
                val root = try { window.root } catch (e: Exception) { null } ?: continue
                try {
                    val tag = "win${window.type}/${root.packageName}"
                    if (tryClickInRoot(root, tag)) return true
                } catch (e: Exception) {
                    println("nodeClick: win exception ${e.message}")
                }
            }
        }
        // Fallback: active window only
        val root = rootInActiveWindow
        println("nodeClick: rootInActiveWindow=${root?.packageName}")
        if (root == null) return false
        return try {
            tryClickInRoot(root, "rootWin")
        } catch (e: Exception) {
            println("nodeClick: fallback exception ${e.message}")
            false
        }
    }

    // DFS: returns the deepest node whose screen bounds contain (x,y).
    // If requireClickable=true, only returns nodes with isClickable && isEnabled.
    // If requireClickable=false, returns ANY node that contains the point.
    private fun findDeepestClickableNode(
        node: AccessibilityNodeInfo, x: Int, y: Int, requireClickable: Boolean = true
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDeepestClickableNode(child, x, y, requireClickable)
            if (found != null) return found
        }

        return if (requireClickable) {
            if (node.isClickable && node.isEnabled) node else null
        } else {
            node // return any node that contains the point
        }
    }

    private fun dispatchDoubleTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        println("dispatchDoubleTap: ($x,$y)")
        val path = Path().apply { moveTo(x, y) }
        // Two short strokes 100ms apart
        val stroke1 = GestureDescription.StrokeDescription(path, 0, 50)
        val stroke2 = GestureDescription.StrokeDescription(path, 150, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build(), null, null)
    }

    private fun dispatchLongPress(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        println("dispatchLongPress: ($x,$y)")
        val path = Path().apply { moveTo(x, y) }
        // 1500ms: well beyond Flutter/Android 500ms LPR threshold, gives action time to complete
        val stroke = GestureDescription.StrokeDescription(path, 0, 1500)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                println("dispatchLongPress: gesture completed — trying ACTION_LONG_CLICK fallback")
                performNodeLongClick(x.toInt(), y.toInt())
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                println("dispatchLongPress: gesture cancelled → ACTION_LONG_CLICK at ($x,$y)")
                performNodeLongClick(x.toInt(), y.toInt())
            }
        }
        val dispatched = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, null)
        println("dispatchLongPress: gesture dispatched=$dispatched")
        if (!dispatched) {
            println("dispatchLongPress: not dispatched → ACTION_LONG_CLICK at ($x,$y)")
            performNodeLongClick(x.toInt(), y.toInt())
        }
    }

    private fun performNodeLongClick(x: Int, y: Int) {
        val allWindows = try { windows } catch (e: Exception) { null }
        if (allWindows != null) {
            for (window in allWindows) {
                val root = try { window.root } catch (e: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                val node = findDeepestClickableNode(root, x, y, requireClickable = false)
                if (node != null) {
                    val actions = node.actionList?.map { it.id } ?: emptyList()
                    println("performNodeLongClick: pkg=$pkg cls=${node.className} clickable=${node.isClickable} actions=$actions at ($x,$y)")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                        println("performNodeLongClick: ok at ($x,$y)")
                        return
                    }
                    // Try parent
                    var p = node.parent
                    while (p != null) {
                        if (p.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                            println("performNodeLongClick: parent ok cls=${p.className}")
                            return
                        }
                        p = p.parent
                    }
                } else {
                    println("performNodeLongClick: no node at ($x,$y) in pkg=$pkg")
                }
            }
        }
        val root = rootInActiveWindow ?: return
        val node = findDeepestClickableNode(root, x, y, requireClickable = false) ?: return
        println("performNodeLongClick: rootWin cls=${node.className} clickable=${node.isClickable}")
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    private fun dispatchScroll(x: Float, y: Float, scrollDown: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val scrollDistance = 400f
        val y2 = if (scrollDown) (y - scrollDistance).coerceAtLeast(0f)
                 else (y + scrollDistance).coerceAtMost(4000f)
        println("dispatchScroll: ($x,$y) scrollDown=$scrollDown → y2=$y2")
        val path = Path().apply { moveTo(x, y); lineTo(x, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // Auto-accept the Android MediaProjection permission dialog when autoConsent is on.
    // Android 14+ shows a 2-step dialog:
    //   Step 1 – spinner defaults to "Chia sẻ một ứng dụng" → click it to open dropdown
    //   Step 2 – dropdown open → click "Chia sẻ toàn màn hình"
    //   Step 3 – confirm with "Tiếp theo" / "Start now" etc.
    private fun tryAutoAcceptMediaProjection() {
        // Only auto-click when the app has actually requested a projection — prevents
        // accidentally clicking "Bắt đầu" buttons in the notification shade or elsewhere.
        if (!g_pendingProjectionRequest) return
        val roots = getSystemUIRoots()
        if (roots.isEmpty()) return

        for (root in roots) {
            // Step 2: dropdown is open — pick "entire screen"
            if (awaitingFullScreenSelection) {
                val fullScreenTexts = listOf(
                    "toàn bộ màn hình",  // Samsung VI: "Chia sẻ toàn bộ màn hình"
                    "toàn màn hình",     // alt VI: "Chia sẻ toàn màn hình"
                    "Entire screen",     // AOSP EN
                    "Whole screen",      // Samsung EN
                    "Full screen"
                )
                for (text in fullScreenTexts) {
                    if (clickNodeByText(root, text)) {
                        awaitingFullScreenSelection = false
                        return
                    }
                }
                return // dropdown not ready yet, wait for next event
            }

            // Step 1: "Share one app" spinner visible → click it to open dropdown
            val oneAppTexts = listOf("một ứng dụng", "Share one app", "An app", "one app")
            var spinnerClicked = false
            for (text in oneAppTexts) {
                if (clickNodeByText(root, text)) {
                    awaitingFullScreenSelection = true
                    spinnerClicked = true
                    break
                }
            }
            if (spinnerClicked) return

            // Step 3: confirm button — text varies by spinner selection and Android version
            val confirmTexts = listOf(
                "Chia sẻ màn hình",  // Samsung VI after selecting "entire screen"
                "Chia sẻ",           // Samsung VI short variant
                "Share screen",      // Samsung EN after selecting "entire screen"
                "Share",             // AOSP EN short
                "Tiếp theo",         // Samsung VI when "one app" selected
                "Next",              // EN when "one app" selected
                "Start now", "Bắt đầu ngay", "Bắt đầu", "Allow", "Start"
            )
            for (text in confirmTexts) {
                if (clickNodeByText(root, text)) return
            }
        }
    }

    private fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (e: Exception) { return false }
        for (node in nodes) {
            val target = if (node.isClickable && node.isEnabled) node
                         else node.parent?.takeIf { it.isClickable && it.isEnabled }
            if (target != null) {
                println("autoAccept: clicking '$text'")
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    private fun getSystemUIRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val allWins = try { windows } catch (e: Exception) { null }
        if (allWins != null) {
            for (win in allWins) {
                val root = try { win.root } catch (e: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg.contains("systemui", ignoreCase = true) || pkg == "android") {
                    roots.add(root)
                }
            }
        }
        if (roots.isEmpty()) {
            val root = rootInActiveWindow ?: return roots
            val pkg = root.packageName?.toString() ?: return roots
            if (pkg.contains("systemui", ignoreCase = true) || pkg == "android") {
                roots.add(root)
            }
        }
        return roots
    }

    // Convert scaled coordinates back to physical screen pixels
    private fun toScreen(scaled: Float, isX: Boolean): Float {
        return if (g_desktop_scalingLevel != 1024 && g_ScreenCaptureService != null)
            (scaled * 1024f) / g_desktop_scalingLevel
        else scaled
    }
}

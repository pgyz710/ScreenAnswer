package com.screenanswer.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.screenanswer.app.R
import com.screenanswer.app.util.displaySize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Overlay windows:
 *
 *  - a draggable floating ball,
 *  - a compact answer pill next to the ball (the short answer only),
 *  - the answer panel with the explanation, opened by tapping the ball.
 *
 * Tap the ball: scan when there is no answer yet, otherwise open the explanation.
 * Long press the ball: scan again. Double tap: hide everything.
 *
 * All windows are hidden right before a screenshot so the assistant does not capture itself.
 */
class OverlayController(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onScanRequested()
        fun onAddPageRequested()
        fun onListenRequested()
        fun onRetryRequested()
        fun onPanelClosed()
        fun onHideBubble()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_ScreenAnswer)
    private val inflater = LayoutInflater.from(themedContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screen = displaySize(context)

    private var bubbleRoot: View? = null
    private var bubbleImage: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAdded = false
    private var bubbleBusy = false
    private var bubbleHidden = false
    private var bubbleWidth = 0
    private var bubbleHeight = 0

    private var pillRoot: View? = null
    private var pillAnswerView: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillAdded = false
    private var pillContent = ""
    private var lastPillRefresh = 0L

    private var panelRoot: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAdded = false
    private var panelVisible = false
    private var panelWasVisibleBeforeCapture = false

    private var statusDot: TextView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var previewText: TextView? = null
    private var answerScroll: MaxHeightScrollView? = null
    private var answerText: TextView? = null
    private var ocrToggle: TextView? = null
    private var ocrScroll: MaxHeightScrollView? = null
    private var ocrText: TextView? = null
    private var actionsRow: View? = null
    private var addPageButton: TextView? = null
    private var listenButton: TextView? = null
    private var copyButton: TextView? = null
    private var retryButton: TextView? = null
    private var rescanButton: TextView? = null

    private var currentAnswer = ""
    private var currentOcrText = ""
    private var ocrExpanded = false
    private var copyingReset: Runnable? = null
    private var releasing = false
    private var positionPending = false
    private var capturing = false

    /** Last short answer, so stopping speech input restores it instead of leaving "正在听…". */
    private var lastAnswerPill = ""

    /** Set after the user taps 「加一页」: the next tap on the ball captures the next page. */
    private var awaitingAddPage = false

    /** A scan/request is running: tapping the ball should show progress instead of starting over. */
    private var working = false
    private var hasAnswer = false

    private val repositionTask = Runnable {
        positionPending = false
        positionPanel()
    }

    // ---------------------------------------------------------------- bubble

    fun showBubble() {
        bubbleHidden = false
        if (bubbleAdded) {
            bubbleRoot?.visibility = View.VISIBLE
            bubbleRoot?.alpha = 1f
            setBubbleBusy(bubbleBusy)
            updatePillVisibility()
            return
        }
        screen = displaySize(context)
        val root = inflater.inflate(R.layout.overlay_bubble, null)
        val image = root.findViewById<ImageView>(R.id.bubbleImage)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Keep clear of the left/right system back-gesture areas on MIUI/HyperOS.
            x = max(dp(24), screen.x - dp(58) - dp(34))
            y = (screen.y * 0.32f).roundToInt()
        }
        attachDrag(root, params)
        windowManager.addView(root, params)

        bubbleRoot = root
        bubbleImage = image
        bubbleParams = params
        bubbleAdded = true
        setBubbleBusy(bubbleBusy)
        applyGestureExclusion(root)
        preparePanel()
        preparePill()
        root.post {
            bubbleWidth = root.width
            bubbleHeight = root.height
            updatePillVisibility()
        }
    }

    /** Hide the floating icon but keep the foreground service and notification alive. */
    fun hideBubble() {
        if (!bubbleAdded) return
        bubbleHidden = true
        awaitingAddPage = false
        hidePanel()
        bubbleRoot?.visibility = View.GONE
        pillRoot?.visibility = View.GONE
    }

    fun setBubbleBusy(busy: Boolean) {
        bubbleBusy = busy
        bubbleImage?.let {
            it.alpha = if (busy) 0.55f else 1f
            // Important: the child must stay non-clickable, otherwise it consumes the
            // touch and the parent FrameLayout's drag/tap listener never runs.
            it.isClickable = false
            it.isEnabled = true
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!dragging) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        playTapAnimation(view)
                        when {
                            // 「加一页」was requested: this tap captures the next page.
                            awaitingAddPage -> {
                                awaitingAddPage = false
                                listener.onAddPageRequested()
                            }
                            // The explanation is open: the same tap folds it back.
                            panelVisible -> hidePanel()
                            // An answer exists (or is being generated): show the explanation.
                            working || hasAnswer -> openPanel()
                            else -> listener.onScanRequested()
                        }
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (dragging) return
                    // Long press always starts a fresh scan, so the same ball can be reused
                    // after the question changed without opening the panel first.
                    awaitingAddPage = false
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    playTapAnimation(view)
                    listener.onScanRequested()
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!dragging) {
                        awaitingAddPage = false
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        listener.onHideBubble()
                    }
                    return true
                }
            }
        )

        view.setOnTouchListener { touched, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
            }
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val minX = dp(24)
                        val minY = dp(24)
                        val maxX = max(minX, screen.x - touched.width - dp(24))
                        val maxY = max(minY, screen.y - touched.height - dp(48))
                        params.x = (startX + dx).roundToInt().coerceIn(minX, maxX)
                        params.y = (startY + dy).roundToInt().coerceIn(minY, maxY)
                        safeUpdate(touched, params)
                        positionPill()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> true

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun playTapAnimation(view: View) {
        view.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(70L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
            }
            .start()
    }

    /** Ask the system not to steal touches for the back gesture around the bubble. */
    private fun applyGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.post {
            if (view.width <= 0 || view.height <= 0) return@post
            runCatching {
                view.setSystemGestureExclusionRects(
                    mutableListOf(Rect(0, 0, view.width, view.height))
                )
            }
        }
    }

    // ---------------------------------------------------------------- answer pill

    /**
     * Created together with the bubble so MIUI/HyperOS does not block adding an overlay window
     * from the background later. The window is not touchable: taps go through to the app below.
     */
    private fun preparePill() {
        if (pillAdded) return
        val root = try {
            inflater.inflate(R.layout.overlay_answer_pill, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to inflate the answer pill", t)
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        root.visibility = View.GONE
        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to add the answer pill window", t)
            return
        }
        pillRoot = root
        pillParams = params
        pillAdded = true
        pillAnswerView = root.findViewById(R.id.pillAnswer)
    }

    private fun setPillContent(text: String) {
        val value = text.trim()
        pillContent = value
        pillAnswerView?.text = value
        updatePillVisibility()
    }

    private fun setPillContentThrottled(text: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPillRefresh < PILL_REFRESH_MS) return
        lastPillRefresh = now
        setPillContent(text)
    }

    private fun updatePillVisibility() {
        val pill = pillRoot ?: return
        val shouldShow = !bubbleHidden && !capturing && !panelVisible && pillContent.isNotBlank()
        if (!shouldShow) {
            pill.visibility = View.GONE
            return
        }
        if (pill.visibility != View.VISIBLE) {
            pill.visibility = View.VISIBLE
        }
        pill.post { positionPill() }
    }

    private fun positionPill() {
        val pill = pillRoot ?: return
        val params = pillParams ?: return
        val bubble = bubbleParams ?: return
        if (!pillAdded || pill.visibility != View.VISIBLE) return
        if (pill.width <= 0 || pill.height <= 0) {
            pill.post { positionPill() }
            return
        }
        val bubbleW = bubbleWidth.takeIf { it > 0 } ?: bubbleRoot?.width?.takeIf { it > 0 } ?: dp(58)
        val bubbleH =
            bubbleHeight.takeIf { it > 0 } ?: bubbleRoot?.height?.takeIf { it > 0 } ?: dp(58)
        val gap = dp(8)

        // Default: left of the ball, vertically centred.
        var x = bubble.x - pill.width - gap
        var y = bubble.y + (bubbleH - pill.height) / 2
        if (x < dp(4)) {
            // No room on the left: put it above the ball and centre it horizontally.
            x = bubble.x + (bubbleW - pill.width) / 2
            y = bubble.y - pill.height - gap
        }
        if (y < dp(4)) y = bubble.y + bubbleH + gap

        val maxX = max(dp(4), screen.x - pill.width - dp(4))
        val maxY = max(dp(4), screen.y - pill.height - dp(4))
        params.x = x.coerceIn(dp(4), maxX)
        params.y = y.coerceIn(dp(4), maxY)
        safeUpdate(pill, params)
    }

    // ---------------------------------------------------------------- panel

    fun showOcrStage() {
        working = true
        hasAnswer = false
        setPillContent(PILL_OCR)
        renderLoading("正在识别屏幕文字…", "")
    }

    fun showThinking(preview: String, fullOcr: String = preview) {
        working = true
        hasAnswer = false
        setPillContent(PILL_THINKING)
        renderLoading("正在思考…", preview, fullOcr)
    }

    private fun renderLoading(status: String, preview: String, fullOcr: String = preview) {
        ensurePanel()
        progressBar?.isVisible = true
        statusText?.text = status
        setDotColor(R.color.brand_light)
        if (preview.isNotBlank() || fullOcr.isNotBlank()) {
            setPreview(preview, fullOcr)
        } else {
            clearPreview()
        }
        answerScroll?.isVisible = true
        answerText?.setTextColor(color(R.color.text_muted))
        answerText?.text = "正在生成…"
        actionsRow?.isVisible = false
        addPageButton?.isVisible = false
        listenButton?.isVisible = false
        currentAnswer = ""
        scrollAnswerToBottom()
        schedulePosition()
    }

    /** Streamed answer text: the panel sees the raw output, the pill only the answer part. */
    fun updateStreaming(raw: String, answerPart: String) {
        ensurePanel()
        progressBar?.isVisible = true
        statusText?.text = "正在作答…"
        setDotColor(R.color.brand_light)
        answerScroll?.isVisible = true
        answerText?.setTextColor(color(R.color.text_primary))
        answerText?.text = MarkdownRenderer.render(raw)
        actionsRow?.isVisible = false
        addPageButton?.isVisible = false
        listenButton?.isVisible = false
        currentAnswer = raw
        scrollAnswerToBottom()
        schedulePosition()
        if (answerPart.isNotBlank()) {
            lastAnswerPill = answerPart
            setPillContentThrottled(answerPart)
        }
    }

    fun finishAnswer(raw: String, answer: String, explanation: String, pageCount: Int = 1) {
        working = false
        hasAnswer = answer.isNotBlank()
        lastAnswerPill = answer
        setPillContent(if (hasAnswer) answer else "已生成")

        val panelBody = buildPanelBody(answer, explanation).ifBlank { raw.trim() }
        currentAnswer = panelBody
        ensurePanel()
        progressBar?.isVisible = false
        statusText?.text = if (pageCount > 1) "已完成 · $pageCount 页" else "已完成"
        setDotColor(R.color.success)
        answerScroll?.isVisible = true
        answerText?.setTextColor(color(R.color.text_primary))
        answerText?.text = MarkdownRenderer.render(panelBody)
        actionsRow?.isVisible = true
        addPageButton?.isVisible = true
        listenButton?.isVisible = true
        copyButton?.isVisible = true
        retryButton?.isVisible = true
        rescanButton?.isVisible = true
        scrollAnswerToBottom()
        schedulePosition()
    }

    private fun buildPanelBody(answer: String, explanation: String): String {
        val builder = StringBuilder()
        if (answer.isNotBlank()) {
            builder.append("【答案】\n").append(answer.trim())
        }
        if (explanation.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append("【解析】\n").append(explanation.trim())
        }
        return builder.toString()
    }

    fun showInfo(message: String, allowRetry: Boolean) {
        working = false
        hasAnswer = false
        awaitingAddPage = false
        setPillContent("")
        if (!showPanelWindow()) return
        progressBar?.isVisible = false
        statusText?.text = "提示"
        setDotColor(R.color.warning)
        answerScroll?.isVisible = true
        answerText?.setTextColor(color(R.color.text_primary))
        answerText?.text = message
        currentAnswer = ""
        actionsRow?.isVisible = true
        addPageButton?.isVisible = false
        listenButton?.isVisible = true
        copyButton?.isVisible = false
        retryButton?.isVisible = allowRetry
        rescanButton?.isVisible = true
        scrollAnswerToBottom()
        schedulePosition()
    }

    fun showError(message: String, allowRetry: Boolean) {
        working = false
        hasAnswer = false
        awaitingAddPage = false
        setPillContent("")
        if (!showPanelWindow()) return
        progressBar?.isVisible = false
        statusText?.text = "出错了"
        setDotColor(R.color.error)
        answerScroll?.isVisible = true
        answerText?.setTextColor(color(R.color.error))
        answerText?.text = message
        currentAnswer = ""
        actionsRow?.isVisible = true
        addPageButton?.isVisible = false
        listenButton?.isVisible = true
        copyButton?.isVisible = false
        retryButton?.isVisible = allowRetry
        rescanButton?.isVisible = true
        scrollAnswerToBottom()
        schedulePosition()
    }

    /** Shows the explanation panel with whatever was generated last (or the live progress). */
    fun openPanel() {
        if (showPanelWindow()) schedulePosition(0L)
    }

    /**
     * Toggles the 「听一段」 state. While listening the answer pill shows the partial transcript;
     * stopping restores the last answer.
     */
    fun setListening(listening: Boolean, partial: String = "") {
        listenButton?.text = context.getString(
            if (listening) R.string.stop_listening else R.string.listen
        )
        if (listening) {
            setPillContent(partial.ifBlank { context.getString(R.string.listening) })
        } else {
            setPillContent(lastAnswerPill)
        }
    }

    /**
     * 「加一页」：fold the panel, remember that the next tap on the ball should capture another
     * page, and show the hint in the answer pill.
     */
    private fun requestAddPage() {
        awaitingAddPage = true
        hidePanel()
        setPillContent(context.getString(R.string.next_page_hint))
    }

    fun isPanelVisible(): Boolean = panelVisible

    private fun ensurePanel(): Boolean {
        if (panelAdded) return true
        screen = displaySize(context)
        val root = try {
            inflater.inflate(R.layout.overlay_panel, null) as LinearLayout
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to inflate the answer panel", t)
            toast("答案浮窗加载失败，请重试")
            return false
        }
        val width = min(screen.x - dp(24), dp(300)).coerceAtLeast(dp(240))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screen.x - width) / 2).coerceAtLeast(0)
            y = 0
            alpha = 1f
        }

        root.alpha = 0f
        root.translationY = dp(18).toFloat()
        root.visibility = View.GONE
        var added = false
        try {
            windowManager.addView(root, params)
            added = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to add the answer panel window", t)
        }
        if (!added) {
            toast("无法显示答案浮窗，请检查悬浮窗权限")
            return false
        }

        panelRoot = root
        panelParams = params
        panelAdded = true
        bindPanel(root)
        return true
    }

    /**
     * Create the panel window while the app is still in the foreground. Showing it later only
     * toggles visibility, avoiding MIUI/HyperOS restrictions on adding overlay windows from
     * the background.
     */
    private fun preparePanel() {
        if (!ensurePanel()) return
        panelVisible = false
        panelRoot?.visibility = View.GONE
        panelRoot?.alpha = 1f
        panelRoot?.translationY = 0f
    }

    private fun showPanelWindow(): Boolean {
        if (!ensurePanel()) return false
        val root = panelRoot ?: return false
        val becameVisible = root.visibility != View.VISIBLE
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f
        panelVisible = true
        if (becameVisible) root.post { positionPanel() }
        updatePillVisibility()
        return true
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun bindPanel(root: View) {
        statusDot = root.findViewById(R.id.statusDot)
        statusText = root.findViewById(R.id.statusText)
        progressBar = root.findViewById(R.id.progressBar)
        previewText = root.findViewById(R.id.previewText)
        answerScroll = root.findViewById(R.id.answerScroll)
        answerText = root.findViewById(R.id.answerText)
        ocrToggle = root.findViewById(R.id.ocrToggle)
        ocrScroll = root.findViewById(R.id.ocrScroll)
        ocrText = root.findViewById(R.id.ocrText)
        actionsRow = root.findViewById(R.id.actionsRow)
        addPageButton = root.findViewById(R.id.addPageButton)
        listenButton = root.findViewById(R.id.listenButton)
        copyButton = root.findViewById(R.id.copyButton)
        retryButton = root.findViewById(R.id.retryButton)
        rescanButton = root.findViewById(R.id.rescanButton)

        val closeButton = root.findViewById<ImageButton>(R.id.closeButton)
        closeButton.setOnClickListener { hidePanel() }

        addPageButton?.setOnClickListener { requestAddPage() }
        listenButton?.setOnClickListener { listener.onListenRequested() }
        copyButton?.setOnClickListener { copyAnswer() }
        retryButton?.setOnClickListener { listener.onRetryRequested() }
        rescanButton?.setOnClickListener { listener.onScanRequested() }
        ocrToggle?.setOnClickListener { toggleOcrText() }

        applyPanelSizeLimits()

        ocrExpanded = false
        ocrScroll?.isVisible = false
        ocrToggle?.isVisible = currentOcrText.isNotBlank()
        ocrToggle?.text = context.getString(R.string.show_ocr)
        updateOcrViews()
    }

    private fun applyPanelSizeLimits() {
        answerScroll?.maxHeight = min(
            (screen.y * 0.22f).toInt(),
            dp(180)
        ).coerceAtLeast(dp(100))
        ocrScroll?.maxHeight = dp(90)
    }

    private fun setPreview(text: String, fullOcr: String = text) {
        currentOcrText = fullOcr
        val snippet = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" ")
        previewText?.text = snippet
        previewText?.isVisible = snippet.isNotBlank()
        ocrToggle?.isVisible = fullOcr.isNotBlank()
        updateOcrViews()
    }

    private fun clearPreview() {
        currentOcrText = ""
        previewText?.text = ""
        previewText?.isVisible = false
        ocrExpanded = false
        ocrToggle?.isVisible = false
        ocrScroll?.isVisible = false
    }

    private fun updateOcrViews() {
        ocrText?.text = currentOcrText
        ocrToggle?.isVisible = currentOcrText.isNotBlank()
        ocrScroll?.isVisible = ocrExpanded && currentOcrText.isNotBlank()
        ocrToggle?.text = context.getString(
            if (ocrExpanded) R.string.hide_ocr else R.string.show_ocr
        )
    }

    private fun toggleOcrText() {
        if (currentOcrText.isBlank()) return
        ocrExpanded = !ocrExpanded
        updateOcrViews()
        schedulePosition(90L)
    }

    private fun copyAnswer() {
        if (currentAnswer.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI 答案", currentAnswer))
        val button = copyButton ?: return
        copyingReset?.let { mainHandler.removeCallbacks(it) }
        button.text = "已复制"
        val reset = Runnable {
            if (panelAdded) button.text = context.getString(R.string.copy)
        }
        copyingReset = reset
        mainHandler.postDelayed(reset, 1400L)
    }

    private fun hidePanel() {
        mainHandler.removeCallbacks(repositionTask)
        positionPending = false
        panelWasVisibleBeforeCapture = false
        panelVisible = false
        panelRoot?.visibility = View.GONE
        ocrExpanded = false
        updatePillVisibility()
        if (!releasing) listener.onPanelClosed()
    }

    // ---------------------------------------------------------------- capture

    /** Hide every overlay window so it does not appear in the screenshot. */
    fun hideForCapture() {
        capturing = true
        panelWasVisibleBeforeCapture = panelAdded && panelVisible
        if (!bubbleHidden) bubbleRoot?.visibility = View.INVISIBLE
        pillRoot?.visibility = View.GONE
        panelRoot?.visibility = View.INVISIBLE
    }

    fun restoreAfterCapture() {
        capturing = false
        if (!bubbleHidden) bubbleRoot?.visibility = View.VISIBLE
        if (panelWasVisibleBeforeCapture) {
            panelVisible = true
            panelRoot?.visibility = View.VISIBLE
            panelRoot?.post { positionPanel() }
        }
        panelWasVisibleBeforeCapture = false
        updatePillVisibility()
    }

    // ---------------------------------------------------------------- misc

    private fun positionPanel() {
        val view = panelRoot ?: return
        val params = panelParams ?: return
        if (!panelAdded) return
        if (view.visibility != View.VISIBLE) return
        if (view.height <= 0) {
            schedulePosition(80L)
            return
        }
        screen = displaySize(context)
        params.x = ((screen.x - params.width) / 2).coerceAtLeast(0)
        params.y = (screen.y - view.height - dp(76)).coerceAtLeast(dp(16))
        safeUpdate(view, params)
    }

    private fun schedulePosition(delay: Long = 60L) {
        if (positionPending) return
        positionPending = true
        mainHandler.postDelayed(repositionTask, delay)
    }

    private fun scrollAnswerToBottom() {
        answerScroll?.post { answerScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setDotColor(colorRes: Int) {
        statusDot?.setTextColor(color(colorRes))
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            // View was already detached.
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun onConfigurationChanged() {
        screen = displaySize(context)
        applyPanelSizeLimits()
        bubbleRoot?.let { view ->
            bubbleParams?.let { params ->
                params.x = params.x.coerceIn(
                    dp(24),
                    max(dp(24), screen.x - view.width - dp(24))
                )
                params.y = params.y.coerceIn(
                    dp(24),
                    max(dp(24), screen.y - view.height - dp(48))
                )
                safeUpdate(view, params)
            }
        }
        if (panelAdded) {
            val width = min(screen.x - dp(24), dp(300)).coerceAtLeast(dp(240))
            panelParams?.width = width
            schedulePosition(80L)
        }
        if (pillAdded) positionPill()
    }

    fun release() {
        releasing = true
        mainHandler.removeCallbacksAndMessages(null)
        copyingReset = null
        hidePanel()
        pillRoot?.let { view ->
            if (pillAdded) runCatching { windowManager.removeView(view) }
        }
        pillAdded = false
        pillRoot = null
        pillAnswerView = null
        pillParams = null
        pillContent = ""
        panelRoot?.let { view ->
            if (panelAdded) runCatching { windowManager.removeView(view) }
        }
        panelAdded = false
        panelRoot = null
        panelParams = null
        panelVisible = false
        bubbleRoot?.let { view ->
            if (bubbleAdded) runCatching { windowManager.removeView(view) }
        }
        bubbleAdded = false
        bubbleRoot = null
        bubbleImage = null
        bubbleParams = null
        bubbleHidden = false
        working = false
        hasAnswer = false
        awaitingAddPage = false
    }

    private companion object {
        private const val TAG = "ScreenAnswerOverlay"
        private const val PILL_REFRESH_MS = 90L
        private const val PILL_OCR = "识屏中…"
        private const val PILL_THINKING = "思考中…"
    }
}

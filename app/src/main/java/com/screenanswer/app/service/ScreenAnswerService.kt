package com.screenanswer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.screenanswer.app.MainActivity
import com.screenanswer.app.R
import com.screenanswer.app.accessibility.ScreenReaderService
import com.screenanswer.app.api.AnswerParser
import com.screenanswer.app.api.ApiException
import com.screenanswer.app.api.DeepSeekClient
import com.screenanswer.app.data.Prefs
import com.screenanswer.app.ocr.ScreenOcr
import com.screenanswer.app.overlay.OverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Floating-ball foreground service. Screen frames come from the accessibility service
 * (no MediaProjection and no system screen-sharing prompt), then go through OCR and DeepSeek.
 */
class ScreenAnswerService : Service(), OverlayController.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: Prefs
    private lateinit var client: DeepSeekClient
    private lateinit var ocr: ScreenOcr
    private lateinit var overlay: OverlayController

    private var answerJob: Job? = null
    private var started = false
    private var released = false
    private var isWorking = false

    /** OCR text for each captured screen, in order. Cleared when a fresh scan starts. */
    private val pages = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)
        client = DeepSeekClient(prefs)
        ocr = ScreenOcr()
        createNotificationChannel()
        overlay = OverlayController(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                if (!started) {
                    started = true
                    if (!Settings.canDrawOverlays(this)) {
                        failAndStop("没有悬浮窗权限，请先在系统设置中授权")
                    } else {
                        try {
                            overlay.showBubble()
                            isBubbleHidden = false
                        } catch (t: Throwable) {
                            failAndStop("悬浮球启动失败：${t.message ?: "未知错误"}")
                        }
                    }
                }
            }

            ACTION_STOP -> stopSafely()

            ACTION_SHOW_BUBBLE -> {
                if (started) {
                    overlay.showBubble()
                    isBubbleHidden = false
                } else {
                    stopSelf()
                }
            }

            else -> {
                if (!started) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ overlay actions

    override fun onScanRequested() {
        if (released) return
        if (isWorking) {
            Toast.makeText(this, "正在识屏，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkConfig()) return
        val reader = connectedReader() ?: return
        pages.clear()
        captureAndAnswer(reader, append = false)
    }

    override fun onAddPageRequested() {
        if (released) return
        if (isWorking) {
            Toast.makeText(this, "正在识屏，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        if (pages.isEmpty()) {
            // Nothing collected yet: behave like a normal first scan.
            onScanRequested()
            return
        }
        if (!checkConfig()) return
        val reader = connectedReader() ?: return
        captureAndAnswer(reader, append = true)
    }

    private fun checkConfig(): Boolean {
        if (prefs.apiKey.isBlank()) {
            overlay.showError("还没有配置 DeepSeek API Key，请回到 App 填写后再试", allowRetry = false)
            return false
        }
        return true
    }

    private fun connectedReader(): ScreenReaderService? {
        val reader = ScreenReaderService.instance
        if (reader == null) {
            overlay.showError(
                "无障碍服务未连接。请到 设置 → 无障碍 中开启「识屏答题助手」，" +
                    "然后回到 App 重新点「开启悬浮球」。",
                allowRetry = false
            )
        }
        return reader
    }

    /**
     * Capture the current screen, OCR it, and ask DeepSeek.
     *
     * @param append true for 「加一页」: keep the pages collected so far and add this screen to
     *   them, so a multi-page paper is answered in one request.
     */
    private fun captureAndAnswer(reader: ScreenReaderService, append: Boolean) {
        beginWork()
        answerJob = scope.launch {
            var bitmap: Bitmap? = null
            try {
                overlay.hideForCapture()
                delay(220L)
                bitmap = reader.captureScreen()

                overlay.restoreAfterCapture()
                overlay.showOcrStage()

                val text = ocr.recognize(bitmap).trim()
                bitmap.recycle()
                bitmap = null

                if (text.isBlank()) {
                    overlay.showInfo(
                        if (append) {
                            "这一页没有识别到文字。可以再点一次球重试，或点「重扫」重新开始。"
                        } else {
                            "屏幕上没有识别到文字。请让题目完整显示在屏幕上，再点「重扫」。"
                        },
                        allowRetry = pages.isNotEmpty()
                    )
                    return@launch
                }
                if (!append) pages.clear()
                pages.add(text)
                askDeepSeek()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                overlay.showError(friendlyMessage(t), allowRetry = pages.isNotEmpty())
            } finally {
                bitmap?.let { runCatching { it.recycle() } }
                overlay.restoreAfterCapture()
                endWork()
            }
        }
    }

    override fun onRetryRequested() {
        if (isWorking || released) return
        if (pages.isEmpty()) {
            onScanRequested()
            return
        }
        beginWork()
        answerJob = scope.launch {
            try {
                askDeepSeek()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                overlay.showError(friendlyMessage(t), allowRetry = true)
            } finally {
                endWork()
            }
        }
    }

    override fun onPanelClosed() {
        // Closing the popup only hides the explanation; the ball keeps the answer and an
        // in-flight request keeps streaming into the ball.
    }

    override fun onHideBubble() {
        if (released) return
        answerJob?.cancel()
        overlay.hideBubble()
        isBubbleHidden = true
        // 隐藏时不再弹 Toast：恢复入口在通知栏和 App 里，避免遮挡屏幕底部。
    }

    private suspend fun askDeepSeek() {
        val joined = joinPages()
        val clipped = if (joined.length > MAX_OCR_CHARS) joined.take(MAX_OCR_CHARS) else joined
        overlay.showThinking(preview = pages.lastOrNull().orEmpty(), fullOcr = joined)

        val buffer = StringBuilder()
        client.streamAnswer(clipped).collect { delta ->
            buffer.append(delta)
            val partial = AnswerParser.parse(buffer.toString(), fallbackToWhole = false)
            overlay.updateStreaming(buffer.toString(), partial.answer)
        }

        val raw = buffer.toString().trim()
        if (raw.isBlank()) {
            overlay.showInfo("这次没有收到回答，点「重答」再试一次。", allowRetry = true)
            return
        }
        val parsed = AnswerParser.parse(raw)
        overlay.finishAnswer(raw, parsed.answer, parsed.explanation, pages.size)
    }

    /** Pages are joined with visible markers so the model can answer by page / question number. */
    private fun joinPages(): String {
        if (pages.size <= 1) return pages.firstOrNull().orEmpty()
        return pages.mapIndexed { index, page ->
            "--- 第 ${index + 1} 页 ---\n$page"
        }.joinToString("\n\n")
    }

    private fun beginWork() {
        isWorking = true
        answerJob?.cancel()
        overlay.setBubbleBusy(true)
    }

    private fun endWork() {
        isWorking = false
        if (!released) overlay.setBubbleBusy(false)
    }

    // ------------------------------------------------------------------ lifecycle

    private fun failAndStop(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSafely()
    }

    private fun stopSafely() {
        if (released) return
        released = true
        started = false
        isBubbleHidden = false
        answerJob?.cancel()
        answerJob = null
        if (::overlay.isInitialized) overlay.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        released = true
        isBubbleHidden = false
        answerJob?.cancel()
        answerJob = null
        if (::overlay.isInitialized) runCatching { overlay.release() }
        if (::ocr.isInitialized) runCatching { ocr.close() }
        scope.cancel()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized && !released) {
            overlay.onConfigurationChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ notification

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "识屏服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持悬浮球在后台运行（不使用屏幕共享）"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenAnswerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenAnswerService::class.java).setAction(ACTION_SHOW_BUBBLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("识屏答题助手")
            .setContentText("点球看答案，再点看解析；多页试卷可「加一页」")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "显示悬浮球", showIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    // ------------------------------------------------------------------ helpers

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is ApiException -> t.message
        is SocketTimeoutException -> "请求超时，请检查网络后重试"
        is UnknownHostException, is ConnectException -> "网络连接失败，请检查网络后重试"
        is SecurityException -> t.message?.takeIf { it.isNotBlank() }
            ?: "权限不足，请检查无障碍服务"
        is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "网络请求失败，请重试"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "出了点问题，请重试"
    }

    companion object {
        const val ACTION_START = "com.screenanswer.app.action.START"
        const val ACTION_STOP = "com.screenanswer.app.action.STOP"
        const val ACTION_SHOW_BUBBLE = "com.screenanswer.app.action.SHOW_BUBBLE"

        private const val CHANNEL_ID = "screen_answer_service"
        private const val NOTIFICATION_ID = 42
        private const val MAX_OCR_CHARS = 20000

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isBubbleHidden: Boolean = false
            private set

        fun start(context: Context) {
            isBubbleHidden = false
            val intent = Intent(context, ScreenAnswerService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun showBubble(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, ScreenAnswerService::class.java).apply {
                action = ACTION_SHOW_BUBBLE
            }
            context.startService(intent)
        }
    }
}

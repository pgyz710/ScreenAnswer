package com.screenanswer.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uses Android's accessibility screenshot API (API 30+) instead of MediaProjection.
 *
 * This avoids the system screen-sharing/recording prompt and the screen-cast indicator.
 * The user still has to enable this accessibility service manually, and screenshots of
 * FLAG_SECURE windows remain unavailable by design.
 */
class ScreenReaderService : AccessibilityService() {

    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    suspend fun captureScreen(): Bitmap = suspendCancellableCoroutine { continuation ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < 700L) {
            continuation.resumeWithException(IllegalStateException("截图太频繁，请稍后再试"))
        } else {
            lastCaptureAt = now
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    captureExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            var buffer: HardwareBuffer? = null
                            try {
                                val hardwareBuffer = result.hardwareBuffer
                                buffer = hardwareBuffer
                                val colorSpace = result.colorSpace
                                    ?: ColorSpace.get(ColorSpace.Named.SRGB)
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                    hardwareBuffer,
                                    colorSpace
                                ) ?: throw IllegalStateException("无法读取无障碍截图")
                                val softwareBitmap = hardwareBitmap.copy(
                                    Bitmap.Config.ARGB_8888,
                                    false
                                ) ?: throw IllegalStateException("无法转换无障碍截图")
                                hardwareBitmap.recycle()
                                if (continuation.isActive) {
                                    continuation.resume(softwareBitmap)
                                } else {
                                    softwareBitmap.recycle()
                                }
                            } catch (t: Throwable) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(t)
                                }
                            } finally {
                                runCatching { buffer?.close() }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException(screenshotErrorMessage(errorCode))
                                )
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }
        }
    }

    private fun screenshotErrorMessage(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "截图太频繁，请稍后再试"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "无障碍权限未生效，请关闭后重新开启无障碍服务"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "当前屏幕不可用，请解锁手机后重试"
        else ->
            "无障碍截图失败（错误码 $code）。如果当前页面禁止截屏，请换一个页面重试"
    }

    companion object {
        @Volatile
        var instance: ScreenReaderService? = null
            private set
    }
}

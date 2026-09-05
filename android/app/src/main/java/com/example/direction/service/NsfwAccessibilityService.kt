package com.example.direction.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.example.direction.manager.ShizukuManager
import com.example.direction.repository.SettingsRepository
import com.example.direction.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 无障碍监控服务
 * 通过 AccessibilityService.takeScreenshot() 抓屏（API 30+），实现「一次授权、持续监控」。
 * 系统绑定后自动开机运行、进程被杀自动重启，无需 MediaProjection 单次令牌。
 */
class NsfwAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NsfwAccessibilityService"

        /**
         * 判断本应用的无障碍服务是否已在系统设置中开启
         */
        fun isEnabled(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabledServices.any { info ->
                    info.resolveInfo.serviceInfo.name == NsfwAccessibilityService::class.java.name
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "检查无障碍服务状态失败", e)
                false
            }
        }
    }

    private val settingsRepository by lazy { SettingsRepository(this) }
    private val detectionProcessor by lazy { DetectionProcessor(this) }
    private val shizukuManager by lazy { ShizukuManager() }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkTask: Runnable? = null
    private var isRunning = false
    private var currentDetectionInterval = 1 * 60 * 1000L // 默认1分钟

    // 上一帧检测是否未完成（防止 takeScreenshot 异步回调与周期调度重叠）
    @Volatile
    private var isCapturing = false

    // 无障碍监控开关（onServiceConnected 时读取）
    @Volatile
    private var monitorEnabled = true

    // 无障碍事件缓存的前台应用包名（TYPE_WINDOW_STATE_CHANGED 的 packageName）
    @Volatile
    private var cachedForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogUtils.i(TAG, "无障碍服务已连接")
        CoroutineScope(Dispatchers.Main).launch {
            monitorEnabled = try {
                settingsRepository.accessibilityMonitorEnabled.first()
            } catch (e: Exception) {
                LogUtils.e(TAG, "读取无障碍监控开关失败，使用默认值true", e)
                true
            }

            if (!monitorEnabled) {
                LogUtils.i(TAG, "无障碍监控开关未启用，不启动检测")
                return@launch
            }

            // 无障碍优先：停掉手动录屏服务，避免双循环导致重复历史/通知/强杀
            NsfwMonitorService.stopService(this@NsfwAccessibilityService)
            startPeriodicDetection()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null) {
                cachedForegroundPackage = pkg
                shizukuManager.cacheForegroundPackage(pkg)
                LogUtils.d(TAG, "前台应用切换: $pkg")
            }
        }
    }

    override fun onInterrupt() {
        LogUtils.d(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        LogUtils.i(TAG, "无障碍服务销毁")
        stopPeriodicDetection()
        super.onDestroy()
    }

    /**
     * 启动周期性检测（镜像 NsfwMonitorService 的调度逻辑）
     */
    private fun startPeriodicDetection() {
        if (isRunning) {
            LogUtils.i(TAG, "周期性检测已在运行")
            return
        }
        LogUtils.i(TAG, "启动周期性检测")

        checkTask = Runnable {
            captureAndDetect()
            // 调度下一次检测
            if (isRunning) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val intervalMinutes = settingsRepository.detectionInterval.first()
                        currentDetectionInterval = intervalMinutes * 60 * 1000L
                        LogUtils.i(TAG, "当前检测间隔: ${intervalMinutes}分钟 (${currentDetectionInterval}ms)")
                        mainHandler.post {
                            if (isRunning) {
                                mainHandler.postDelayed(checkTask!!, currentDetectionInterval)
                            }
                        }
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "读取检测间隔失败", e)
                        mainHandler.post {
                            if (isRunning) {
                                mainHandler.postDelayed(checkTask!!, currentDetectionInterval)
                            }
                        }
                    }
                }
            }
        }

        mainHandler.post(checkTask!!)
        isRunning = true
        LogUtils.i(TAG, "周期性检测已启动")
    }

    /**
     * 停止周期性检测
     */
    private fun stopPeriodicDetection() {
        LogUtils.i(TAG, "停止周期性检测")
        checkTask?.let { mainHandler.removeCallbacks(it) }
        checkTask = null
        isRunning = false
    }

    /**
     * 抓屏并检测
     */
    private fun captureAndDetect() {
        if (!monitorEnabled) {
            LogUtils.d(TAG, "无障碍监控未启用，跳过")
            return
        }
        if (isCapturing) {
            LogUtils.d(TAG, "上一帧检测未完成，跳过")
            return
        }
        if (!isScreenOn()) {
            LogUtils.d(TAG, "屏幕已熄灭，跳过")
            return
        }

        isCapturing = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = toSoftwareBitmap(screenshot)
                    if (bitmap == null) {
                        LogUtils.e(TAG, "截图转换失败")
                        isCapturing = false
                        return
                    }
                    LogUtils.d(TAG, "截图成功，尺寸: ${bitmap.width}x${bitmap.height}")
                    val fg = cachedForegroundPackage
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            detectionProcessor.process(bitmap, fg)
                        } catch (e: Exception) {
                            LogUtils.e(TAG, "检测异常", e)
                        } finally {
                            isCapturing = false
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    LogUtils.e(TAG, "截图失败: ${describeScreenshotError(errorCode)}")
                    isCapturing = false
                }
            })
        } catch (e: Exception) {
            LogUtils.e(TAG, "takeScreenshot调用失败", e)
            isCapturing = false
        }
    }

    /**
     * 将 ScreenshotResult（HardwareBuffer）转换为软件位图
     * 硬件位图无法直接 getPixels，必须先 copy 为软件位图。
     */
    private fun toSoftwareBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val hardwareBuffer = screenshot.hardwareBuffer
        return try {
            val colorSpace = screenshot.colorSpace
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return null
            val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            soft
        } catch (e: Exception) {
            LogUtils.e(TAG, "ScreenshotResult → Bitmap 转换失败", e)
            null
        } finally {
            try {
                hardwareBuffer.close()
            } catch (e: Exception) {
                LogUtils.e(TAG, "关闭HardwareBuffer失败", e)
            }
        }
    }

    /**
     * 检查屏幕是否点亮
     */
    private fun isScreenOn(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 描述 takeScreenshot 失败原因
     */
    private fun describeScreenshotError(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
        else -> "UNKNOWN($errorCode)"
    }
}

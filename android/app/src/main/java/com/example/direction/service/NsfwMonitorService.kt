package com.example.direction.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.util.DisplayMetrics
import android.util.Log
import com.example.direction.utils.LogUtils
import androidx.core.app.NotificationCompat
import com.example.direction.R
import com.example.direction.detector.classifier.NSFWClassifier
import com.example.direction.detector.classifier.BackendNsfwDetector
import com.example.direction.model.DetectionResult
import com.example.direction.model.DebugImageData
import com.example.direction.repository.DetectionRepository
import com.example.direction.utils.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * NSFW监控前台服务
 * 简化的服务实现，定期检测屏幕内容
 */
class NsfwMonitorService : Service() {

    companion object {
        private const val TAG = "NsfwMonitorService"

        // 通知配置
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nsfw_monitor_service"

        // 图像捕获配置
        private const val VIRTUAL_DISPLAY_NAME = "ScreenCapture"
        private const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        private const val MAX_IMAGES = 2

        // Intent额外数据键
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_INTENT = "result_intent"

        // 广播Action
        const val ACTION_MEDIA_PROJECTION_STOPPED = "com.example.direction.action.MEDIA_PROJECTION_STOPPED"
        const val ACTION_NSFW_DETECTED = "com.example.direction.action.NSFW_DETECTED"

        /**
         * 启动NSFW监控服务
         */
        fun startService(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, NsfwMonitorService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_INTENT, data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止NSFW监控服务
         */
        fun stopService(context: Context) {
            val intent = Intent(context, NsfwMonitorService::class.java)
            context.stopService(intent)
        }
    }

    // MediaProjection相关
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            LogUtils.i(TAG, "MediaProjection已停止")
            // 清理捕获资源（虚拟显示、ImageReader）并停止检测
            cleanupCaptureResources()
        }
    }

    // 显示配置
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0

    // 组件
    private val nsfwClassifier by lazy { NSFWClassifier(this) }
    private val notificationUtils by lazy { NotificationUtils(this) }
    private val detectionRepository by lazy { DetectionRepository(this) }
    private val settingsRepository by lazy { com.example.direction.repository.SettingsRepository(this) }

    // 定时任务相关
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkTask: Runnable? = null
    private var isRunning = false

    // 当前检测间隔（毫秒）
    private var currentDetectionInterval = 1 * 60 * 1000L // 默认1分钟

    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "NsfwMonitorService创建")

        // 初始化组件
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 获取显示配置
        val displayMetrics = resources.displayMetrics
        displayWidth = displayMetrics.widthPixels
        displayHeight = displayMetrics.heightPixels
        displayDensity = displayMetrics.densityDpi

        LogUtils.i(TAG, "显示配置: ${displayWidth}x${displayHeight}, DPI: $displayDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.i(TAG, "NsfwMonitorService启动命令")

        // 处理启动意图
        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Integer.MIN_VALUE)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)
            }

            if (resultCode != Integer.MIN_VALUE) {
                LogUtils.i(TAG, "启动参数: resultCode=$resultCode, resultData=${if (resultData != null) "非空" else "空"}")
                // 只有有效intent才启动前台服务
                createNotificationChannel()
                val notification = createNotification()
                // Android 10+ (API 29+) 需要指定服务类型常量
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                setupMediaProjection(resultCode, resultData)
            } else {
                LogUtils.w(TAG, "启动参数缺少resultCode")
                stopSelf()
            }
        } else {
            LogUtils.w(TAG, "启动意图为空 - 系统重启，直接停止服务")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LogUtils.i(TAG, "NsfwMonitorService销毁")
        cleanupResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 设置MediaProjection
     */
    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        try {
            LogUtils.i(TAG, "设置MediaProjection: resultCode=$resultCode, data=${if (data != null) "非空" else "空"}")

            mediaProjection = if (data != null) {
                mediaProjectionManager.getMediaProjection(resultCode, data)
            } else {
                LogUtils.e(TAG, "data为空，无法获取MediaProjection")
                null
            }

            mediaProjection?.let { projection ->
                try {
                    // 注册回调以响应MediaProjection状态
                    projection.registerCallback(mediaProjectionCallback, mainHandler)
                    LogUtils.i(TAG, "MediaProjection回调已注册")

                    // 设置虚拟显示和图像读取器
                    setupVirtualDisplay()

                    LogUtils.i(TAG, "MediaProjection设置完成")
                    // 启动周期性检测
                    startPeriodicDetection()
                } catch (e: Exception) {
                    LogUtils.e(TAG, "设置MediaProjection回调或虚拟显示失败", e)
                    stopSelf()
                }
            } ?: run {
                LogUtils.e(TAG, "无法获取MediaProjection实例，需要重新授权")
                stopSelf()
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "设置MediaProjection失败", e)
            stopSelf()
        }
    }

    /**
     * 设置虚拟显示和图像读取器
     */
    private fun setupVirtualDisplay() {
        LogUtils.i(TAG, "开始设置虚拟显示")
        try {
            // 创建ImageReader
            imageReader = ImageReader.newInstance(
                displayWidth,
                displayHeight,
                android.graphics.PixelFormat.RGBA_8888,
                MAX_IMAGES
            )

            // 创建虚拟显示
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                displayWidth,
                displayHeight,
                displayDensity,
                VIRTUAL_DISPLAY_FLAGS,
                imageReader?.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        LogUtils.d(TAG, "虚拟显示已暂停")
                    }

                    override fun onResumed() {
                        LogUtils.d(TAG, "虚拟显示已恢复")
                    }

                    override fun onStopped() {
                        LogUtils.d(TAG, "虚拟显示已停止")
                    }
                },
                mainHandler
            )

            if (virtualDisplay != null) {
                LogUtils.i(TAG, "虚拟显示创建成功")
            } else {
                LogUtils.e(TAG, "虚拟显示创建失败")
                stopSelf()
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "设置虚拟显示失败", e)
            stopSelf()
        }
    }

    /**
     * 执行屏幕截图
     */
    private fun captureScreen(): Bitmap? {
        if (mediaProjection == null || imageReader == null) {
            LogUtils.w(TAG, "MediaProjection或ImageReader未设置，无法截图")
            return null
        }

        return try {
            // 从ImageReader获取最新图像
            val image = imageReader?.acquireLatestImage()
            image?.use { img ->
                convertImageToBitmap(img)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "截图失败", e)
            null
        }
    }

    /**
     * 将Image转换为Bitmap
     */
    private fun convertImageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            LogUtils.e(TAG, "转换图像为Bitmap失败", e)
            null
        }
    }

    /**
     * 创建Bitmap的副本
     */
    private fun createBitmapCopy(original: Bitmap): Bitmap {
        return Bitmap.createBitmap(original)
    }

    /**
     * 启动周期性检测
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
                // 读取当前检测间隔设置
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val intervalMinutes = settingsRepository.detectionInterval.first()
                        currentDetectionInterval = intervalMinutes * 60 * 1000L
                        LogUtils.i(TAG, "当前检测间隔: ${intervalMinutes}分钟 (${currentDetectionInterval}ms)")

                        // 回到主线程调度下一次检测
                        mainHandler.post {
                            if (isRunning) {
                                mainHandler.postDelayed(checkTask!!, currentDetectionInterval)
                                LogUtils.d(TAG, "已调度下一次检测，间隔: ${currentDetectionInterval}ms")
                            }
                        }
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "读取检测间隔失败", e)
                        // 使用默认间隔
                        mainHandler.post {
                            if (isRunning) {
                                mainHandler.postDelayed(checkTask!!, currentDetectionInterval)
                                LogUtils.d(TAG, "使用默认间隔调度下一次检测，间隔: ${currentDetectionInterval}ms")
                            }
                        }
                    }
                }
            }
        }

        // 立即执行第一次检测
        mainHandler.post(checkTask!!)
        isRunning = true
        LogUtils.i(TAG, "周期性检测已启动")
    }

    /**
     * 停止周期性检测
     */
    private fun stopPeriodicDetection() {
        LogUtils.i(TAG, "停止周期性检测")
        checkTask?.let {
            mainHandler.removeCallbacks(it)
        }
        checkTask = null
        isRunning = false
    }

    /**
     * 捕获屏幕并检测NSFW内容
     */
    private fun captureAndDetect() {
        LogUtils.i(TAG, "开始检测屏幕内容")
        val startTime = System.currentTimeMillis()

        // 在后台线程执行检测以避免阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            try {

                // 1. 截图
                LogUtils.d(TAG, "开始截图")
                val screenshot = captureScreen()
                if (screenshot == null) {
                    LogUtils.e(TAG, "截图失败")
                    return@launch
                }
                LogUtils.d(TAG, "截图成功，尺寸: ${screenshot.width}x${screenshot.height}")

                // 2. 分类
                LogUtils.d(TAG, "开始NSFW分类")
                // 获取当前NSFW阈值
                val nsfwThreshold = try {
                    settingsRepository.nsfwThreshold.first()
                } catch (e: Exception) {
                    LogUtils.e(TAG, "读取NSFW阈值失败，使用默认值", e)
                    NSFWClassifier.DEFAULT_NSFW_THRESHOLD
                }
                LogUtils.d(TAG, "使用NSFW阈值: $nsfwThreshold")
                val androidResult = nsfwClassifier.classify(screenshot, nsfwThreshold)
                LogUtils.d(TAG, "Android分类完成: isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}, error=${androidResult.error}")

                // 后台检测逻辑：根据设置和Android检测结果决定是否调用远程检测
                // 规则：
                // 1. 检查backendEnabled开关，如果为false则不调用远程检测
                // 2. 如果backendEnabled为true，但Android检测结果为NSFW，则不调用远程检测
                // 3. 只有Android检测结果为SFW时才调用远程检测进行兜底验证
                var backendResult: DetectionResult? = null
                var finalResult = androidResult

                // 检查后端是否启用
                val backendEnabled = try {
                    settingsRepository.backendEnabled.first()
                } catch (e: Exception) {
                    LogUtils.e(TAG, "读取后端启用状态失败，使用默认值", e)
                    true // 默认启用
                }
                LogUtils.i(TAG, "后台检测决策: backendEnabled=$backendEnabled, androidResult.isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}")

                if (backendEnabled) {
                    // 读取后端URL
                    val backendUrl = try {
                        val url = settingsRepository.backendUrl.first()
                        LogUtils.i(TAG, "读取后端URL: $url")
                        url
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "读取后端URL失败", e)
                        null
                    }

                    if (backendUrl != null && backendUrl.isNotEmpty()) {
                        // 检查Android检测结果，只有SFW时才调用远程检测
                        if (!androidResult.isNSFW) {
                            LogUtils.i(TAG, "Android检测结果为SFW（isNSFW=false），启动后端兜底检测")
                            try {
                                // 执行后端检测（无超时限制）
                                backendResult = performFallbackDetection(screenshot, backendUrl, nsfwThreshold)

                                if (backendResult != null) {
                                    LogUtils.i(TAG, "后端兜底检测完成: isNsfw=${backendResult.backendIsNsfw}, confidence=${backendResult.backendConfidence}")
                                    // 合并结果
                                    finalResult = mergeDetectionResults(androidResult, backendResult)
                                    LogUtils.i(TAG, "合并后最终结果: isNSFW=${finalResult.isNSFW}, 后端结果: ${backendResult.backendIsNsfw}")
                                } else {
                                    LogUtils.w(TAG, "后端检测返回null，使用Android结果")
                                    finalResult = androidResult
                                }
                            } catch (e: Exception) {
                                LogUtils.w(TAG, "后端检测失败: ${e.message}")
                                // 失败时，使用Android结果
                                finalResult = androidResult
                            }
                        } else {
                            LogUtils.i(TAG, "Android检测结果为NSFW（isNSFW=true），跳过远程检测，直接使用Android结果")
                            finalResult = androidResult
                        }
                    } else {
                        LogUtils.w(TAG, "后端URL为空或无效，无法执行远程检测")
                        finalResult = androidResult
                    }
                } else {
                    LogUtils.i(TAG, "后端检测已禁用，仅使用Android检测结果")
                    finalResult = androidResult
                }

                // 3. 保存结果（NSFW和SFW内容都保存截图）
                // 为保存创建副本，立即回收原始Bitmap以释放内存
                val screenshotCopy = createBitmapCopy(screenshot)
                screenshot.recycle() // 立即回收原始Bitmap

                // 读取调试图片保存设置
                val saveDebugImages = try {
                    settingsRepository.saveDebugImages.first()
                } catch (e: Exception) {
                    LogUtils.e(TAG, "读取调试图片保存设置失败，使用默认值false", e)
                    false // 默认不保存调试图片
                }
                LogUtils.d(TAG, "调试图片保存设置: $saveDebugImages")

                detectionRepository.saveResult(finalResult, screenshotCopy, null, saveDebugImages)
                LogUtils.d(TAG, "结果已保存（包含截图）")

                // 4. 发送通知和震动（仅NSFW）
                if (finalResult.isNSFW) {
                    LogUtils.i(TAG, "检测到NSFW内容，检查通知和震动设置")

                    // 读取通知和震动设置
                    val notificationEnabled = try {
                        settingsRepository.notificationEnabled.first()
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "读取通知设置失败，使用默认值", e)
                        true // 默认启用
                    }

                    val vibrationEnabled = try {
                        settingsRepository.vibrationEnabled.first()
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "读取震动设置失败，使用默认值", e)
                        true // 默认启用
                    }

                    LogUtils.d(TAG, "通知设置: enabled=$notificationEnabled, 震动设置: enabled=$vibrationEnabled")

                    // 震动和通知独立执行，互不影响
                    if (vibrationEnabled) {
                        LogUtils.i(TAG, "触发震动提醒")
                        // 在独立协程中执行震动，避免阻塞通知
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                vibrate()
                            } catch (e: Exception) {
                                LogUtils.e(TAG, "震动执行失败", e)
                            }
                        }
                    } else {
                        LogUtils.i(TAG, "震动提醒已禁用")
                    }

                    if (notificationEnabled) {
                        LogUtils.i(TAG, "发送通知")
                        // 在独立协程中发送通知，避免阻塞震动
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                notificationUtils.sendDetectionNotification(finalResult, enableVibration = false)
                            } catch (e: Exception) {
                                LogUtils.e(TAG, "通知发送失败", e)
                            }
                        }
                    } else {
                        LogUtils.i(TAG, "通知已禁用")
                    }

                    // 发送NSFW检测广播（用于悬浮窗警告）
                    sendNsfwDetectedBroadcast()

                    // 如果两者都禁用，至少记录日志
                    if (!notificationEnabled && !vibrationEnabled) {
                        LogUtils.i(TAG, "通知和震动均被禁用，仅记录检测结果")
                    }
                } else {
                    LogUtils.i(TAG, "SFW内容，不发送通知")
                }

                // 5. 清理资源已完成（Bitmap在步骤3中已回收）
                LogUtils.d(TAG, "资源已清理")

                val elapsed = System.currentTimeMillis() - startTime
                LogUtils.i(TAG, "检测完成: ${if (finalResult.isNSFW) "NSFW" else "SFW"}, 耗时: ${elapsed}ms")
            } catch (e: Exception) {
                LogUtils.e(TAG, "检测异常", e)
            }
        }
    }

    /**
     * 清理捕获资源（虚拟显示、ImageReader）并停止检测
     * 用于MediaProjection停止时的回调
     */
    private fun cleanupCaptureResources() {
        LogUtils.i(TAG, "清理捕获资源")

        // 停止周期性检测
        stopPeriodicDetection()

        // 清理虚拟显示
        virtualDisplay?.release()
        virtualDisplay = null

        // 清理ImageReader
        imageReader?.close()
        imageReader = null

        // MediaProjection已停止，将引用置为null
        mediaProjection = null

        // 通知UI MediaProjection已停止
        notifyMediaProjectionStopped()

        // 停止服务自身，因为MediaProjection已停止
        stopSelf()
    }

    /**
     * 通知UI MediaProjection已停止
     */
    private fun notifyMediaProjectionStopped() {
        try {
            val intent = Intent(ACTION_MEDIA_PROJECTION_STOPPED).apply { setPackage("com.example.direction") }
            sendBroadcast(intent)
            LogUtils.i(TAG, "已发送MediaProjection停止广播")
        } catch (e: Exception) {
            LogUtils.e(TAG, "发送MediaProjection停止广播失败", e)
        }
    }

    /**
     * 发送NSFW检测广播
     * 用于触发悬浮窗警告
     */
    private fun sendNsfwDetectedBroadcast() {
        try {
            LogUtils.d(TAG, "准备发送NSFW检测广播")
            val intent = Intent(ACTION_NSFW_DETECTED).apply { setPackage("com.example.direction") }
            LogUtils.d(TAG, "广播Intent创建完成: action=$ACTION_NSFW_DETECTED, package=com.example.direction")

            sendBroadcast(intent)
            LogUtils.i(TAG, "已发送NSFW检测广播")
            LogUtils.d(TAG, "广播发送完成")
        } catch (e: Exception) {
            LogUtils.e(TAG, "发送NSFW检测广播失败", e)
        }
    }

    /**
     * 清理资源
     */
    private fun cleanupResources() {
        LogUtils.i(TAG, "清理资源")

        // 停止周期性检测
        stopPeriodicDetection()

        // 清理虚拟显示
        virtualDisplay?.release()
        virtualDisplay = null

        // 清理ImageReader
        imageReader?.close()
        imageReader = null

        // 注销MediaProjection回调
        mediaProjection?.unregisterCallback(mediaProjectionCallback)

        // 停止MediaProjection
        mediaProjection?.stop()
        mediaProjection = null

    }

    /**
     * 触发震动提醒
     * 使用3次震动模式：0ms延迟，1000ms震动，250ms暂停，1000ms震动，250ms暂停，1000ms震动
     */
    private fun vibrate() {
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            // 3次震动模式：0, 1000, 250, 1000, 250, 1000
            val vibrationPattern = longArrayOf(0, 1000, 250, 1000, 250, 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 VibrationEffect
                vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
            } else {
                // 旧版本使用 deprecated 方法
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationPattern, -1)
            }
            LogUtils.d(TAG, "震动提醒已触发，3次震动模式")
        } catch (e: Exception) {
            LogUtils.e(TAG, "震动失败", e)
        }
    }

    /**
     * 执行后端兜底检测
     * @param screenshot 截图
     * @param backendUrl 后端服务URL
     * @param backendThreshold 后端阈值
     * @return 后端检测结果，如果失败返回null
     */
    private suspend fun performFallbackDetection(
        screenshot: Bitmap,
        backendUrl: String,
        backendThreshold: Float
    ): DetectionResult? {
        return try {
            LogUtils.i(TAG, "开始后端兜底检测，URL: $backendUrl")
            val backendDetector = BackendNsfwDetector(backendUrl, backendThreshold)
            backendDetector.detect(screenshot)
        } catch (e: Exception) {
            LogUtils.e(TAG, "后端兜底检测失败", e)
            null
        }
    }

    /**
     * 检查是否需要兜底检测
     * @param androidResult 本地Android检测结果
     * @param thresholdMargin 阈值容差（当前未使用，但保留参数以保持兼容性）
     * @return 如果需要兜底返回true
     */
    private fun shouldFallbackToBackend(
        androidResult: DetectionResult,
        thresholdMargin: Float
    ): Boolean {
        // 如果Android检测结果有错误，则兜底
        if (androidResult.error != null) {
            LogUtils.d(TAG, "Android检测有错误，需要后端兜底")
            return true
        }
        // 只要前台检测结果为SFW（isNSFW为false）就开启后端检测
        val shouldFallback = !androidResult.isNSFW
        if (shouldFallback) {
            LogUtils.d(TAG, "Android检测结果为SFW（isNSFW=false），需要后端兜底验证")
        } else {
            LogUtils.d(TAG, "Android检测结果为NSFW（isNSFW=true），不需要后端兜底")
        }
        return shouldFallback
    }

    /**
     * 合并Android和后端检测结果
     * 最终isNSFW = isAndroidNsfw || isBackEndNsfw
     * 根据Android检测结果和设置决定保存哪些后端信息：
     * 1. 如果Android检测结果为NSFW：只保存基本后端信息（清除modelResults, yoloResult, debugImages）
     * 2. 如果Android检测结果为SFW：保存完整的后端信息
     * 3. 如果saveDebugImages为false：清除debugImages（即使Android结果为SFW）
     */
    private suspend fun mergeDetectionResults(
        androidResult: DetectionResult,
        backendResult: DetectionResult?
    ): DetectionResult {
        if (backendResult == null) {
            // 无后端结果，返回Android结果
            return androidResult
        }

        // 合并结果：最终NSFW判定为两者任一判定为NSFW
        val finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw == true

        // 读取调试图片保存设置
        val saveDebugImages = try {
            settingsRepository.saveDebugImages.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取调试图片保存设置失败", e)
            false
        }

        // 决定保存哪些后端信息
        val shouldSaveFullBackendInfo = !androidResult.isNSFW // SFW时保存完整信息

        val modelOutputToSave = if (shouldSaveFullBackendInfo) backendResult.modelOutput else null
        val yoloResultToSave = if (shouldSaveFullBackendInfo) backendResult.yoloResult else null
        val debugImagesToSave = if (shouldSaveFullBackendInfo && saveDebugImages) {
            // 清理和限制debugImages：清空Base64数据，只保留关键图片
            cleanDebugImages(backendResult.debugImages)
        } else null

        LogUtils.d(TAG, "合并结果决策: Android.isNSFW=${androidResult.isNSFW}, shouldSaveFullBackendInfo=$shouldSaveFullBackendInfo, saveDebugImages=$saveDebugImages")

        // 创建新的DetectionResult，根据条件保存后端信息
        return androidResult.copy(
            isNSFW = finalIsNsfw,
            backendIsNsfw = backendResult.backendIsNsfw,
            backendConfidence = backendResult.backendConfidence,
            backendThreshold = backendResult.backendThreshold,
            backendRawScores = backendResult.backendRawScores,
            modelOutput = modelOutputToSave,
            yoloResult = yoloResultToSave,
            debugImages = debugImagesToSave,
            backendResponseRaw = backendResult.backendResponseRaw
        )
    }

    /**
     * 清理调试图片：保留原始Base64数据，由DetectionRepository负责保存和清理
     * @param debugImages 原始调试图片Map
     * @return 原始的debugImages，DetectionRepository会保存文件并清空Base64
     */
    private fun cleanDebugImages(debugImages: Map<String, DebugImageData>?): Map<String, DebugImageData>? {
        if (debugImages == null) return null

        LogUtils.d(TAG, "debugImages清理: 数量=${debugImages.size}, 键=${debugImages.keys}")
        // 不修改，返回原始数据，由DetectionRepository处理保存和清理
        return debugImages
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NSFW内容检测",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在检测屏幕内容"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NSFW内容检测运行中")
            .setContentText("每分钟检测一次屏幕内容")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}
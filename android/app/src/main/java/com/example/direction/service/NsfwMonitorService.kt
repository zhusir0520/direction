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
import android.util.DisplayMetrics
import com.example.direction.utils.LogUtils
import androidx.core.app.NotificationCompat
import com.example.direction.R
import com.example.direction.manager.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private val settingsRepository by lazy { com.example.direction.repository.SettingsRepository(this) }
    private val shizukuManager by lazy { ShizukuManager() }
    private val detectionProcessor by lazy { DetectionProcessor(this) }

    // 定时任务相关
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkTask: Runnable? = null
    private var isRunning = false

    // 当前检测间隔（毫秒）
    private var currentDetectionInterval = 1 * 60 * 1000L // 默认1分钟

    // 当前检测协程Job，用于取消上一次检测的延迟回调
    private var detectionJob: Job? = null

    // 标记是否为NSFW检测触发的主动关闭
    private var isNsfwShutdown = false

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

        // 使用START_STICKY：如果进程被系统杀死，系统会尝试重启服务
        // 注意：MediaProjection权限无法跨进程存活，重启后需要用户重新授权
        return START_STICKY
    }

    override fun onDestroy() {
        LogUtils.i(TAG, "NsfwMonitorService销毁, isNsfwShutdown=$isNsfwShutdown")
        if (!isNsfwShutdown) {
            // 非NSFW主动关闭，记录调用栈排查异常关闭原因
            LogUtils.w(TAG, "服务非预期关闭!", Exception("服务非预期关闭调用栈"))
        }
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
            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmapHeight = image.height

            // 记录图像属性，便于排查视频截图问题
            LogUtils.d(TAG, "图像属性: format=${image.format}, width=${image.width}, height=${image.height}, " +
                    "pixelStride=$pixelStride, rowStride=$rowStride, rowPadding=$rowPadding, " +
                    "计算宽=$bitmapWidth, buffer大小=${buffer.remaining()}")

            // 检查Bitmap尺寸是否异常（超过屏幕尺寸太多可能导致OOM）
            if (bitmapWidth > displayWidth * 2 || bitmapHeight > displayHeight * 2) {
                LogUtils.w(TAG, "图像尺寸异常: ${bitmapWidth}x${bitmapHeight}, 屏幕: ${displayWidth}x${displayHeight}")
            }

            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)
            LogUtils.d(TAG, "Bitmap创建成功: ${bitmap.width}x${bitmap.height}, 内存≈${bitmap.allocationByteCount / 1024}KB")
            bitmap
        } catch (e: Exception) {
            LogUtils.e(TAG, "转换图像为Bitmap失败", e)
            null
        }
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

        // 取消上一次检测协程（防止旧NSFW检测的delay(5000)在新检测完成后意外触发stopSelf）
        detectionJob?.cancel()
        detectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 0. 在截图前缓存前台应用包名（此时用户正在使用目标应用）
                // 避免后续 DetectionResultActivity 跳转导致 mCurrentFocus=null
                shizukuManager.updateCachedForegroundPackage()
                LogUtils.d(TAG, "缓存的前台应用包名: ${shizukuManager.getCachedForegroundPackage()}")

                // 1. 截图
                LogUtils.d(TAG, "开始截图")
                val screenshot = captureScreen()
                if (screenshot == null) {
                    LogUtils.e(TAG, "截图失败")
                    return@launch
                }
                LogUtils.d(TAG, "截图成功，尺寸: ${screenshot.width}x${screenshot.height}")

                // 2. 交给共享检测管线处理（分类 → 后端兜底 → 保存 → 通知/震动/悬浮窗/强杀/详情页）
                detectionProcessor.process(screenshot, shizukuManager.getCachedForegroundPackage())

                val elapsed = System.currentTimeMillis() - startTime
                LogUtils.i(TAG, "检测完成，耗时: ${elapsed}ms")
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
     * 释放MediaProjection录屏资源（NSFW检测到后主动调用）
     * 先停止录屏再回到前台，避免vivo OriginOS强制停止MediaProjection
     */
    private fun releaseMediaProjectionForNsfw() {
        LogUtils.i(TAG, "NSFW检测到，主动释放录屏资源")
        try {
            stopPeriodicDetection()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            notifyMediaProjectionStopped()
            LogUtils.i(TAG, "录屏资源已主动释放")
        } catch (e: Exception) {
            LogUtils.e(TAG, "释放录屏资源失败", e)
        }
    }

    /**
     * 将应用带到前台
     */
    private fun bringAppToForeground() {
        try {
            LogUtils.i(TAG, "尝试将应用带到前台")
            val intent = Intent(this, com.example.direction.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            LogUtils.i(TAG, "MainActivity已启动")
        } catch (e: Exception) {
            LogUtils.e(TAG, "将应用带到前台失败", e)
        }
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
package com.example.direction.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import com.example.direction.DetectionResultActivity
import com.example.direction.detector.classifier.BackendNsfwDetector
import com.example.direction.detector.classifier.NSFWClassifier
import com.example.direction.manager.FloatingWindowManager
import com.example.direction.manager.ShizukuManager
import com.example.direction.model.DebugImageData
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.repository.SettingsRepository
import com.example.direction.utils.LogUtils
import com.example.direction.utils.NotificationUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream

/**
 * 共享检测管线
 * 从截图之后的逻辑（分类 → 后端兜底 → 保存 → 通知/震动/悬浮窗/强杀/详情页）封装为可复用组件。
 * 供 MediaProjection 路径（NsfwMonitorService）与无障碍路径（NsfwAccessibilityService）共用。
 *
 * @param context 用于访问系统服务、组件与存储的上下文
 */
class DetectionProcessor(private val context: Context) {

    companion object {
        private const val TAG = "DetectionProcessor"
    }

    private val nsfwClassifier by lazy { NSFWClassifier(context) }
    private val settingsRepository by lazy { SettingsRepository(context) }
    private val detectionRepository by lazy { DetectionRepository(context) }
    private val notificationUtils by lazy { NotificationUtils(context) }
    private val shizukuManager by lazy { ShizukuManager() }

    /**
     * 处理一张截图：分类、后端兜底、保存、并在 NSFW 时触发通知/震动/悬浮窗/强杀/详情页。
     *
     * 注意：本方法会负责回收传入的 [screenshot]（调用方不得复用）。
     *
     * @param screenshot 待检测的截图（软件位图）
     * @param foregroundPackage 截图时前台应用包名（用于 Shizuku 强杀目标）
     * @return 合并后的最终检测结果
     */
    suspend fun process(screenshot: Bitmap, foregroundPackage: String?): DetectionResult {
        LogUtils.i(TAG, "开始处理检测结果")

        // 1. 分类
        val nsfwThreshold = try {
            settingsRepository.nsfwThreshold.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取NSFW阈值失败，使用默认值", e)
            NSFWClassifier.DEFAULT_NSFW_THRESHOLD
        }
        LogUtils.d(TAG, "使用NSFW阈值: $nsfwThreshold")
        val androidResult = nsfwClassifier.classify(screenshot, nsfwThreshold)
        LogUtils.d(TAG, "Android分类完成: isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}, error=${androidResult.error}")

        // 2. 后端兜底检测
        val finalResult = runBackendFallback(androidResult, screenshot, nsfwThreshold)

        // 3. 保存结果（NSFW和SFW内容都保存截图）
        val screenshotCopy = createBitmapCopy(screenshot)
        screenshot.recycle()

        val saveDebugImages = try {
            settingsRepository.saveDebugImages.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取调试图片保存设置失败，使用默认值false", e)
            false
        }
        LogUtils.d(TAG, "调试图片保存设置: $saveDebugImages")

        // 在saveResult之前保存临时截图文件（saveResult会回收Bitmap）
        val tempScreenshotFile = File(context.cacheDir, "temp_screenshot_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(tempScreenshotFile).use { out ->
                screenshotCopy.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            LogUtils.d(TAG, "临时截图已保存: ${tempScreenshotFile.absolutePath}")
        } catch (e: Exception) {
            LogUtils.e(TAG, "保存临时截图失败", e)
        }

        detectionRepository.saveResult(finalResult, screenshotCopy, null, saveDebugImages)
        LogUtils.d(TAG, "结果已保存（包含截图）")

        // 4. 发送通知和震动（仅NSFW）
        if (finalResult.isNSFW) {
            LogUtils.i(TAG, "检测到NSFW内容，检查通知和震动设置")
            handleNsfwActions(finalResult, foregroundPackage, tempScreenshotFile)
        } else {
            LogUtils.i(TAG, "SFW内容，不发送通知")
        }

        LogUtils.i(TAG, "检测完成: ${if (finalResult.isNSFW) "NSFW" else "SFW"}")
        return finalResult
    }

    /**
     * 执行后端兜底检测并合并结果
     */
    private suspend fun runBackendFallback(
        androidResult: DetectionResult,
        screenshot: Bitmap,
        nsfwThreshold: Float
    ): DetectionResult {
        // 检查后端是否启用
        val backendEnabled = try {
            settingsRepository.backendEnabled.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取后端启用状态失败，使用默认值", e)
            true
        }
        LogUtils.i(TAG, "后台检测决策: backendEnabled=$backendEnabled, androidResult.isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}")

        if (!backendEnabled) {
            LogUtils.i(TAG, "后端检测已禁用，仅使用Android检测结果")
            return androidResult
        }

        val backendUrl = try {
            settingsRepository.backendUrl.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取后端URL失败", e)
            null
        }

        if (backendUrl.isNullOrEmpty()) {
            LogUtils.w(TAG, "后端URL为空或无效，无法执行远程检测")
            return androidResult
        }

        // 只有Android检测结果为SFW时才调用远程检测进行兜底验证
        if (androidResult.isNSFW) {
            LogUtils.i(TAG, "Android检测结果为NSFW（isNSFW=true），跳过远程检测，直接使用Android结果")
            return androidResult
        }

        LogUtils.i(TAG, "Android检测结果为SFW（isNSFW=false），启动后端兜底检测")
        return try {
            val backendResult = performFallbackDetection(screenshot, backendUrl, nsfwThreshold)
            if (backendResult != null) {
                LogUtils.i(TAG, "后端兜底检测完成: isNsfw=${backendResult.backendIsNsfw}, confidence=${backendResult.backendConfidence}")
                var merged = mergeDetectionResults(androidResult, backendResult)
                LogUtils.i(TAG, "合并后最终结果: isNSFW=${merged.isNSFW}, 后端结果: ${backendResult.backendIsNsfw}")

                // 立即保存debugImages到文件并清理base64，减少内存峰值
                if (merged.debugImages != null && merged.debugImages!!.isNotEmpty()) {
                    merged = detectionRepository.saveDebugImagesImmediately(merged)
                    LogUtils.d(TAG, "已保存debugImages到文件并清理base64")
                }
                merged
            } else {
                LogUtils.w(TAG, "后端检测返回null，使用Android结果")
                androidResult
            }
        } catch (e: Exception) {
            LogUtils.w(TAG, "后端检测失败: ${e.message}")
            androidResult
        }
    }

    /**
     * NSFW 时触发强杀、震动、通知、广播与详情页
     */
    private suspend fun handleNsfwActions(
        finalResult: DetectionResult,
        foregroundPackage: String?,
        tempScreenshotFile: File
    ) {
        // 通过 Shizuku 强制停止前台应用（fire-and-forget，不阻塞主流程）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (shizukuManager.isPermissionGranted()) {
                    val shizukuKillEnabled = settingsRepository.shizukuKillEnabled.first()
                    if (shizukuKillEnabled) {
                        // 注入无障碍事件缓存的前台包名，避免依赖 dumpsys 查询
                        if (foregroundPackage != null) {
                            shizukuManager.cacheForegroundPackage(foregroundPackage)
                        }
                        shizukuManager.killForegroundApp()
                    } else {
                        LogUtils.d(TAG, "Shizuku 强制停止功能已禁用")
                    }
                } else {
                    LogUtils.d(TAG, "Shizuku 未运行或binder无效，跳过强制停止")
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "Shizuku 强制停止前台应用失败", e)
            }
        }

        // 读取通知和震动设置
        val notificationEnabled = try {
            settingsRepository.notificationEnabled.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取通知设置失败，使用默认值", e)
            true
        }
        val vibrationEnabled = try {
            settingsRepository.vibrationEnabled.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取震动设置失败，使用默认值", e)
            true
        }
        val soundEnabled = try {
            settingsRepository.soundEnabled.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取声音设置失败，使用默认值", e)
            true
        }
        val showDetail = try {
            settingsRepository.bringToForeground.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取回到主页设置失败，使用默认值", e)
            false
        }

        LogUtils.d(TAG, "通知设置: enabled=$notificationEnabled, 震动: $vibrationEnabled, 声音: $soundEnabled, 详情页: $showDetail")

        // 震动和通知独立执行，互不影响
        if (vibrationEnabled) {
            LogUtils.i(TAG, "触发震动提醒")
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
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    notificationUtils.sendDetectionNotification(finalResult, enableVibration = false, enableSound = soundEnabled)
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

        // 展示检测结果详情页
        if (tempScreenshotFile.exists()) {
            showDetectionResult(finalResult, tempScreenshotFile.absolutePath, showDetail)
        }
    }

    /**
     * 执行后端兜底检测
     */
    private suspend fun performFallbackDetection(
        screenshot: Bitmap,
        backendUrl: String,
        backendThreshold: Float
    ): DetectionResult? {
        return try {
            LogUtils.i(TAG, "开始后端兜底检测，URL: $backendUrl")
            val backendDetector = BackendNsfwDetector(backendUrl, backendThreshold)
            withTimeout(8000) {
                backendDetector.detect(screenshot)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "后端兜底检测失败", e)
            null
        }
    }

    /**
     * 合并Android和后端检测结果
     */
    private suspend fun mergeDetectionResults(
        androidResult: DetectionResult,
        backendResult: DetectionResult?
    ): DetectionResult {
        if (backendResult == null) {
            return androidResult
        }

        val finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw == true

        val saveDebugImages = try {
            settingsRepository.saveDebugImages.first()
        } catch (e: Exception) {
            LogUtils.e(TAG, "读取调试图片保存设置失败", e)
            false
        }

        val shouldSaveFullBackendInfo = !androidResult.isNSFW

        val modelOutputToSave = if (shouldSaveFullBackendInfo) backendResult.modelOutput else null
        val yoloResultToSave = if (shouldSaveFullBackendInfo) backendResult.yoloResult else null
        val debugImagesToSave = if (shouldSaveFullBackendInfo && saveDebugImages) {
            cleanDebugImages(backendResult.debugImages)
        } else null

        LogUtils.d(TAG, "合并结果决策: Android.isNSFW=${androidResult.isNSFW}, shouldSaveFullBackendInfo=$shouldSaveFullBackendInfo, saveDebugImages=$saveDebugImages")

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
     * 清理调试图片：返回原始数据，由DetectionRepository负责保存和清理
     */
    private fun cleanDebugImages(debugImages: Map<String, DebugImageData>?): Map<String, DebugImageData>? {
        if (debugImages == null) return null
        LogUtils.d(TAG, "debugImages清理: 数量=${debugImages.size}, 键=${debugImages.keys}")
        return debugImages
    }

    /**
     * 创建Bitmap的副本（强制可变副本，避免 immutable 源位图被 createBitmap 直接复用同一对象）
     */
    private fun createBitmapCopy(original: Bitmap): Bitmap {
        return original.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 触发震动提醒（3次震动模式）
     */
    private fun vibrate() {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            val vibrationPattern = longArrayOf(0, 1000, 250, 1000, 250, 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationPattern, -1)
            }
            LogUtils.d(TAG, "震动提醒已触发，3次震动模式")
        } catch (e: Exception) {
            LogUtils.e(TAG, "震动失败", e)
        }
    }

    /**
     * 发送NSFW检测广播并直接显示悬浮窗警告
     */
    private fun sendNsfwDetectedBroadcast() {
        try {
            LogUtils.d(TAG, "准备发送NSFW检测广播")
            val intent = Intent(NsfwMonitorService.ACTION_NSFW_DETECTED).apply { setPackage(context.packageName) }
            context.sendBroadcast(intent)
            LogUtils.i(TAG, "已发送NSFW检测广播")
        } catch (e: Exception) {
            LogUtils.e(TAG, "发送NSFW检测广播失败", e)
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val floatingEnabled = withContext(Dispatchers.IO) {
                    settingsRepository.floatingWindowEnabled.first()
                }
                val showWarning = withContext(Dispatchers.IO) {
                    settingsRepository.floatingWindowShowWarning.first()
                }
                LogUtils.d(TAG, "悬浮窗设置: enabled=$floatingEnabled, showWarning=$showWarning")

                if (floatingEnabled && showWarning) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !android.provider.Settings.canDrawOverlays(context)) {
                        LogUtils.w(TAG, "没有悬浮窗权限，无法直接显示警告")
                        return@launch
                    }
                    val fwm = FloatingWindowManager(context)
                    fwm.showCenteredNotification("想想你该干什么！")
                    LogUtils.i(TAG, "已直接从服务显示悬浮窗警告")
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "直接显示悬浮窗警告失败", e)
            }
        }
    }

    /**
     * 展示检测结果详情页
     */
    private fun showDetectionResult(result: DetectionResult, screenshotPath: String, showDetail: Boolean = true) {
        try {
            if (!showDetail) {
                LogUtils.i(TAG, "回到详情页已禁用，不打开任何页面")
                return
            }

            LogUtils.i(TAG, "展示检测结果详情页")
            val cleanedResult = result.createCleanedCopy()
            val gson = Gson()
            val intent = Intent(context, DetectionResultActivity::class.java).apply {
                putExtra(DetectionResultActivity.EXTRA_DETECTION_RESULT_JSON, gson.toJson(cleanedResult))
                putExtra(DetectionResultActivity.EXTRA_SCREENSHOT_PATH, screenshotPath)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
            LogUtils.i(TAG, "检测结果详情页已启动")
        } catch (e: Exception) {
            LogUtils.e(TAG, "展示检测结果详情页失败", e)
        }
    }
}

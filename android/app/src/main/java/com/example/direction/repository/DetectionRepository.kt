package com.example.direction.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.direction.model.DebugImageData
import com.example.direction.model.DetectionResult
import com.example.direction.utils.ScreenshotManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

/**
 * 检测记录仓库
 * 管理检测结果的存储和检索
 */
class DetectionRepository(
    private val context: Context,
    private val screenshotManager: ScreenshotManager = ScreenshotManager(context),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    companion object {
        private const val TAG = "DetectionRepository"

        // 偏好设置键
        private const val PREF_DETECTION_HISTORY = "detection_history"
        private const val PREF_MAX_HISTORY_SIZE = "max_history_size"
        private const val PREF_AUTO_CLEANUP_DAYS = "auto_cleanup_days"

        // 默认值
        private const val DEFAULT_MAX_HISTORY_SIZE = 1000 // 最大保存1000条记录
        private const val DEFAULT_AUTO_CLEANUP_DAYS = 30 // 自动清理30天前的记录
    }

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("com.example.direction.preferences", Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    /**
     * 保存检测结果（向后兼容版本）
     * 注意：此方法不会保存截图
     */
    fun saveResult(result: DetectionResult) {
        saveResult(result, null, null, false)
    }

    /**
     * 保存检测结果（带截图）
     * @param result 检测结果
     * @param screenshot 截图Bitmap，如果为null则不保存截图
     * @param scope 可选的协程作用域，如果为null则使用默认作用域
     * @param saveDebugImages 是否保存调试图片，如果为false则debugImages字段将被清除
     */
    fun saveResult(result: DetectionResult, screenshot: Bitmap?, scope: CoroutineScope? = null, saveDebugImages: Boolean = true) {
        val usedScope = scope ?: coroutineScope

        // 根据设置处理调试图片
        if (!saveDebugImages) {
            // 不保存调试图片，清除debugImages字段以避免大JSON
            val resultWithoutDebugImages = result.copy(debugImages = null)
            saveResultWithScreenshot(resultWithoutDebugImages, screenshot, usedScope)
            return
        }

        // 保存调试图片（如果启用且存在）
        if (result.debugImages != null && result.debugImages.isNotEmpty()) {
            // 检查是否已处理过（base64已清空且有本地路径），避免重复保存
            val alreadyProcessed = result.debugImages!!.all { (_, img) ->
                img.base64.isEmpty() && img.localPath != null
            }

            if (alreadyProcessed) {
                // 已处理过，直接保存结果
                saveResultWithScreenshot(result, screenshot, usedScope)
            } else {
                // 在协程中保存调试图片
                usedScope.launch {
                    try {
                        val savedDebugImages = saveDebugImagesToFile(result.debugImages!!, result.timestamp)
                        val updatedResult = result.copy(debugImages = savedDebugImages)
                        // 调用内部保存逻辑（带更新后的debugImages）
                        saveResultWithScreenshot(updatedResult, screenshot, usedScope)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "保存调试图片失败，继续保存结果", e)
                        // 失败时使用原始结果（但保留debugImages字段，因为保存失败）
                        saveResultWithScreenshot(result, screenshot, usedScope)
                    }
                }
            }
        } else {
            // 没有调试图片，直接保存
            saveResultWithScreenshot(result, screenshot, usedScope)
        }
    }

    /**
     * 同步保存检测结果并等待完成（主要用于实时检测）
     * @param result 检测结果
     * @param screenshot 截图Bitmap，如果为null则不保存截图
     * @param saveDebugImages 是否保存调试图片
     * @return 更新了localPath的DetectionResult（如果保存了debug图片）
     */
    suspend fun saveResultAndWait(result: DetectionResult, screenshot: Bitmap?, saveDebugImages: Boolean = true): DetectionResult {
        return withContext(Dispatchers.IO) {
            try {
                // 如果有截图，先保存截图并更新result
                val resultWithScreenshot = if (screenshot != null && !screenshot.isRecycled) {
                    try {
                        // 保存双版本截图（缩略图和原始分辨率）
                        val (thumbnailPath, originalPath) = screenshotManager.saveDualScreenshots(screenshot)

                        // 更新result，包含两个截图路径
                        result.copy(
                            screenshotPath = thumbnailPath,
                            originalScreenshotPath = originalPath
                        )
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "保存截图失败，继续保存检测结果", e)
                        result
                    }
                    // 注意：这里不回收bitmap，因为调用者可能还需要使用它
                } else {
                    result
                }

                // 根据设置处理调试图片
                if (!saveDebugImages) {
                    // 不保存调试图片，清除debugImages字段以避免大JSON
                    val resultWithoutDebugImages = resultWithScreenshot.copy(debugImages = null)
                    // 异步保存到历史记录（不等待）
                    saveResultInternal(resultWithoutDebugImages)
                    return@withContext resultWithoutDebugImages
                }

                // 保存调试图片（如果启用且存在）
                val finalResult = if (resultWithScreenshot.debugImages != null && resultWithScreenshot.debugImages.isNotEmpty()) {
                    try {
                        val savedDebugImages = saveDebugImagesToFile(resultWithScreenshot.debugImages!!, resultWithScreenshot.timestamp)
                        val updatedResult = resultWithScreenshot.copy(debugImages = savedDebugImages)
                        // 保存到历史记录（传入清理过的副本）
                        val resultForStorage = updatedResult.copy(
                            debugImages = savedDebugImages.mapValues { (_, imageData) ->
                                // 确保base64为空
                                if (imageData.base64.isNotEmpty()) {
                                    DebugImageData(
                                        base64 = "",
                                        width = imageData.width,
                                        height = imageData.height,
                                        description = imageData.description,
                                        format = imageData.format,
                                        localPath = imageData.localPath
                                    )
                                } else {
                                    imageData
                                }
                            }
                        )
                        saveResultInternal(resultForStorage)
                        updatedResult  // 返回包含localPath的result（base64可能还在）
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "保存调试图片失败，继续保存结果", e)
                        // 失败时使用原始结果（但保留debugImages字段，因为保存失败）
                        saveResultInternal(resultWithScreenshot)
                        resultWithScreenshot
                    }
                } else {
                    // 没有调试图片，直接保存
                    saveResultInternal(resultWithScreenshot)
                    resultWithScreenshot
                }

                return@withContext finalResult
            } catch (e: Exception) {
                android.util.Log.e(TAG, "saveResultAndWait失败", e)
                // 发生异常时返回原始结果
                return@withContext result
            }
        }
    }

    /**
     * 保存结果和截图（内部方法）
     */
    private fun saveResultWithScreenshot(result: DetectionResult, screenshot: Bitmap?, scope: CoroutineScope) {
        // 如果有截图，异步保存截图并更新result
        if (screenshot != null && !screenshot.isRecycled) {
            scope.launch {
                try {
                    // 保存双版本截图（缩略图和原始分辨率）
                    val (thumbnailPath, originalPath) = screenshotManager.saveDualScreenshots(screenshot)

                    // 更新result，包含两个截图路径
                    val updatedResult = result.copy(
                        screenshotPath = thumbnailPath,
                        originalScreenshotPath = originalPath
                    )
                    saveResultInternal(updatedResult)

                    // 记录日志
                    if (thumbnailPath != null && originalPath != null) {
                        android.util.Log.i(TAG, "双版本截图保存成功: 缩略图=$thumbnailPath, 原始=$originalPath")
                    } else if (thumbnailPath != null) {
                        android.util.Log.w(TAG, "仅保存了缩略图: $thumbnailPath")
                    } else if (originalPath != null) {
                        android.util.Log.w(TAG, "仅保存了原始截图: $originalPath")
                    } else {
                        android.util.Log.e(TAG, "截图保存失败，两个版本都未保存")
                    }
                } catch (e: Exception) {
                    // 截图保存失败，仍然保存结果（不带截图）
                    android.util.Log.e(TAG, "保存截图失败，继续保存检测结果", e)
                    saveResultInternal(result)
                } finally {
                    // 无论保存成功与否，都回收传入的Bitmap（假设这是副本）
                    if (!screenshot.isRecycled) {
                        screenshot.recycle()
                        android.util.Log.d(TAG, "已回收截图Bitmap副本")
                    }
                }
            }
        } else {
            // 没有截图，直接保存
            saveResultInternal(result)
        }
    }

    /**
     * 内部保存逻辑（原有逻辑）
     */
    private fun saveResultInternal(result: DetectionResult) {
        try {
            // 清理debugImages中的Base64数据，防止OOM
            val cleanedResult = if (result.debugImages != null) {
                val cleanedDebugImages = result.debugImages!!.mapValues { (_, imageData) ->
                    // 确保base64为空
                    if (imageData.base64.isNotEmpty()) {
                        DebugImageData(
                            base64 = "",
                            width = imageData.width,
                            height = imageData.height,
                            description = imageData.description,
                            format = imageData.format,
                            localPath = imageData.localPath
                        )
                    } else {
                        imageData
                    }
                }
                result.copy(debugImages = cleanedDebugImages)
            } else {
                result
            }

            val history = getHistory()
            history.add(cleanedResult)

            // 限制历史记录大小
            val maxSize = preferences.getInt(PREF_MAX_HISTORY_SIZE, DEFAULT_MAX_HISTORY_SIZE)
            while (history.size > maxSize) {
                val oldest = history.removeAt(0)
                // 删除对应的截图文件
                oldest.screenshotPath?.let { path ->
                    coroutineScope.launch {
                        screenshotManager.deleteScreenshot(path)
                    }
                }
            }

            // 紧急修复：在序列化前清理整个历史记录中的Base64数据
            val cleanedHistory = history.map { result ->
                if (result.debugImages != null) {
                    val cleanedDebugImages = result.debugImages!!.mapValues { (_, imageData) ->
                        // 确保base64为空
                        if (imageData.base64.isNotEmpty()) {
                            DebugImageData(
                                base64 = "",
                                width = imageData.width,
                                height = imageData.height,
                                description = imageData.description,
                                format = imageData.format,
                                localPath = imageData.localPath
                            )
                        } else {
                            imageData
                        }
                    }
                    result.copy(debugImages = cleanedDebugImages)
                } else {
                    result
                }
            }

            // 保存到SharedPreferences（使用commit同步写入，防止进程被杀导致数据丢失）
            val json = gson.toJson(cleanedHistory)
            preferences.edit()
                .putString(PREF_DETECTION_HISTORY, json)
                .commit()

            // 自动清理过期记录
            cleanupExpiredRecords()

            // 更新检测统计
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settingsRepo = SettingsRepository(context)
                    settingsRepo.updateDetectionStats(cleanedResult.isNSFW)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "更新检测统计失败", e)
                }
            }
        } catch (e: Exception) {
            // 记录错误但不崩溃
            android.util.Log.e(TAG, "保存检测结果失败", e)
        }
    }

    /**
     * 立即保存调试图片并清理base64（在传递给saveResult之前调用）
     * 用于减少内存中base64数据的驻留时间，防止OOM
     * @param result 包含debugImages的检测结果
     * @return 更新了localPath且清空了base64的检测结果
     */
    suspend fun saveDebugImagesImmediately(result: DetectionResult): DetectionResult {
        if (result.debugImages == null || result.debugImages.isEmpty()) return result

        return withContext(Dispatchers.IO) {
            try {
                val savedDebugImages = saveDebugImagesToFile(result.debugImages!!, result.timestamp)
                result.copy(debugImages = savedDebugImages)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "立即保存调试图片失败，清理base64", e)
                // 失败时清理base64以免OOM
                val cleaned = result.debugImages!!.mapValues { (_, img) ->
                    img.copy(base64 = "")
                }
                result.copy(debugImages = cleaned)
            }
        }
    }

    /**
     * 保存调试图片到文件系统
     * @param debugImages 调试图片Map
     * @param timestamp 检测时间戳，用于创建子目录
     * @return 更新了localPath的debugImages Map
     */
    private suspend fun saveDebugImagesToFile(
        debugImages: Map<String, DebugImageData>,
        timestamp: Long
    ): Map<String, DebugImageData> {
        val savedImages = mutableMapOf<String, DebugImageData>()

        // 创建调试图片目录：debug_images/yyyy-MM-dd/HH-mm-ss/
        val date = Date(timestamp)
        val dateDir = File(context.filesDir, "debug_images").apply { mkdirs() }
        val timeDir = File(dateDir, "${date.time}").apply { mkdir() }

        for ((key, imageData) in debugImages) {
            try {
                val base64Data = imageData.base64
                if (base64Data.isNotEmpty()) {
                    // 解码Base64
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)

                    // 创建文件
                    val fileName = "${key}_${timestamp}.${imageData.format}"
                    val imageFile = File(timeDir, fileName)

                    // 保存文件
                    FileOutputStream(imageFile).use { fos ->
                        fos.write(imageBytes)
                    }

                    // 创建更新后的DebugImageData，包含本地路径，清空Base64数据
                    val updatedImageData = DebugImageData(
                        base64 = "",  // 清空Base64数据
                        width = imageData.width,
                        height = imageData.height,
                        description = imageData.description,
                        format = imageData.format,
                        localPath = imageFile.absolutePath
                    )
                    savedImages[key] = updatedImageData

                    android.util.Log.d(TAG, "调试图片保存成功: $key -> ${imageFile.absolutePath}")
                } else if (imageData.localPath != null) {
                    // base64已清空且已有localPath，说明已经处理过，直接保留
                    savedImages[key] = imageData
                    android.util.Log.d(TAG, "调试图片已存在: $key -> ${imageData.localPath}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "保存调试图片失败: $key", e)
                // 失败时保留原始数据（无localPath），但清空Base64数据
                val cleanedImageData = DebugImageData(
                    base64 = "",  // 清空Base64数据
                    width = imageData.width,
                    height = imageData.height,
                    description = imageData.description,
                    format = imageData.format,
                    localPath = imageData.localPath
                )
                savedImages[key] = cleanedImageData
            }
        }

        return savedImages
    }

    /**
     * 加载调试图片
     * @param localPath 本地文件路径
     * @return Bitmap，如果失败返回null
     */
    fun loadDebugImage(localPath: String?): Bitmap? {
        return if (localPath != null) {
            try {
                val file = File(localPath)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    android.util.Log.w(TAG, "调试图片文件不存在: $localPath")
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "加载调试图片失败: $localPath", e)
                null
            }
        } else {
            null
        }
    }

    /**
     * 清理过期的调试图片（保留最近7天）
     */
    suspend fun cleanupExpiredDebugImages() {
        coroutineScope.launch {
            try {
                val debugImagesDir = File(context.filesDir, "debug_images")
                if (debugImagesDir.exists() && debugImagesDir.isDirectory) {
                    val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L

                    debugImagesDir.listFiles()?.forEach { dateDir ->
                        if (dateDir.isDirectory) {
                            try {
                                val dirTimestamp = dateDir.name.toLongOrNull()
                                if (dirTimestamp != null && dirTimestamp < sevenDaysAgo) {
                                    // 删除7天前的目录
                                    dateDir.deleteRecursively()
                                    android.util.Log.i(TAG, "清理过期调试图片目录: ${dateDir.name}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "清理调试图片目录失败: ${dateDir.name}", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "清理调试图片失败", e)
            }
        }
    }

    /**
     * 获取所有检测历史
     */
    fun getHistory(): MutableList<DetectionResult> {
        return try {
            val json = preferences.getString(PREF_DETECTION_HISTORY, "[]")
            val type = object : TypeToken<MutableList<DetectionResult>>() {}.type
            val rawHistory = gson.fromJson<MutableList<DetectionResult>>(json, type) ?: mutableListOf()

            // 紧急修复：清理现有历史记录中的Base64数据，防止OOM
            var hasChanges = false
            val cleanedHistory = rawHistory.map { result ->
                if (result.debugImages != null) {
                    val cleanedDebugImages = result.debugImages!!.mapValues { (_, imageData) ->
                        // 确保base64为空
                        if (imageData.base64.isNotEmpty()) {
                            hasChanges = true
                            DebugImageData(
                                base64 = "",
                                width = imageData.width,
                                height = imageData.height,
                                description = imageData.description,
                                format = imageData.format,
                                localPath = imageData.localPath
                            )
                        } else {
                            imageData
                        }
                    }
                    result.copy(debugImages = cleanedDebugImages)
                } else {
                    result
                }
            }.toMutableList()

            // 如果发现有Base64数据被清理，立即保存清理后的历史记录
            if (hasChanges) {
                android.util.Log.i(TAG, "检测到历史记录中包含Base64数据，正在清理并保存...")
                val cleanedJson = gson.toJson(cleanedHistory)
                preferences.edit()
                    .putString(PREF_DETECTION_HISTORY, cleanedJson)
                    .apply()
                android.util.Log.i(TAG, "历史记录Base64数据清理完成")
            }

            cleanedHistory
        } catch (e: Exception) {
            android.util.Log.e(TAG, "获取检测历史失败", e)
            mutableListOf()
        }
    }

    /**
     * 获取最近的检测结果
     * @param limit 限制数量，0表示无限制
     */
    fun getRecentResults(limit: Int = 0): List<DetectionResult> {
        val history = getHistory()
        return if (limit > 0 && history.size > limit) {
            history.takeLast(limit).reversed() // 最新的在前
        } else {
            history.reversed()
        }
    }

    /**
     * 获取指定时间范围内的检测结果
     */
    fun getResultsInTimeRange(startTime: Long, endTime: Long): List<DetectionResult> {
        return getHistory().filter { result ->
            result.timestamp in startTime..endTime
        }
    }

    /**
     * 获取今天的检测结果
     */
    fun getTodayResults(): List<DetectionResult> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = calendar.timeInMillis

        return getResultsInTimeRange(startOfDay, endOfDay)
    }

    /**
     * 获取NSFW检测结果
     */
    fun getNsfwResults(): List<DetectionResult> {
        return getHistory().filter { it.isNSFW }
    }

    /**
     * 获取安全检测结果
     */
    fun getSafeResults(): List<DetectionResult> {
        return getHistory().filter { !it.isNSFW }
    }

    /**
     * 获取检测统计
     */
    fun getStats(): DetectionStats {
        val history = getHistory()
        val total = history.size
        val nsfw = history.count { it.isNSFW }
        val safe = total - nsfw

        val latestTimestamp = if (history.isNotEmpty()) {
            history.maxByOrNull { it.timestamp }?.timestamp ?: 0L
        } else {
            0L
        }

        return DetectionStats(
            totalDetections = total,
            nsfwDetections = nsfw,
            safeDetections = safe,
            latestDetectionTime = latestTimestamp,
            nsfwPercentage = if (total > 0) nsfw.toFloat() / total * 100 else 0f
        )
    }

    /**
     * 清理过期记录
     */
    fun cleanupExpiredRecords(): Int {
        val cleanupDays = preferences.getInt(PREF_AUTO_CLEANUP_DAYS, DEFAULT_AUTO_CLEANUP_DAYS)
        val cutoffTime = System.currentTimeMillis() - (cleanupDays * 24L * 60 * 60 * 1000)

        val history = getHistory()
        val originalSize = history.size

        // 找出要删除的记录
        val toRemove = history.filter { result ->
            result.timestamp < cutoffTime
        }

        // 从历史记录中移除
        history.removeAll(toRemove)

        if (history.size < originalSize) {
            // 删除对应的截图文件
            toRemove.forEach { result ->
                result.screenshotPath?.let { path ->
                    coroutineScope.launch {
                        screenshotManager.deleteScreenshot(path)
                    }
                }
            }

            // 保存清理后的历史
            val json = gson.toJson(history)
            preferences.edit()
                .putString(PREF_DETECTION_HISTORY, json)
                .apply()

            return originalSize - history.size
        }

        return 0
    }

    /**
     * 手动清理所有记录
     * 注意：此方法会异步删除所有截图文件
     */
    fun clearAllRecords() {
        // 先获取所有记录的截图路径
        val history = getHistory()
        val screenshotPaths = history.mapNotNull { it.screenshotPath }

        // 清除SharedPreferences中的历史记录
        preferences.edit()
            .remove(PREF_DETECTION_HISTORY)
            .apply()

        // 异步删除所有截图文件
        if (screenshotPaths.isNotEmpty()) {
            coroutineScope.launch {
                screenshotPaths.forEach { path ->
                    screenshotManager.deleteScreenshot(path)
                }
                android.util.Log.i(TAG, "已删除 ${screenshotPaths.size} 个截图文件")
            }
        }
    }

    /**
     * 设置最大历史记录大小
     */
    fun setMaxHistorySize(size: Int) {
        require(size > 0) { "历史记录大小必须大于0" }
        preferences.edit()
            .putInt(PREF_MAX_HISTORY_SIZE, size)
            .apply()

        // 立即应用大小限制
        val history = getHistory()
        if (history.size > size) {
            // 找出将被删除的记录（最前面的记录）
            val removedCount = history.size - size
            val removedRecords = history.take(removedCount)
            val trimmedHistory = history.takeLast(size)

            // 删除被移除记录的截图文件
            removedRecords.forEach { record ->
                record.screenshotPath?.let { path ->
                    coroutineScope.launch {
                        screenshotManager.deleteScreenshot(path)
                    }
                }
            }

            // 保存修剪后的历史
            val json = gson.toJson(trimmedHistory)
            preferences.edit()
                .putString(PREF_DETECTION_HISTORY, json)
                .apply()

            android.util.Log.i(TAG, "历史记录大小已限制为 $size，删除了 $removedCount 条记录")
        }
    }

    /**
     * 设置自动清理天数
     */
    fun setAutoCleanupDays(days: Int) {
        require(days >= 0) { "清理天数不能为负数" }
        preferences.edit()
            .putInt(PREF_AUTO_CLEANUP_DAYS, days)
            .apply()
    }

    /**
     * 导出检测历史为JSON字符串
     */
    fun exportHistory(): String {
        return gson.toJson(getHistory())
    }

    /**
     * 从JSON字符串导入检测历史
     */
    fun importHistory(json: String): Boolean {
        return try {
            val type = object : TypeToken<List<DetectionResult>>() {}.type
            val imported = gson.fromJson<List<DetectionResult>>(json, type) ?: emptyList()

            // 合并现有历史
            val current = getHistory()
            current.addAll(imported)

            // 保存合并后的历史
            val mergedJson = gson.toJson(current)
            preferences.edit()
                .putString(PREF_DETECTION_HISTORY, mergedJson)
                .apply()

            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "导入检测历史失败", e)
            false
        }
    }

    /**
     * 加载检测结果对应的截图（缩略图版本）
     * @param result 检测结果
     * @return 截图Bitmap，如果不存在或加载失败返回null
     */
    suspend fun getScreenshot(result: DetectionResult): Bitmap? {
        return result.screenshotPath?.let { path ->
            screenshotManager.loadScreenshot(path)
        }
    }

    /**
     * 加载检测结果对应的原始分辨率截图
     * @param result 检测结果
     * @return 原始分辨率截图Bitmap，如果不存在或加载失败返回null
     */
    suspend fun getOriginalScreenshot(result: DetectionResult): Bitmap? {
        return result.originalScreenshotPath?.let { path ->
            screenshotManager.loadOriginalScreenshot(path)
        }
    }

    /**
     * 加载检测结果对应的显示截图（优先原始分辨率，不存在则加载缩略图）
     * @param result 检测结果
     * @return 截图Bitmap，如果不存在或加载失败返回null
     */
    suspend fun getDisplayScreenshot(result: DetectionResult): Bitmap? {
        // 优先尝试加载原始分辨率截图
        return getOriginalScreenshot(result) ?: getScreenshot(result)
    }

    /**
     * 删除单个检测记录
     * @param result 要删除的检测结果
     * @return 是否删除成功
     */
    suspend fun deleteResult(result: DetectionResult): Boolean {
        return try {
            val history = getHistory()
            val index = history.indexOfFirst { it.timestamp == result.timestamp && it.isNSFW == result.isNSFW }

            if (index != -1) {
                val removed = history.removeAt(index)

                // 删除对应的截图文件（缩略图和原始分辨率）
                removed.screenshotPath?.let { path ->
                    screenshotManager.deleteScreenshot(path)
                }
                removed.originalScreenshotPath?.let { path ->
                    screenshotManager.deleteScreenshot(path)
                }

                // 保存更新后的历史
                val json = gson.toJson(history)
                preferences.edit()
                    .putString(PREF_DETECTION_HISTORY, json)
                    .apply()

                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "删除检测记录失败", e)
            false
        }
    }

    /**
     * 删除指定路径的截图文件
     * @param path 截图文件路径
     * @return 是否删除成功
     */
    suspend fun deleteScreenshot(path: String): Boolean {
        return screenshotManager.deleteScreenshot(path)
    }

    /**
     * 获取截图存储统计信息
     */
    suspend fun getScreenshotStats(): ScreenshotManager.StorageStats {
        return screenshotManager.getStorageStats()
    }

    /**
     * 清理截图文件（独立于检测记录）
     * @param maxAgeDays 最大保留天数
     * @param maxCount 最大截图数量
     * @param maxSizeMB 最大存储大小（MB）
     * @return 删除的文件数量
     */
    suspend fun cleanupScreenshots(
        maxAgeDays: Int = ScreenshotManager.DEFAULT_MAX_AGE_DAYS,
        maxCount: Int = ScreenshotManager.DEFAULT_MAX_COUNT,
        maxSizeMB: Int = ScreenshotManager.DEFAULT_MAX_SIZE_MB
    ): Int {
        return screenshotManager.cleanupScreenshots(maxAgeDays, maxCount, maxSizeMB)
    }

    /**
     * 检测统计数据类
     */
    data class DetectionStats(
        val totalDetections: Int,
        val nsfwDetections: Int,
        val safeDetections: Int,
        val latestDetectionTime: Long,
        val nsfwPercentage: Float
    ) {
        val description: String get() {
            return "总检测: $totalDetections, " +
                    "NSFW: $nsfwDetections (${"%.1f".format(nsfwPercentage)}%), " +
                    "安全: $safeDetections"
        }
    }
}
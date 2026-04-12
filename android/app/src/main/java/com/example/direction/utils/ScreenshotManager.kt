package com.example.direction.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 截图管理器
 * 负责截图文件的保存、加载、删除和存储空间管理
 */
class ScreenshotManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotManager"

        // 存储配置
        private const val SCREENSHOT_DIR_NAME = "screenshots"
        private const val SCREENSHOT_PREFIX = "screenshot_"
        private const val SCREENSHOT_EXTENSION = ".jpg"
        private const val RANDOM_SUFFIX_LENGTH = 8

        // 原始截图后缀
        private const val ORIGINAL_SUFFIX = "_original"

        // 图片质量配置
        private const val DEFAULT_JPEG_QUALITY = 70 // 70%
        private const val ORIGINAL_JPEG_QUALITY = 85 // 85%
        private const val MAX_WIDTH = 800 // 最大宽度像素
        private const val SCALE_FILTER = true // 使用双线性过滤

        // 清理策略默认值
        const val DEFAULT_MAX_AGE_DAYS = 30
        const val DEFAULT_MAX_COUNT = 1000
        const val DEFAULT_MAX_SIZE_MB = 500

        // 原始截图清理策略默认值（保留更短时间）
        const val ORIGINAL_MAX_AGE_DAYS = 14
        const val ORIGINAL_MAX_COUNT = 500

        // 大图显示配置
        private const val LARGE_IMAGE_MAX_DIMENSION = 2048 // 最大显示尺寸像素

        /**
         * 根据基本文件名生成原始截图文件名
         * 例如: screenshot_12345_abc.jpg -> screenshot_12345_abc_original.jpg
         */
        private fun getOriginalFileName(baseFileName: String): String {
            return if (baseFileName.endsWith(SCREENSHOT_EXTENSION)) {
                val base = baseFileName.substring(0, baseFileName.length - SCREENSHOT_EXTENSION.length)
                "${base}${ORIGINAL_SUFFIX}${SCREENSHOT_EXTENSION}"
            } else {
                "${baseFileName}${ORIGINAL_SUFFIX}${SCREENSHOT_EXTENSION}"
            }
        }

        /**
         * 检查文件是否为原始截图
         */
        private fun isOriginalScreenshot(fileName: String): Boolean {
            return fileName.contains(ORIGINAL_SUFFIX) && fileName.endsWith(SCREENSHOT_EXTENSION)
        }

        /**
         * 从原始截图文件名获取对应的缩略图文件名
         */
        private fun getThumbnailFileName(originalFileName: String): String {
            return if (originalFileName.contains(ORIGINAL_SUFFIX) && originalFileName.endsWith(SCREENSHOT_EXTENSION)) {
                originalFileName.replace("$ORIGINAL_SUFFIX$SCREENSHOT_EXTENSION", SCREENSHOT_EXTENSION)
            } else {
                originalFileName
            }
        }
    }

    /**
     * 截图存储目录
     */
    val screenshotsDir: File by lazy {
        File(context.filesDir, SCREENSHOT_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * 保存原始分辨率截图（不缩放，高质量）
     * @param bitmap 要保存的Bitmap
     * @param quality JPEG质量（1-100），默认85
     * @return 保存的截图文件相对路径（相对于应用私有目录），失败返回null
     */
    suspend fun saveOriginalScreenshot(
        bitmap: Bitmap,
        quality: Int = ORIGINAL_JPEG_QUALITY
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 验证输入
            if (bitmap.isRecycled) {
                Log.e(TAG, "无法保存已回收的Bitmap")
                return@withContext null
            }

            if (quality < 1 || quality > 100) {
                Log.w(TAG, "JPEG质量超出范围 (1-100)，使用默认值")
            }

            // 2. 生成唯一文件名（添加原始后缀）
            val baseFileName = generateUniqueFileName()
            val originalFileName = getOriginalFileName(baseFileName)
            val screenshotFile = File(screenshotsDir, originalFileName)

            // 3. 保存为JPEG文件（不缩放）
            FileOutputStream(screenshotFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.flush()
            }

            // 4. 记录日志
            val fileSizeKB = screenshotFile.length() / 1024
            Log.i(TAG, "原始截图保存成功: $originalFileName, 原始尺寸: ${bitmap.width}x${bitmap.height}, " +
                    "大小: ${fileSizeKB}KB, 质量: $quality%")

            // 5. 返回相对路径（用于存储在DetectionResult中）
            "$SCREENSHOT_DIR_NAME/$originalFileName"
        } catch (e: Exception) {
            Log.e(TAG, "保存原始截图失败", e)
            null
        }
    }

    /**
     * 同时保存原始分辨率和缩略图两个版本
     * @param bitmap 要保存的Bitmap
     * @param thumbnailQuality 缩略图JPEG质量（1-100），默认70
     * @param originalQuality 原始截图JPEG质量（1-100），默认85
     * @param maxWidth 缩略图最大宽度，默认800px
     * @return Pair<缩略图路径, 原始截图路径>，失败时返回null
     */
    suspend fun saveDualScreenshots(
        bitmap: Bitmap,
        thumbnailQuality: Int = DEFAULT_JPEG_QUALITY,
        originalQuality: Int = ORIGINAL_JPEG_QUALITY,
        maxWidth: Int = MAX_WIDTH
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            // 1. 验证输入
            if (bitmap.isRecycled) {
                Log.e(TAG, "无法保存已回收的Bitmap")
                return@withContext Pair(null, null)
            }

            // 2. 生成唯一基础文件名
            val baseFileName = generateUniqueFileName()

            // 3. 保存缩略图版本
            val thumbnailPath = saveScreenshot(bitmap, thumbnailQuality, maxWidth)

            // 4. 保存原始分辨率版本
            val originalPath = saveOriginalScreenshot(bitmap, originalQuality)

            // 5. 记录日志
            if (thumbnailPath != null && originalPath != null) {
                Log.i(TAG, "双版本截图保存成功: 缩略图=$thumbnailPath, 原始=$originalPath")
            } else {
                Log.w(TAG, "双版本截图保存不完整: 缩略图=$thumbnailPath, 原始=$originalPath")
            }

            Pair(thumbnailPath, originalPath)
        } catch (e: Exception) {
            Log.e(TAG, "保存双版本截图失败", e)
            Pair(null, null)
        }
    }

    /**
     * 加载原始分辨率截图（使用内存优化选项）
     * @param path 原始截图文件路径（相对路径或完整路径）
     * @return 加载的Bitmap，失败返回null
     */
    suspend fun loadOriginalScreenshot(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val screenshotFile = if (path.startsWith(SCREENSHOT_DIR_NAME)) {
                // 相对路径
                File(context.filesDir, path)
            } else {
                // 完整路径或尝试直接加载
                File(path)
            }

            if (!screenshotFile.exists()) {
                Log.w(TAG, "原始截图文件不存在: $path")
                return@withContext null
            }

            // 使用BitmapFactory加载，带有内存优化选项
            // 对于原始分辨率大图，使用ARGB_8888保证质量，但计算合适的采样率
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888 // 原始质量
                inSampleSize = calculateInSampleSizeForLargeImage(screenshotFile)
            }

            BitmapFactory.decodeFile(screenshotFile.absolutePath, options)?.also { bitmap ->
                Log.d(TAG, "原始截图加载成功: $path, 尺寸: ${bitmap.width}x${bitmap.height}, " +
                        "采样率: ${options.inSampleSize}, 配置: ${options.inPreferredConfig}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载原始截图失败: $path", e)
            null
        }
    }

    /**
     * 保存Bitmap为截图文件（缩略图版本）
     * @param bitmap 要保存的Bitmap
     * @param quality JPEG质量（1-100），默认70
     * @param maxWidth 最大宽度，超过此宽度会等比例缩小，默认800px
     * @return 保存的截图文件相对路径（相对于应用私有目录），失败返回null
     */
    suspend fun saveScreenshot(
        bitmap: Bitmap,
        quality: Int = DEFAULT_JPEG_QUALITY,
        maxWidth: Int = MAX_WIDTH
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 验证输入
            if (bitmap.isRecycled) {
                Log.e(TAG, "无法保存已回收的Bitmap")
                return@withContext null
            }

            if (quality < 1 || quality > 100) {
                Log.w(TAG, "JPEG质量超出范围 (1-100)，使用默认值")
            }

            // 2. 缩放Bitmap（如果超过最大宽度）
            val scaledBitmap = if (bitmap.width > maxWidth) {
                scaleBitmapToWidth(bitmap, maxWidth)
            } else {
                bitmap
            }

            // 3. 生成唯一文件名
            val fileName = generateUniqueFileName()
            val screenshotFile = File(screenshotsDir, fileName)

            // 4. 保存为JPEG文件
            FileOutputStream(screenshotFile).use { outputStream ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.flush()
            }

            // 5. 记录日志
            val fileSizeKB = screenshotFile.length() / 1024
            Log.i(TAG, "截图保存成功: $fileName, 尺寸: ${scaledBitmap.width}x${scaledBitmap.height}, " +
                    "大小: ${fileSizeKB}KB, 质量: $quality%")

            // 6. 如果缩放过，清理缩放后的Bitmap
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }

            // 7. 返回相对路径（用于存储在DetectionResult中）
            "$SCREENSHOT_DIR_NAME/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            null
        }
    }

    /**
     * 加载截图文件为Bitmap
     * @param path 截图文件路径（相对路径或完整路径）
     * @return 加载的Bitmap，失败返回null
     */
    suspend fun loadScreenshot(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val screenshotFile = if (path.startsWith(SCREENSHOT_DIR_NAME)) {
                // 相对路径
                File(context.filesDir, path)
            } else {
                // 完整路径或尝试直接加载
                File(path)
            }

            if (!screenshotFile.exists()) {
                Log.w(TAG, "截图文件不存在: $path")
                return@withContext null
            }

            // 使用BitmapFactory加载，带有内存优化选项
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // 减少内存使用
                inSampleSize = calculateInSampleSize(screenshotFile)
            }

            BitmapFactory.decodeFile(screenshotFile.absolutePath, options)?.also { bitmap ->
                Log.d(TAG, "截图加载成功: $path, 尺寸: ${bitmap.width}x${bitmap.height}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载截图失败: $path", e)
            null
        }
    }

    /**
     * 删除截图文件
     * @param path 截图文件路径（相对路径或完整路径）
     */
    suspend fun deleteScreenshot(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val screenshotFile = if (path.startsWith(SCREENSHOT_DIR_NAME)) {
                File(context.filesDir, path)
            } else {
                File(path)
            }

            if (screenshotFile.exists()) {
                val deleted = screenshotFile.delete()
                if (deleted) {
                    Log.i(TAG, "截图删除成功: $path")
                } else {
                    Log.w(TAG, "截图删除失败: $path")
                }
                deleted
            } else {
                Log.w(TAG, "截图文件不存在，无法删除: $path")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除截图失败: $path", e)
            false
        }
    }

    /**
     * 清理过期/超量截图
     * 采用三级保护策略：时间、数量、大小
     * @param maxAgeDays 最大保留天数，默认30天
     * @param maxCount 最大截图数量，默认1000张
     * @param maxSizeMB 最大存储大小（MB），默认500MB
     * @return 删除的文件数量
     */
    suspend fun cleanupScreenshots(
        maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
        maxCount: Int = DEFAULT_MAX_COUNT,
        maxSizeMB: Int = DEFAULT_MAX_SIZE_MB
    ): Int = withContext(Dispatchers.IO) {
        try {
            // 获取所有截图文件
            val screenshotFiles = screenshotsDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(SCREENSHOT_PREFIX) && it.name.endsWith(SCREENSHOT_EXTENSION) }
                ?.sortedBy { it.lastModified() } // 按修改时间排序（最旧的在前面）
                ?: emptyList()

            if (screenshotFiles.isEmpty()) {
                Log.i(TAG, "没有截图需要清理")
                return@withContext 0
            }

            Log.i(TAG, "开始清理截图，当前数量: ${screenshotFiles.size}")

            val filesToDelete = mutableSetOf<File>()
            val currentTime = System.currentTimeMillis()

            // 1. 时间维度清理：删除超过maxAgeDays天的文件
            val cutoffTime = currentTime - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
            val expiredFiles = screenshotFiles.filter { it.lastModified() < cutoffTime }
            filesToDelete.addAll(expiredFiles)

            // 2. 数量维度清理：如果数量超过maxCount，删除最旧的文件
            if (screenshotFiles.size > maxCount) {
                val excessCount = screenshotFiles.size - maxCount
                val oldestFiles = screenshotFiles.take(excessCount)
                filesToDelete.addAll(oldestFiles)
            }

            // 3. 大小维度清理：如果总大小超过maxSizeMB，删除最旧的文件直到满足大小限制
            val maxSizeBytes = maxSizeMB.toLong() * 1024 * 1024
            val totalSize = screenshotFiles.sumOf { it.length() }
            if (totalSize > maxSizeBytes) {
                var currentSize = totalSize
                val sortedByAge = screenshotFiles.sortedBy { it.lastModified() }

                for (file in sortedByAge) {
                    if (currentSize <= maxSizeBytes) break
                    if (!filesToDelete.contains(file)) {
                        filesToDelete.add(file)
                        currentSize -= file.length()
                    }
                }
            }

            // 执行删除
            var deletedCount = 0
            filesToDelete.forEach { file ->
                if (file.delete()) {
                    deletedCount++
                }
            }

            Log.i(TAG, "截图清理完成: 删除了 $deletedCount 个文件")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "清理截图失败", e)
            0
        }
    }

    /**
     * 获取截图存储统计信息
     */
    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        try {
            val screenshotFiles = screenshotsDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(SCREENSHOT_PREFIX) && it.name.endsWith(SCREENSHOT_EXTENSION) }
                ?: emptyList()

            val totalSize = screenshotFiles.sumOf { it.length() }
            val oldestTimestamp = screenshotFiles.minOfOrNull { it.lastModified() } ?: 0L
            val newestTimestamp = screenshotFiles.maxOfOrNull { it.lastModified() } ?: 0L

            StorageStats(
                fileCount = screenshotFiles.size,
                totalSizeBytes = totalSize,
                oldestTimestamp = oldestTimestamp,
                newestTimestamp = newestTimestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取存储统计失败", e)
            StorageStats()
        }
    }

    /**
     * 存储统计信息
     */
    data class StorageStats(
        val fileCount: Int = 0,
        val totalSizeBytes: Long = 0,
        val oldestTimestamp: Long = 0,
        val newestTimestamp: Long = 0
    ) {
        val totalSizeMB: Double get() = totalSizeBytes / (1024.0 * 1024.0)
        val totalSizeFormatted: String get() = "%.2f MB".format(totalSizeMB)
        val ageRangeFormatted: String get() {
            if (oldestTimestamp == 0L || newestTimestamp == 0L) return "无数据"
            val oldestDate = Date(oldestTimestamp)
            val newestDate = Date(newestTimestamp)
            return "${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(oldestDate)} 至 " +
                    "${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(newestDate)}"
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 生成唯一文件名
     */
    private fun generateUniqueFileName(): String {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = generateRandomSuffix(RANDOM_SUFFIX_LENGTH)
        return "${SCREENSHOT_PREFIX}${timestamp}_${randomSuffix}${SCREENSHOT_EXTENSION}"
    }

    /**
     * 生成随机后缀
     */
    private fun generateRandomSuffix(length: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    /**
     * 等比例缩放Bitmap到指定宽度
     */
    private fun scaleBitmapToWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val scaleRatio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * scaleRatio).toInt()

        val matrix = Matrix()
        matrix.postScale(scaleRatio, scaleRatio)

        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            matrix, SCALE_FILTER
        )
    }

    /**
     * 计算合适的inSampleSize以减少内存使用
     */
    private fun calculateInSampleSize(file: File): Int {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)

            // 如果图片较小，不需要采样
            if (options.outWidth <= MAX_WIDTH) {
                return 1
            }

            // 计算采样率
            var inSampleSize = 1
            while (options.outWidth / inSampleSize > MAX_WIDTH * 2) {
                inSampleSize *= 2
            }
            inSampleSize
        } catch (e: Exception) {
            Log.e(TAG, "计算inSampleSize失败", e)
            1
        }
    }

    /**
     * 计算大图的合适inSampleSize（用于原始分辨率截图）
     * 目标是将最大尺寸限制在LARGE_IMAGE_MAX_DIMENSION内
     */
    private fun calculateInSampleSizeForLargeImage(file: File): Int {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)

            // 获取图片的最大尺寸
            val maxDimension = maxOf(options.outWidth, options.outHeight)

            // 如果图片较小，不需要采样
            if (maxDimension <= LARGE_IMAGE_MAX_DIMENSION) {
                return 1
            }

            // 计算采样率，使最大尺寸不超过LARGE_IMAGE_MAX_DIMENSION
            var inSampleSize = 1
            while (maxDimension / inSampleSize > LARGE_IMAGE_MAX_DIMENSION) {
                inSampleSize *= 2
            }
            inSampleSize
        } catch (e: Exception) {
            Log.e(TAG, "计算大图inSampleSize失败", e)
            1
        }
    }
}
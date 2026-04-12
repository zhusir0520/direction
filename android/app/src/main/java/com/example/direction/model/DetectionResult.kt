package com.example.direction.model

import java.util.Date

/**
 * 检测结果数据类
 * @property isNSFW 是否检测到不适宜内容
 * @property confidence 置信度（0.0-1.0）
 * @property sfwScore SFW（安全）分数
 * @property nsfwScore NSFW（不适宜）分数
 * @property rawScores 原始分类分数映射（类别名称 -> 分数）
 * @property timestamp 检测时间戳
 * @property screenshotPath 截图路径（可选，用于调试）
 * @property originalScreenshotPath 原始分辨率截图路径（可选，用于高质量预览）
 * @property error 错误信息（如果检测失败）
 * @property threshold 分类时使用的NSFW阈值（0.0-1.0）
 * @property backendIsNsfw 后端检测结果（可为空）
 * @property backendConfidence 后端检测置信度（可为空）
 * @property backendThreshold 后端检测使用的阈值（可为空）
 * @property backendRawScores 后端原始分类分数映射（可为空）
 */
data class DetectionResult(
    val isNSFW: Boolean,
    val confidence: Float,
    val sfwScore: Float = 0f,
    val nsfwScore: Float = 0f,
    val rawScores: Map<String, Float> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val screenshotPath: String? = null,
    val originalScreenshotPath: String? = null,
    val error: String? = null,
    val threshold: Float = 0.95f,
    val backendIsNsfw: Boolean? = null,
    val backendConfidence: Float? = null,
    val backendThreshold: Float? = null,
    val backendRawScores: Map<String, Float>? = null,
    // 扩展后端检测字段：双模型结果（modelOutput字符串）、YOLO信息、调试图片
    val modelOutput: String? = null,
    val yoloResult: BackendYoloResult? = null,
    val debugImages: Map<String, DebugImageData>? = null,
    val backendResponseRaw: String? = null
) {
    companion object {
        // 创建一个成功的检测结果
        fun success(
            isNSFW: Boolean,
            confidence: Float,
            sfwScore: Float = 0f,
            nsfwScore: Float = 0f,
            rawScores: Map<String, Float> = emptyMap(),
            screenshotPath: String? = null,
            originalScreenshotPath: String? = null,
            threshold: Float = 0.95f,
            backendIsNsfw: Boolean? = null,
            backendConfidence: Float? = null,
            backendThreshold: Float? = null,
            backendRawScores: Map<String, Float>? = null,
            modelOutput: String? = null,
            yoloResult: BackendYoloResult? = null,
            debugImages: Map<String, DebugImageData>? = null,
            backendResponseRaw: String? = null
        ): DetectionResult {
            return DetectionResult(
                isNSFW = isNSFW,
                confidence = confidence,
                sfwScore = sfwScore,
                nsfwScore = nsfwScore,
                rawScores = rawScores,
                screenshotPath = screenshotPath,
                originalScreenshotPath = originalScreenshotPath,
                threshold = threshold,
                backendIsNsfw = backendIsNsfw,
                backendConfidence = backendConfidence,
                backendThreshold = backendThreshold,
                backendRawScores = backendRawScores,
                modelOutput = modelOutput,
                yoloResult = yoloResult,
                debugImages = debugImages,
                backendResponseRaw = backendResponseRaw
            )
        }

        // 创建一个失败的检测结果
        fun failure(error: String): DetectionResult {
            return DetectionResult(
                isNSFW = false,
                confidence = 0f,
                error = error
            )
        }
    }

    // 转换为可读字符串
    override fun toString(): String {
        return try {
            if (error != null) {
                "检测失败: $error"
            } else {
                // 安全格式化置信度和阈值，处理NaN/Infinity等特殊情况
                val confidenceStr = try {
                    if (confidence.isFinite()) {
                        "%.2f".format(confidence * 100)
                    } else {
                        "无效"
                    }
                } catch (e: Exception) {
                    "格式错误"
                }
                val thresholdStr = try {
                    if (threshold.isFinite()) {
                        "%.2f".format(threshold * 100)
                    } else {
                        "无效"
                    }
                } catch (e: Exception) {
                    "格式错误"
                }
                val base = "检测结果: ${if (isNSFW) "NSFW" else "SFW"} (置信度: ${confidenceStr}%, 阈值: ${thresholdStr}%)"
                if (rawScores.isNotEmpty()) {
                    val scoresStr = rawScores.entries.joinToString(", ") { (category, score) ->
                        val scoreStr = try {
                            if (score.isFinite()) {
                                "%.2f".format(score * 100)
                            } else {
                                "无效"
                            }
                        } catch (e: Exception) {
                            "格式错误"
                        }
                        "$category: ${scoreStr}%"
                    }
                    "$base\n原始分类: $scoresStr"
                } else {
                    base
                }
            }
        } catch (e: Exception) {
            // 任何异常都返回安全字符串，防止崩溃
            try {
                "检测结果: 无法格式化显示数据 (${e.message?.take(50) ?: "未知错误"})"
            } catch (e2: Exception) {
                "检测结果: 数据错误"
            }
        }
    }

    /**
     * 获取有效的阈值（处理旧记录的阈值缺失问题）
     * 如果阈值小于1%，则认为是旧记录，返回默认阈值0.95
     */
    val effectiveThreshold: Float
        get() = if (threshold < 0.01f) 0.95f else threshold

    /**
     * 检查是否有原始分辨率截图
     */
    val hasOriginalScreenshot: Boolean
        get() = originalScreenshotPath != null

    /**
     * 获取用于显示的截图路径
     * 优先返回原始分辨率截图路径，不存在则返回缩略图路径
     */
    val displayScreenshotPath: String?
        get() = originalScreenshotPath ?: screenshotPath

    /**
     * 创建清理过的副本，移除大字段以便通过Intent传递
     * 移除debugImages、modelOutput、backendResponseRaw等大字段
     */
    fun createCleanedCopy(): DetectionResult {
        // 清理debugImages中的base64数据，但保留localPath
        val cleanedDebugImages = if (debugImages != null) {
            debugImages!!.mapValues { (key, imageData) ->
                if (imageData.base64.isNotEmpty()) {
                    // 只保留localPath，移除base64数据
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
        } else {
            null
        }

        return this.copy(
            debugImages = cleanedDebugImages,  // 保留清理后的debugImages（只有localPath）
            modelOutput = null,           // 移除模型输出字符串
            backendResponseRaw = null,    // 移除原始响应字符串
            yoloResult = yoloResult,      // 保留YOLO结果（通常不大）
            // 保留其他字段
        )
    }
}

/**
 * 时间窗口配置
 * @property startHour 开始小时（0-23）
 * @property endHour 结束小时（0-23，如果小于开始小时则表示跨天）
 * @property enabled 是否启用检测
 */
data class TimeWindow(
    val startHour: Int = 22,  // 22:00
    val endHour: Int = 2,     // 02:00（跨天）
    val enabled: Boolean = true
) {
    init {
        require(startHour in 0..23) { "开始小时必须在0-23之间" }
        require(endHour in 0..23) { "结束小时必须在0-23之间" }
    }

    // 检查时间窗口是否跨天
    val isCrossDay: Boolean get() = endHour <= startHour

    // 获取时间窗口描述
    val description: String get() {
        val startHourStr = String.format("%02d", startHour)
        val endHourStr = String.format("%02d", endHour)
        return if (isCrossDay) {
            "$startHourStr:00 - 次日$endHourStr:00"
        } else {
            "$startHourStr:00 - $endHourStr:00"
        }
    }
}

/**
 * 权限状态
 */
data class PermissionState(
    val mediaProjectionGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val mediaProjectionGrantTime: Long = 0L,
    val notificationGrantTime: Long = 0L
) {
    // 检查MediaProjection权限是否有效（24小时内）
    val isMediaProjectionValid: Boolean get() {
        if (!mediaProjectionGranted) return false
        val currentTime = System.currentTimeMillis()
        return currentTime - mediaProjectionGrantTime < 24 * 60 * 60 * 1000
    }
}

/**
 * 后端模型结果
 * @property model 模型名称，如"5class_onnx", "falconsai"
 * @property isNsfw 是否NSFW
 * @property confidence 置信度
 * @property threshold 阈值
 * @property rawScores 原始分类分数（可选）
 */
data class BackendModelResult(
    val model: String,
    val isNsfw: Boolean,
    val confidence: Double,
    val threshold: Double,
    val rawScores: Map<String, Double>? = null
)

/**
 * 后端YOLO检测结果
 * @property triggered 是否触发YOLO检测
 * @property detectedObjects 是否检测到物体
 * @property unionBox 联合框坐标，格式："left,top,right,bottom"（归一化坐标）
 * @property backendIsNsfw 裁剪区域检测结果是否NSFW
 * @property backendConfidence 裁剪区域检测置信度
 * @property backendRawScores 裁剪区域检测原始分类分数
 * @property detectedClasses 检测到的物体类别列表（可选）
 */
data class BackendYoloResult(
    val triggered: Boolean,
    val detectedObjects: Boolean,
    val unionBox: String?,       // 格式："left,top,right,bottom"
    val backendIsNsfw: Boolean?,
    val backendConfidence: Float?,
    val backendRawScores: Map<String, Float>?,
    val detectedClasses: List<String>? = null
)

/**
 * 调试图片数据
 * @property base64 Base64编码的图片数据
 * @property width 图片宽度（可选）
 * @property height 图片高度（可选）
 * @property description 图片用途描述（可选）
 * @property format 图片格式，默认"jpg"
 * @property localPath 本地保存路径（可选）
 */
data class DebugImageData(
    val base64: String,          // Base64编码的图片数据
    val width: Int? = null,
    val height: Int? = null,
    val description: String? = null,
    val format: String = "jpg",
    val localPath: String? = null // 本地保存路径（可选）
)
package com.example.direction.model

import com.google.gson.annotations.SerializedName

/**
 * 后端NSFW检测响应DTO
 * 对应后端NsfwResponse类的Kotlin映射
 */
data class BackendResponseDto(
    @SerializedName("isNsfw")
    val isNsfw: Boolean? = null,

    @SerializedName("confidence")
    val confidence: Double? = null,

    @SerializedName("error")
    val error: String? = null,

    @SerializedName("modelOutput")
    val modelOutput: String? = null, // 向后兼容的原始JSON字符串

    @SerializedName("yoloResult")
    val yoloResult: YoloResultDto? = null,

    @SerializedName("debugImages")
    val debugImages: Map<String, DebugImageDto>? = null
)

/**
 * 后端模型结果DTO
 */
data class BackendModelResultDto(
    @SerializedName("model")
    val model: String? = null,

    @SerializedName("isNsfw")
    val isNsfw: Boolean? = null,

    @SerializedName("confidence")
    val confidence: Double? = null,

    @SerializedName("threshold")
    val threshold: Double? = null
)

/**
 * 后端YOLO结果DTO
 */
data class YoloResultDto(
    @SerializedName("triggered")
    val triggered: Boolean? = null,

    @SerializedName("detectedObjects")
    val detectedObjects: Boolean? = null,

    @SerializedName("unionBox")
    val unionBox: String? = null, // 格式："left,top,right,bottom"（归一化坐标）

    @SerializedName("backendIsNsfw")
    val backendIsNsfw: Boolean? = null,

    @SerializedName("backendConfidence")
    val backendConfidence: Float? = null,

    @SerializedName("backendRawScores")
    val backendRawScores: Map<String, Float>? = null
)

/**
 * 调试图片DTO
 */
data class DebugImageDto(
    @SerializedName("base64")
    val base64: String? = null, // Base64编码的图片数据

    @SerializedName("width")
    val width: Int? = null,

    @SerializedName("height")
    val height: Int? = null,

    @SerializedName("description")
    val description: String? = null,

    @SerializedName("format")
    val format: String = "jpg"
)

/**
 * 扩展函数：将BackendModelResultDto转换为BackendModelResult
 */
fun BackendModelResultDto.toDomain(): BackendModelResult? {
    return if (model != null && isNsfw != null && confidence != null && threshold != null) {
        BackendModelResult(
            model = model,
            isNsfw = isNsfw,
            confidence = confidence,
            threshold = threshold
        )
    } else {
        null
    }
}

/**
 * 扩展函数：将YoloResultDto转换为BackendYoloResult
 */
fun YoloResultDto.toDomain(): BackendYoloResult? {
    return if (triggered != null && detectedObjects != null) {
        BackendYoloResult(
            triggered = triggered,
            detectedObjects = detectedObjects,
            unionBox = unionBox,
            backendIsNsfw = backendIsNsfw,
            backendConfidence = backendConfidence,
            backendRawScores = backendRawScores
        )
    } else {
        null
    }
}

/**
 * 扩展函数：将DebugImageDto转换为DebugImageData
 */
fun DebugImageDto.toDomain(): DebugImageData? {
    return if (base64 != null) {
        DebugImageData(
            base64 = base64,
            width = width,
            height = height,
            description = description,
            format = format
        )
    } else {
        null
    }
}
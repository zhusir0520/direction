package com.example.direction.detector.classifier

import android.graphics.Bitmap
import android.util.Log
import com.example.direction.model.BackendResponseDto
import com.example.direction.model.BackendModelResult
import com.example.direction.model.BackendModelResultDto
import com.example.direction.model.BackendYoloResult
import com.example.direction.model.DebugImageData
import com.example.direction.model.DebugImageDto
import com.example.direction.model.DetectionResult
import com.example.direction.model.YoloResultDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后端NSFW检测器（兜底机制）
 * 当本地分类器置信度不达标时调用后端服务
 */
class BackendNsfwDetector(
    private val backendUrl: String,
    private val backendThreshold: Float = 0.90f // 后端默认阈值
) {
    companion object {
        private const val TAG = "BackendNsfwDetector"
        private const val CONNECT_TIMEOUT_MS = 3000 // 连接超时3秒
        private const val READ_TIMEOUT_MS = 5000 // 读取超时5秒
        private const val BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    }

    /**
     * 调用后端服务进行NSFW检测
     * @param bitmap 要检测的图片
     * @return DetectionResult，包含后端检测结果；如果失败则返回null
     */
    suspend fun detect(bitmap: Bitmap): DetectionResult? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            Log.i(TAG, "开始后端NSFW检测，URL: $backendUrl")

            // 将Bitmap转换为JPEG字节数组
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
            val imageBytes = byteArrayOutputStream.toByteArray()

            // 创建HTTP连接
            val url = URL(backendUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Connection", "Keep-Alive")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            connection.setRequestProperty("User-Agent", "NSFW-Android-App")

            // 构建multipart请求体
            val outputStream = DataOutputStream(connection.outputStream)
            writeFormField(outputStream, "image", "screenshot.jpg", imageBytes)
            outputStream.writeBytes("\r\n--$BOUNDARY--\r\n")
            outputStream.flush()
            outputStream.close()

            // 获取响应
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream: InputStream = connection.inputStream
                val responseText = inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "后端响应原始文本: $responseText")

                // 解析JSON响应（简单解析）
                return@withContext parseResponse(responseText, backendThreshold)
            } else {
                Log.e(TAG, "后端请求失败，状态码: $responseCode")
                val errorStream = connection.errorStream
                val errorText = errorStream?.bufferedReader()?.use { it.readText() } ?: "未知错误"
                Log.e(TAG, "错误响应: $errorText")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "后端检测异常", e)
            return@withContext null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 写入multipart表单字段
     */
    private fun writeFormField(
        outputStream: DataOutputStream,
        fieldName: String,
        fileName: String,
        fileData: ByteArray
    ) {
        outputStream.writeBytes("--$BOUNDARY\r\n")
        outputStream.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        outputStream.writeBytes("Content-Type: image/jpeg\r\n\r\n")
        outputStream.write(fileData)
        outputStream.writeBytes("\r\n")
    }

    /**
     * 解析后端JSON响应
     * 支持完整后端响应格式，包括双模型结果、YOLO信息和调试图片
     * 期望格式: {"isNsfw": true/false, "confidence": 0.95, "error": null, "modelResults": [...], "yoloResult": {...}, "debugImages": {...}}
     */
    private fun parseResponse(json: String, backendThreshold: Float): DetectionResult? {
        return try {
            Log.d(TAG, "开始解析后端响应: ${json.take(200)}...") // 只记录前200个字符

            // 首先尝试使用Gson解析完整响应
            val gson = Gson()
            val responseDto = gson.fromJson(json, BackendResponseDto::class.java)

            // 检查是否有错误
            if (responseDto.error != null) {
                Log.e(TAG, "后端返回错误: ${responseDto.error}")
                return null
            }

            // 提取基本字段
            val isNsfw = responseDto.isNsfw
            val confidence = responseDto.confidence

            if (isNsfw != null && confidence != null) {
                // 调整置信度到0-1范围
                val adjustedConfidence = adjustConfidence(confidence.toFloat())

                if (adjustedConfidence < 0.0f || adjustedConfidence > 1.0f) {
                    Log.e(TAG, "调整后的置信度不在0-1范围内: $adjustedConfidence")
                    return null
                }

                Log.i(TAG, "Gson解析后端结果: isNsfw=$isNsfw, confidence=$confidence, 调整后confidence=$adjustedConfidence")

                // 保存modelOutput字符串（如果后端提供）
                val modelOutput = responseDto.modelOutput
                if (modelOutput != null) {
                    Log.i(TAG, "保存modelOutput字符串: ${modelOutput.take(100)}...")
                }

                // 转换YOLO结果
                val yoloResult = responseDto.yoloResult?.let { dto ->
                    if (dto.triggered != null && dto.detectedObjects != null) {
                        Log.i(TAG, "解析YOLO结果: triggered=${dto.triggered}, detectedObjects=${dto.detectedObjects}, unionBox=${dto.unionBox}, backendIsNsfw=${dto.backendIsNsfw}")
                        BackendYoloResult(
                            triggered = dto.triggered,
                            detectedObjects = dto.detectedObjects,
                            unionBox = dto.unionBox,
                            backendIsNsfw = dto.backendIsNsfw,
                            backendConfidence = dto.backendConfidence,
                            backendRawScores = dto.backendRawScores
                        )
                    } else {
                        Log.w(TAG, "YOLO结果缺少必要字段: triggered=${dto.triggered}, detectedObjects=${dto.detectedObjects}")
                        null
                    }
                }
                if (yoloResult == null && responseDto.yoloResult != null) {
                    Log.w(TAG, "YOLO结果存在但解析失败: ${responseDto.yoloResult}")
                } else if (yoloResult != null) {
                    Log.i(TAG, "YOLO结果解析成功: triggered=${yoloResult.triggered}, detectedObjects=${yoloResult.detectedObjects}")
                }

                // 转换调试图片
                val debugImages = mutableMapOf<String, DebugImageData>()
                responseDto.debugImages?.forEach { (key, dto) ->
                    if (dto.base64 != null) {
                        val debugImageData = DebugImageData(
                            base64 = dto.base64,
                            width = dto.width,
                            height = dto.height,
                            description = dto.description,
                            format = dto.format
                        )
                        debugImages[key] = debugImageData
                    }
                }

                // 创建一个临时的DetectionResult，包含完整的后端信息
                return DetectionResult.success(
                    isNSFW = isNsfw,
                    confidence = adjustedConfidence,
                    sfwScore = if (isNsfw) 1 - adjustedConfidence else adjustedConfidence,
                    nsfwScore = adjustedConfidence,
                    rawScores = mapOf("backend" to adjustedConfidence),
                    threshold = backendThreshold,
                    backendIsNsfw = isNsfw,
                    backendConfidence = adjustedConfidence,
                    backendThreshold = backendThreshold,
                    modelOutput = modelOutput,
                    yoloResult = yoloResult,
                    debugImages = if (debugImages.isNotEmpty()) debugImages else null,
                    backendResponseRaw = json
                )
            } else {
                // Gson解析失败，尝试回退到正则表达式解析（向后兼容）
                Log.w(TAG, "Gson解析基本字段失败，尝试回退到正则表达式解析")
                return parseResponseFallback(json, backendThreshold)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gson解析后端响应失败，尝试回退到正则表达式", e)
            // 回退到正则表达式解析
            return parseResponseFallback(json, backendThreshold)
        }
    }

    /**
     * 回退解析方法：使用正则表达式解析基本字段（向后兼容）
     */
    private fun parseResponseFallback(json: String, backendThreshold: Float): DetectionResult? {
        return try {
            Log.d(TAG, "开始回退解析后端响应")

            // 简单解析，避免引入额外JSON库（假设响应格式简单）
            val isNsfw = "\"isNsfw\"\\s*:\\s*(true|false)".toRegex().find(json)?.groupValues?.get(1)?.toBoolean()
            // 改进的正则表达式：匹配整数或小数（包括科学计数法）
            val confidenceMatch = "\"confidence\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)".toRegex().find(json)
            val confidence = confidenceMatch?.groupValues?.get(1)?.toFloat()
            val error = "\"error\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)?.groupValues?.get(1)

            Log.d(TAG, "回退解析结果: isNsfw=$isNsfw, confidence匹配=$confidenceMatch, confidence值=$confidence, error=$error")

            if (error != null) {
                Log.e(TAG, "后端返回错误: $error")
                return null
            }

            if (isNsfw != null && confidence != null) {
                // 检查置信度是否在合理范围内
                val adjustedConfidence = adjustConfidence(confidence)

                if (adjustedConfidence < 0.0f || adjustedConfidence > 1.0f) {
                    Log.e(TAG, "调整后的置信度不在0-1范围内: $adjustedConfidence")
                    return null
                }

                Log.i(TAG, "回退解析后端结果: isNsfw=$isNsfw, 原始confidence=$confidence, 调整后confidence=$adjustedConfidence")
                // 创建一个临时的DetectionResult，仅包含基本后端信息
                return DetectionResult.success(
                    isNSFW = isNsfw,
                    confidence = adjustedConfidence,
                    sfwScore = if (isNsfw) 1 - adjustedConfidence else adjustedConfidence,
                    nsfwScore = adjustedConfidence,
                    rawScores = mapOf("backend" to adjustedConfidence),
                    threshold = backendThreshold,
                    backendIsNsfw = isNsfw,
                    backendConfidence = adjustedConfidence,
                    backendThreshold = backendThreshold,
                    backendResponseRaw = json // 保存原始响应用于调试
                )
            } else {
                Log.e(TAG, "无法解析后端响应: isNsfw=$isNsfw, confidence=$confidence")
                Log.e(TAG, "原始响应: $json")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "回退解析后端响应失败", e)
            null
        }
    }

    /**
     * 调整置信度到0-1范围
     */
    private fun adjustConfidence(confidence: Float): Float {
        var adjustedConfidence = confidence
        if (confidence > 1.0f) {
            // 如果置信度大于1，可能是百分比形式，除以100
            Log.w(TAG, "置信度大于1.0: $confidence，尝试除以100处理")
            adjustedConfidence = confidence / 100.0f
        }
        return adjustedConfidence
    }

    /**
     * 测试后端连接
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(backendUrl.replace("/detect", "/health"))
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000 // 连接超时3秒
            connection.readTimeout = 5000 // 读取超时5秒
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "后端连接测试失败", e)
            false
        }
    }
}
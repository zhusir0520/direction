package com.example.direction.detector.classifier

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.direction.model.DetectionResult
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * NSFW分类器（TensorFlow Lite版本）
 * 使用assets/models/nsfw.tflite模型进行内容分类
 */
class NSFWClassifier(context: Context) {

    companion object {
        private const val TAG = "NSFWClassifier"
        private const val MODEL_FILE = "saved_model.tflite"
        private const val LABELS_FILE = "labels.txt"
        const val DEFAULT_NSFW_THRESHOLD = 0.95f // 默认NSFW阈值 (hentai+sexy+porn > 95%)
        // GantMan模型输出类别索引
        private const val INDEX_DRAWING = 0
        private const val INDEX_HENTAI = 1
        private const val INDEX_NEUTRAL = 2
        private const val INDEX_PORN = 3
        private const val INDEX_SEXY = 4
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false

    init {
        initialize(context)
    }

    /**
     * 初始化TensorFlow Lite解释器和标签
     */
    private fun initialize(context: Context) {
        try {
            Log.i(TAG, "初始化NSFW分类器（TensorFlow Lite模式）")

            // 1. 加载模型
            val modelBuffer = loadModelFile(context)
            interpreter = Interpreter(modelBuffer)

            // 2. 加载标签
            labels = loadLabels(context)

            // 3. 记录模型信息
            logModelInfo()

            isInitialized = true
            Log.i(TAG, "NSFW分类器初始化完成，标签: $labels")
        } catch (e: Exception) {
            Log.e(TAG, "初始化NSFW分类器失败", e)
            isInitialized = false
            release()
        }
    }

    /**
     * 从assets加载模型文件
     */
    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetManager = context.assets
        val assetFileDescriptor = assetManager.openFd("models/$MODEL_FILE")
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * 从assets加载标签文件
     */
    private fun loadLabels(context: Context): List<String> {
        return try {
            val inputStream = context.assets.open("models/$LABELS_FILE")
            val bytes = ByteArray(inputStream.available())
            inputStream.read(bytes)
            inputStream.close()
            String(bytes, StandardCharsets.UTF_8)
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "加载标签文件失败，使用默认标签", e)
            listOf("sfw", "nsfw") // 默认标签
        }
    }

    /**
     * 记录模型输入输出信息
     */
    private fun logModelInfo() {
        val interpreter = interpreter ?: return
        val inputCount = interpreter.inputTensorCount
        val outputCount = interpreter.outputTensorCount

        Log.i(TAG, "模型信息:")
        Log.i(TAG, "  - 输入张量数量: $inputCount")
        for (i in 0 until inputCount) {
            val tensor = interpreter.getInputTensor(i)
            Log.i(TAG, "    [输入$i] 形状: ${tensor.shape().contentToString()}, 数据类型: ${tensor.dataType()}")
        }

        Log.i(TAG, "  - 输出张量数量: $outputCount")
        for (i in 0 until outputCount) {
            val tensor = interpreter.getOutputTensor(i)
            Log.i(TAG, "    [输出$i] 形状: ${tensor.shape().contentToString()}, 数据类型: ${tensor.dataType()}")
        }
    }

    /**
     * 对图像进行分类（TensorFlow Lite版本）
     */
    fun classify(bitmap: Bitmap, threshold: Float = DEFAULT_NSFW_THRESHOLD): DetectionResult {
        require(isInitialized) { "分类器未初始化" }
        val interpreter = interpreter ?: return DetectionResult.failure("解释器未初始化")

        return try {
            // 1. 预处理图像
            val inputBuffer = ImagePreprocessor.preprocess(bitmap)

            // 2. 准备输出张量
            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputBuffer = TensorBuffer.createFixedSize(outputShape, ImagePreprocessor.INPUT_DATA_TYPE)

            // 3. 运行推理
            val startTime = System.currentTimeMillis()
            interpreter.run(inputBuffer, outputBuffer.buffer)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "推理完成，耗时: ${inferenceTime}ms")

            // 4. 解析输出
            val results = parseOutput(outputBuffer)

            // 5. 创建检测结果
            createDetectionResult(results, threshold)
        } catch (e: Exception) {
            Log.e(TAG, "分类失败", e)
            DetectionResult.failure("分类失败: ${e.message}")
        }
    }

    /**
     * 解析模型输出
     */
    private fun parseOutput(outputBuffer: TensorBuffer): FloatArray {
        val outputArray = outputBuffer.floatArray
        Log.d(TAG, "原始输出: ${outputArray.contentToString()}")

        // 确保输出长度与标签数量匹配
        val minLength = minOf(outputArray.size, labels.size)
        val results = FloatArray(minLength)
        for (i in 0 until minLength) {
            results[i] = outputArray[i]
        }

        // 记录每个类别的概率（用于调试）
        if (labels.isNotEmpty() && results.size >= labels.size) {
            for (i in labels.indices) {
                Log.d(TAG, "  ${labels[i]}: ${"%.4f".format(results[i])}")
            }
        }

        return results
    }

    /**
     * 创建检测结果
     */
    private fun createDetectionResult(output: FloatArray, threshold: Float = DEFAULT_NSFW_THRESHOLD): DetectionResult {
        // 根据GantMan模型的5个输出类别计算SFW和NSFW概率
        val sfwProb: Float
        val nsfwProb: Float
        val rawScores = mutableMapOf<String, Float>()

        // 确保输出长度足够
        if (output.size >= 5 && labels.size >= 5) {
            // 使用标签索引查找各个类别
            val drawingIndex = labels.indexOfFirst { it.equals("Drawing", ignoreCase = true) }.takeIf { it >= 0 } ?: INDEX_DRAWING
            val hentaiIndex = labels.indexOfFirst { it.equals("Hentai", ignoreCase = true) }.takeIf { it >= 0 } ?: INDEX_HENTAI
            val neutralIndex = labels.indexOfFirst { it.equals("Neutral", ignoreCase = true) }.takeIf { it >= 0 } ?: INDEX_NEUTRAL
            val pornIndex = labels.indexOfFirst { it.equals("Porn", ignoreCase = true) }.takeIf { it >= 0 } ?: INDEX_PORN
            val sexyIndex = labels.indexOfFirst { it.equals("Sexy", ignoreCase = true) }.takeIf { it >= 0 } ?: INDEX_SEXY

            // 获取各个类别概率（确保索引在范围内）
            val drawingProb = if (drawingIndex < output.size) output[drawingIndex].coerceIn(0f, 1f) else 0f
            val hentaiProb = if (hentaiIndex < output.size) output[hentaiIndex].coerceIn(0f, 1f) else 0f
            val neutralProb = if (neutralIndex < output.size) output[neutralIndex].coerceIn(0f, 1f) else 0f
            val pornProb = if (pornIndex < output.size) output[pornIndex].coerceIn(0f, 1f) else 0f
            val sexyProb = if (sexyIndex < output.size) output[sexyIndex].coerceIn(0f, 1f) else 0f

            // 构建原始评分映射
            rawScores["Drawing"] = drawingProb
            rawScores["Hentai"] = hentaiProb
            rawScores["Neutral"] = neutralProb
            rawScores["Porn"] = pornProb
            rawScores["Sexy"] = sexyProb

            // 计算SFW（安全）概率：Drawing + Neutral
            sfwProb = (drawingProb + neutralProb).coerceIn(0f, 1f)
            // 计算NSFW（不适宜）概率：Hentai + Porn + Sexy (根据用户要求>95%)
            nsfwProb = (hentaiProb + pornProb + sexyProb).coerceIn(0f, 1f)

            // 记录详细分类结果（用于调试）
            Log.d(TAG, "详细分类结果:")
            Log.d(TAG, "  Drawing (绘画): ${"%.4f".format(drawingProb)}")
            Log.d(TAG, "  Hentai (动漫色情): ${"%.4f".format(hentaiProb)}")
            Log.d(TAG, "  Neutral (正常图片): ${"%.4f".format(neutralProb)}")
            Log.d(TAG, "  Porn (真人色情): ${"%.4f".format(pornProb)}")
            Log.d(TAG, "  Sexy (性感): ${"%.4f".format(sexyProb)}")
            Log.d(TAG, "  SFW总计: ${"%.4f".format(sfwProb)} (Drawing+Neutral)")
            Log.d(TAG, "  NSFW总计: ${"%.4f".format(nsfwProb)} (Hentai+Porn+Sexy)")
        } else {
            // 输出长度不足，回退到简单逻辑
            Log.w(TAG, "输出长度不足或标签不完整，使用回退逻辑")
            if (output.size >= 2) {
                // 假设前两个是SFW和NSFW
                sfwProb = output[0].coerceIn(0f, 1f)
                nsfwProb = output[1].coerceIn(0f, 1f)
                // 尝试构建原始评分映射
                labels.forEachIndexed { index, label ->
                    if (index < output.size) {
                        rawScores[label] = output[index]
                    }
                }
            } else if (output.size == 1) {
                // 单个输出，假设是NSFW概率
                nsfwProb = output[0].coerceIn(0f, 1f)
                sfwProb = 1.0f - nsfwProb
                rawScores["NSFW"] = nsfwProb
                rawScores["SFW"] = sfwProb
            } else {
                // 回退到默认值
                sfwProb = 0.5f
                nsfwProb = 0.5f
                rawScores["NSFW"] = nsfwProb
                rawScores["SFW"] = sfwProb
            }
        }

        // 应用阈值判断（NSFW概率超过阈值则判定为NSFW）
        val isNSFW = nsfwProb > threshold
        val confidence = nsfwProb

        return DetectionResult.success(
            isNSFW = isNSFW,
            confidence = confidence,
            sfwScore = sfwProb,
            nsfwScore = nsfwProb,
            rawScores = rawScores,
            threshold = threshold
        )
    }

    /**
     * 检查分类器是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * 释放TensorFlow Lite解释器资源
     */
    fun release() {
        try {
            interpreter?.close()
            interpreter = null
            isInitialized = false
            Log.i(TAG, "已释放TensorFlow Lite解释器资源")
        } catch (e: Exception) {
            Log.e(TAG, "释放解释器资源时出错", e)
        }
    }

    /**
     * 获取分类器信息
     */
    fun getClassifierInfo(): String {
        val inputInfo = ImagePreprocessor.getInputShapeDescription()
        val labelInfo = if (labels.isNotEmpty()) labels.joinToString(", ") else "未加载"

        return """
            NSFW分类器（TensorFlow Lite模式）
            - 状态: ${if (isInitialized) "已初始化" else "未初始化"}
            - 模型: $MODEL_FILE
            - 标签: $labelInfo
            - 输入要求: $inputInfo
            - NSFW阈值: 可配置（默认${DEFAULT_NSFW_THRESHOLD * 100}%）
        """.trimIndent()
    }
}
package com.example.direction.detector.classifier

import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 图像预处理器
 * 将Bitmap转换为TensorFlow Lite模型输入所需的格式
 *
 * 假设模型输入要求：
 * - 形状: [1, 224, 224, 3] (batch, height, width, channels)
 * - 数据类型: FLOAT32
 * - 归一化: 像素值除以255.0 (范围[0, 1])
 * - 颜色顺序: RGB
 */
object ImagePreprocessor {

    // 模型输入尺寸
    const val INPUT_WIDTH = 224
    const val INPUT_HEIGHT = 224
    const val INPUT_CHANNELS = 3 // RGB
    const val INPUT_SIZE = INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS

    // 输入数据类型
    val INPUT_DATA_TYPE = DataType.FLOAT32
    val INPUT_BYTE_SIZE = INPUT_DATA_TYPE.byteSize()

    /**
     * 将Bitmap预处理为模型输入ByteBuffer
     */
    fun preprocess(bitmap: Bitmap): ByteBuffer {
        // 调整图像大小到模型输入尺寸
        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap, INPUT_WIDTH, INPUT_HEIGHT, true
        )

        // 创建ByteBuffer，使用本地字节序
        val byteBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_BYTE_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())

        // 提取RGB值并归一化到[0, 1]
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        resizedBitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        for (pixel in pixels) {
            // 提取RGB分量
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // 归一化到[0, 1]并写入ByteBuffer
            byteBuffer.putFloat(r / 255.0f)
            byteBuffer.putFloat(g / 255.0f)
            byteBuffer.putFloat(b / 255.0f)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * 将Bitmap预处理为TensorBuffer
     */
    fun preprocessToTensorBuffer(bitmap: Bitmap): TensorBuffer {
        val byteBuffer = preprocess(bitmap)
        val tensorBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, INPUT_HEIGHT, INPUT_WIDTH, INPUT_CHANNELS),
            INPUT_DATA_TYPE
        )
        tensorBuffer.loadBuffer(byteBuffer)
        return tensorBuffer
    }

    /**
     * 检查Bitmap是否有效（非空且尺寸大于0）
     */
    fun validateBitmap(bitmap: Bitmap?): Boolean {
        return bitmap != null && !bitmap.isRecycled &&
                bitmap.width > 0 && bitmap.height > 0
    }

    /**
     * 获取输入张量形状描述
     */
    fun getInputShapeDescription(): String {
        return "Shape: [1, $INPUT_HEIGHT, $INPUT_WIDTH, $INPUT_CHANNELS], " +
                "DataType: $INPUT_DATA_TYPE, " +
                "Normalization: [0, 1] (RGB / 255.0)"
    }
}
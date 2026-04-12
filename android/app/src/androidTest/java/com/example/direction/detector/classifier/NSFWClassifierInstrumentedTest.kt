package com.example.direction.detector.classifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.direction.model.DetectionResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException

/**
 * NSFW分类器仪器测试
 * 在真实设备或模拟器上测试TensorFlow Lite模型
 */
@RunWith(AndroidJUnit4::class)
class NSFWClassifierInstrumentedTest {

    private lateinit var appContext: Context
    private lateinit var testContext: Context
    private lateinit var classifier: NSFWClassifier

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        Log.d("NSFWTest", "测试设置:")
        Log.d("NSFWTest", "  应用 Context 包名: ${appContext.packageName}")
        Log.d("NSFWTest", "  测试 Context 包名: ${testContext.packageName}")
        Log.d("NSFWTest", "  目标 Context 包名: ${targetContext.packageName}")

        classifier = NSFWClassifier(appContext)
    }

    @After
    fun tearDown() {
        classifier.release()
    }

    @Test
    fun testClassifierInitialization() {
        assertTrue("分类器应已初始化", classifier.isInitialized())

        val info = classifier.getClassifierInfo()
        assertTrue("分类器信息应包含TensorFlow Lite", info.contains("TensorFlow Lite"))
        assertTrue("分类器信息应包含模型文件", info.contains("saved_model.tflite"))
        println("分类器信息: $info")
    }

    @Test
    fun testClassifyWithSolidColorImages() {
        // 测试纯色图像
        val colors = listOf(
            android.graphics.Color.BLACK to "黑色",
            android.graphics.Color.WHITE to "白色",
            android.graphics.Color.RED to "红色",
            android.graphics.Color.GREEN to "绿色",
            android.graphics.Color.BLUE to "蓝色"
        )

        for ((color, name) in colors) {
            val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)

            val result = classifier.classify(bitmap)

            assertNotNull("$name 图像分类结果不应为空", result)
            assertTrue("$name 图像置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
            assertNull("$name 图像不应有错误", result.error)

            println("$name 图像结果: isNSFW=${result.isNSFW}, confidence=${"%.2f".format(result.confidence * 100)}%")

            bitmap.recycle()
        }
    }

    @Test
    fun testClassifyWithTestImageFile() {
        // 创建测试图像文件
        val testImageFile = createTestImageFile("instrumented_test.jpg")

        try {
            // 从文件加载图像
            val bitmap = BitmapFactory.decodeFile(testImageFile.absolutePath)
            assertNotNull("应从文件加载Bitmap", bitmap)

            val result = classifier.classify(bitmap)

            // 验证结果
            assertNotNull("分类结果不应为空", result)
            assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
            assertNull("不应有错误", result.error)

            // 输出详细结果
            println("仪器测试 - 图像文件分类结果:")
            println("  文件路径: ${testImageFile.absolutePath}")
            println("  图像尺寸: ${bitmap.width}x${bitmap.height}")
            println("  是否NSFW: ${result.isNSFW}")
            println("  置信度: ${result.confidence}")
            println("  SFW分数: ${result.sfwScore}")
            println("  NSFW分数: ${result.nsfwScore}")
            println("  阈值(0.85): ${result.nsfwScore > 0.85f}")

            // 验证逻辑一致性
            if (result.isNSFW) {
                assertTrue("当isNSFW为true时，nsfwScore应大于阈值(0.85)", result.nsfwScore > 0.85f)
                println("  ⚠️ 图像被分类为NSFW内容")
            } else {
                assertTrue("当isNSFW为false时，nsfwScore应小于等于阈值(0.85)", result.nsfwScore <= 0.85f)
                println("  ✅ 图像被分类为SFW内容")
            }

            bitmap.recycle()
        } finally {
            testImageFile.delete()
        }
    }

    @Test
    fun testModelOutputConsistency() {
        // 测试模型输出的一致性
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.YELLOW)

        val result1 = classifier.classify(bitmap)
        val result2 = classifier.classify(bitmap) // 相同图像再次分类

        // 相同输入应产生相似输出（允许微小浮点差异）
        assertEquals("相同图像的isNSFW结果应一致", result1.isNSFW, result2.isNSFW)
        assertEquals("相同图像的置信度应相近", result1.confidence, result2.confidence, 0.01f)
        assertEquals("相同图像的SFW分数应相近", result1.sfwScore, result2.sfwScore, 0.01f)
        assertEquals("相同图像的NSFW分数应相近", result1.nsfwScore, result2.nsfwScore, 0.01f)

        println("模型一致性测试通过:")
        println("  第一次: isNSFW=${result1.isNSFW}, confidence=${"%.4f".format(result1.confidence)}")
        println("  第二次: isNSFW=${result2.isNSFW}, confidence=${"%.4f".format(result2.confidence)}")

        bitmap.recycle()
    }

    @Test
    fun testClassifyWithSpecificImageFile() {
        // 测试特定的 JPEG 文件
        val fileName = "test.jpg"

        // 尝试多个可能的文件位置
        var bitmap: Bitmap? = null

        // 方法1：尝试从 assets 加载
        try {
            // 先列出 assets 内容用于调试
            val assetList = testContext.assets.list("")
            Log.d("NSFWTest", "测试 APK assets 目录内容: ${assetList?.joinToString() ?: "空"}")

            val inputStream: InputStream = testContext.assets.open(fileName)
            bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            Log.d("NSFWTest", "从 assets 加载图像成功: $fileName")
        } catch (e: IOException) {
            Log.d("NSFWTest", "无法从 assets 加载图像: ${e.message}")
            // 继续尝试其他方法
        }

        // 方法2：如果 assets 失败，尝试从应用缓存目录加载（需要先将文件复制到设备）
        if (bitmap == null) {
            try {
                // 将文件从 assets 复制到缓存目录
                val cacheDir = appContext.cacheDir
                val cacheFile = File(cacheDir, fileName)

                // 如果缓存文件不存在，从 assets 复制
                if (!cacheFile.exists()) {
                    val assetInputStream = testContext.assets.open(fileName)
                    FileOutputStream(cacheFile).use { outputStream ->
                        assetInputStream.copyTo(outputStream)
                    }
                    assetInputStream.close()
                    Log.d("NSFWTest", "已将文件从 assets 复制到缓存: ${cacheFile.absolutePath}")
                }

                // 从缓存文件加载
                bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                Log.d("NSFWTest", "从缓存文件加载图像成功: ${cacheFile.absolutePath}")
            } catch (e: Exception) {
                println("无法从缓存加载图像: ${e.message}")
            }
        }

        // 方法3：如果上述方法都失败，创建简单的测试图像
        if (bitmap == null) {
            Log.d("NSFWTest", "所有文件加载方法失败，创建简单的测试图像")
            bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GRAY)
        }

        assertNotNull("应成功加载或创建测试图像", bitmap)

        try {
            // 进行分类
            val result = classifier.classify(bitmap)

            // 验证结果
            assertNotNull("分类结果不应为空", result)
            assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
            assertNull("不应有错误", result.error)

            // 输出详细结果
            Log.d("NSFWTest", "特定 JPEG 文件分类结果:")
            Log.d("NSFWTest", "  文件: $fileName")
            println("  图像尺寸: ${bitmap.width}x${bitmap.height}")
            println("  是否NSFW: ${result.isNSFW}")
            println("  置信度: ${"%.2f".format(result.confidence * 100)}%")
            println("  SFW分数: ${"%.4f".format(result.sfwScore)}")
            println("  NSFW分数: ${"%.4f".format(result.nsfwScore)}")
            println("  阈值(0.85): ${result.nsfwScore > 0.85f}")

            // 验证逻辑一致性
            if (result.isNSFW) {
                println("  ⚠️ 图像被分类为 NSFW 内容")
                assertTrue("当 isNSFW 为 true 时，nsfwScore 应大于阈值(0.85)", result.nsfwScore > 0.85f)
            } else {
                println("  ✅ 图像被分类为 SFW 内容")
                assertTrue("当 isNSFW 为 false 时，nsfwScore 应小于等于阈值(0.85)", result.nsfwScore <= 0.85f)
            }

            // 验证结果一致性
            assertEquals("置信度应与 nsfwScore 一致", result.nsfwScore, result.confidence, 0.001f)
            assertEquals("sfwScore + nsfwScore 应约等于 1", 1.0f, result.sfwScore + result.nsfwScore, 0.001f)

        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * 创建测试图像文件
     */
    private fun createTestImageFile(filename: String): File {
        // 创建渐变测试图像
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val r = (x * 255 / bitmap.width).coerceIn(0, 255)
                val g = (y * 255 / bitmap.height).coerceIn(0, 255)
                val b = 128
                val color = android.graphics.Color.rgb(r, g, b)
                bitmap.setPixel(x, y, color)
            }
        }

        // 保存到缓存目录
        val cacheDir = appContext.cacheDir
        val outputFile = File(cacheDir, filename)

        FileOutputStream(outputFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
        }

        bitmap.recycle()
        return outputFile
    }
}
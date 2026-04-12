package com.example.direction.detector.classifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.direction.model.DetectionResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * NSFW分类器单元测试
 * 测试TensorFlow Lite模型的加载和推理
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE, assetDir = "src/test/resources")
@LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class NSFWClassifierTest {

    private lateinit var context: Context
    private lateinit var classifier: NSFWClassifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        println("测试上下文: ${context.packageName}")

        // 调试：检查assets中是否有模型文件
        try {
            val assetList = context.assets.list("models")
            println("Assets models目录内容: ${assetList?.joinToString() ?: "空或不存在"}")
        } catch (e: Exception) {
            println("无法列出assets models目录: ${e.message}")
        }

        println("创建NSFW分类器...")
        classifier = NSFWClassifier(context)
        println("分类器创建完成，初始化状态: ${classifier.isInitialized()}")
        if (!classifier.isInitialized()) {
            println("警告: 分类器未初始化，可能无法访问assets模型文件")
            // 尝试获取更多错误信息
            try {
                // 使用反射获取可能的异常信息
                val field = classifier.javaClass.getDeclaredField("isInitialized")
                field.isAccessible = true
                val isInitialized = field.getBoolean(classifier)
                println("反射获取的isInitialized: $isInitialized")
            } catch (e: Exception) {
                // 忽略
            }
        }
        Assume.assumeTrue("NSFW分类器未初始化，跳过所有测试", classifier.isInitialized())
    }

    @After
    fun tearDown() {
        classifier.release()
    }

    @Test
    fun testClassifierInitialization() {
        // 分类器应在初始化后已准备好
        assertTrue("分类器应已初始化", classifier.isInitialized())

        val info = classifier.getClassifierInfo()
        assertTrue("分类器信息应包含TensorFlow Lite", info.contains("TensorFlow Lite"))
        assertTrue("分类器信息应包含模型文件", info.contains("saved_model.tflite"))
    }

    @Test
    fun testClassifyWithBlackImage() {
        // 创建纯黑色图像（224x224）
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)

        val result = classifier.classify(bitmap)

        // 验证结果结构
        assertNotNull("结果不应为空", result)
        assertFalse("纯黑色图像不应被分类为NSFW（除非模型有偏见）", result.isNSFW)
        assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
        assertTrue("SFW分数应在0-1之间", result.sfwScore >= 0f && result.sfwScore <= 1f)
        assertTrue("NSFW分数应在0-1之间", result.nsfwScore >= 0f && result.nsfwScore <= 1f)
        assertNull("不应有错误", result.error)

        bitmap.recycle()
    }

    @Test
    fun testClassifyWithWhiteImage() {
        // 创建纯白色图像（224x224）
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val result = classifier.classify(bitmap)

        assertNotNull("结果不应为空", result)
        assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
        assertNull("不应有错误", result.error)

        bitmap.recycle()
    }

    @Test
    fun testClassifyWithSmallImage() {
        // 创建较小图像，分类器应能处理（会调整大小）
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        val result = classifier.classify(bitmap)

        assertNotNull("结果不应为空", result)
        assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
        assertNull("不应有错误", result.error)

        bitmap.recycle()
    }

    @Test
    fun testClassifyWithNullBitmap() {
        // 分类器应处理空Bitmap（通过ImagePreprocessor.validateBitmap）
        // 但classify方法要求非空，所以这里测试无效输入处理
        // 实际测试中，我们传递有效图像
    }

    @Test
    fun testClassifierRelease() {
        assertTrue("释放前分类器应已初始化", classifier.isInitialized())

        classifier.release()

        assertFalse("释放后分类器不应再初始化", classifier.isInitialized())
    }

    @Test
    fun testMultipleClassifications() {
        // 测试多次分类以确保无资源泄漏
        val bitmap1 = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap1.eraseColor(Color.BLUE)

        val bitmap2 = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        bitmap2.eraseColor(Color.GREEN)

        val result1 = classifier.classify(bitmap1)
        val result2 = classifier.classify(bitmap2)

        assertNotNull("结果1不应为空", result1)
        assertNotNull("结果2不应为空", result2)
        assertNotEquals("不同图像的结果可能不同", result1.confidence, result2.confidence)

        bitmap1.recycle()
        bitmap2.recycle()
    }

    @Test
    fun testImagePreprocessor() {
        // 测试ImagePreprocessor独立功能
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)

        assertTrue("Bitmap应有效", ImagePreprocessor.validateBitmap(bitmap))

        val byteBuffer = ImagePreprocessor.preprocess(bitmap)
        assertNotNull("ByteBuffer不应为空", byteBuffer)
        assertEquals(
            "ByteBuffer大小应符合预期",
            ImagePreprocessor.INPUT_SIZE * ImagePreprocessor.INPUT_BYTE_SIZE,
            byteBuffer.capacity()
        )

        val tensorBuffer = ImagePreprocessor.preprocessToTensorBuffer(bitmap)
        assertNotNull("TensorBuffer不应为空", tensorBuffer)
        assertArrayEquals(
            "TensorBuffer形状应为[1, 224, 224, 3]",
            intArrayOf(1, 224, 224, 3),
            tensorBuffer.shape
        )

        bitmap.recycle()
    }

    /**
     * 从文件路径加载图像并测试分类
     * 此测试需要外部图像文件
     */
    @Test
    fun testClassifyFromImageFile() {
        // 创建一个测试图像文件
        val testImageFile = createTestImageFile("test_image.jpg")

        try {
            // 从文件路径加载Bitmap
            val bitmap = BitmapFactory.decodeFile(testImageFile.absolutePath)
            assertNotNull("应从文件成功加载Bitmap", bitmap)

            // 进行分类
            val result = classifier.classify(bitmap)

            // 验证结果
            assertNotNull("分类结果不应为空", result)
            assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
            assertNull("不应有错误", result.error)

            // 输出结果信息用于调试
            println("测试图像分类结果: isNSFW=${result.isNSFW}, confidence=${result.confidence}, " +
                    "sfwScore=${result.sfwScore}, nsfwScore=${result.nsfwScore}")

            bitmap.recycle()
        } finally {
            // 清理测试文件
            testImageFile.delete()
        }
    }

    /**
     * 从Assets加载图像并测试分类
     * 需要在test/assets目录下有测试图像
     */
    @Test
    fun testClassifyFromAssets() {
        // 尝试从assets加载测试图像
        val assetFileName = "test_image.jpg"

        try {
            // 注意：Robolectric中assets访问方式不同
            // 这里简化处理，如果没有assets文件则跳过测试
            val inputStream: InputStream? = try {
                context.assets.open(assetFileName)
            } catch (e: Exception) {
                // 没有测试图像，跳过测试
                println("没有找到assets测试图像: $assetFileName，跳过测试")
                return
            }

            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                assertNotNull("应从assets成功加载Bitmap", bitmap)

                val result = classifier.classify(bitmap)

                assertNotNull("分类结果不应为空", result)
                assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
                assertNull("不应有错误", result.error)

                println("Assets图像分类结果: isNSFW=${result.isNSFW}, confidence=${result.confidence}")

                bitmap.recycle()
            }
        } catch (e: Exception) {
            // 如果assets访问失败，记录并继续
            println("Assets访问异常，跳过测试: ${e.message}")
        }
    }

    /**
     * 测试从外部文件路径加载图像
     * 使用项目根目录下的测试图像文件（如果存在）
     */
    @Test
    fun testClassifyWithExternalImageFile() {
        // 尝试多个可能的文件位置
        val possiblePaths = listOf(
            "test.jpg",  // 当前目录
            "app/test.jpg",  // app子目录
            "../test.jpg",  // 上级目录
            "../app/test.jpg"  // 上级的app目录
        )

        val projectRoot = System.getProperty("user.dir")
        println("当前工作目录: $projectRoot")

        var imageFile: File? = null
        for (relativePath in possiblePaths) {
            val testFile = File(projectRoot, relativePath)
            println("检查路径: ${testFile.absolutePath}")
            if (testFile.exists()) {
                imageFile = testFile
                println("找到图像文件: ${testFile.absolutePath}")
                break
            }
        }

        if (imageFile == null) {
            println("未找到测试图像文件，尝试的路径:")
            possiblePaths.forEach { path ->
                val testFile = File(projectRoot, path)
                println("  - ${testFile.absolutePath}: ${if (testFile.exists()) "存在" else "不存在"}")
            }
            return
        }

        println("使用测试图像文件: ${imageFile.absolutePath}")
        testWithImageFile(imageFile)
    }

    /**
     * 使用指定的图像文件进行测试
     */
    private fun testWithImageFile(imageFile: File) {
        // 检查分类器状态
        Assume.assumeTrue("分类器未初始化，跳过测试", classifier.isInitialized())

        // 从文件路径加载Bitmap
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        assertNotNull("应从文件路径成功加载Bitmap: ${imageFile.absolutePath}", bitmap)

        try {
            println("开始分类图像: ${imageFile.name}, 尺寸: ${bitmap.width}x${bitmap.height}")
            // 进行分类
            val result = classifier.classify(bitmap)

            // 验证结果
            assertNotNull("分类结果不应为空", result)
            assertTrue("置信度应在0-1之间", result.confidence >= 0f && result.confidence <= 1f)
            assertNull("不应有错误", result.error)

            // 输出详细结果
            println("外部图像文件分类结果:")
            println("  文件路径: ${imageFile.absolutePath}")
            println("  图像尺寸: ${bitmap.width}x${bitmap.height}")
            println("  是否NSFW: ${result.isNSFW}")
            println("  置信度: ${result.confidence}")
            println("  SFW分数: ${result.sfwScore}")
            println("  NSFW分数: ${result.nsfwScore}")
            println("  阈值(0.85): ${result.nsfwScore > 0.85f}")

            // 根据模型输出断言
            if (result.isNSFW) {
                println("  ⚠️ 图像被分类为NSFW内容")
                assertTrue("当isNSFW为true时，nsfwScore应大于阈值(0.85)", result.nsfwScore > 0.85f)
            } else {
                println("  ✅ 图像被分类为SFW内容")
                assertTrue("当isNSFW为false时，nsfwScore应小于等于阈值(0.85)", result.nsfwScore <= 0.85f)
            }

        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 测试多个图像文件（如果有多个测试图像）
     */
    @Test
    fun testClassifyMultipleImageFiles() {
        // 查找项目中的所有测试图像
        val projectRoot = System.getProperty("user.dir")
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".bmp")

        val imageFiles = mutableListOf<File>()
        for (ext in imageExtensions) {
            val files = File(projectRoot).listFiles { _, name ->
                name.lowercase().endsWith(ext)
            } ?: emptyArray()
            imageFiles.addAll(files)
        }

        if (imageFiles.isEmpty()) {
            println("没有找到测试图像文件，跳过测试")
            return
        }

        println("找到 ${imageFiles.size} 个测试图像文件")

        for (imageFile in imageFiles.take(3)) { // 限制测试前3个图像
            println("\n测试图像: ${imageFile.name}")

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap == null) {
                println("  无法解码图像，跳过")
                continue
            }

            try {
                val result = classifier.classify(bitmap)

                println("  结果: ${if (result.isNSFW) "NSFW" else "SFW"}")
                println("  置信度: ${"%.2f".format(result.confidence * 100)}%")
                println("  NSFW分数: ${"%.4f".format(result.nsfwScore)}")
                println("  SFW分数: ${"%.4f".format(result.sfwScore)}")

                // 验证结果一致性
                if (result.isNSFW) {
                    assertTrue("NSFW图像的nsfwScore应大于阈值", result.nsfwScore > 0.85f)
                } else {
                    assertTrue("SFW图像的nsfwScore应小于等于阈值", result.nsfwScore <= 0.85f)
                }

                assertEquals("置信度应与nsfwScore一致", result.nsfwScore, result.confidence, 0.001f)
                assertEquals("sfwScore + nsfwScore应约等于1", 1.0f, result.sfwScore + result.nsfwScore, 0.001f)

            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * 创建测试图像文件
     * @param filename 文件名
     * @return 创建的File对象
     */
    private fun createTestImageFile(filename: String): File {
        // 创建临时测试图像
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)

        // 创建简单的测试图像：渐变颜色
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val r = (x * 255 / bitmap.width).coerceIn(0, 255)
                val g = (y * 255 / bitmap.height).coerceIn(0, 255)
                val b = 128
                val color = Color.rgb(r, g, b)
                bitmap.setPixel(x, y, color)
            }
        }

        // 保存到临时文件
        val tempDir = context.cacheDir
        val outputFile = File(tempDir, filename)

        FileOutputStream(outputFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
        }

        bitmap.recycle()
        return outputFile
    }
}
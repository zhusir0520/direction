package com.example.direction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.ui.DetectionDetailDialog
import com.example.direction.ui.theme.DirectionTheme
import com.example.direction.utils.LogUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 历史记录详情页面
 * 专门用于显示历史记录的检测详情，确保正确显示图片和返回导航
 */
class HistoryDetailActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DETECTION_RESULT_JSON = "detection_result_json"
        const val EXTRA_SCREENSHOT_BYTES = "screenshot_bytes"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"

        /**
         * 启动历史记录详情页面
         * @param fromActivity 源Activity
         * @param detectionResult 检测结果
         * @param screenshotBitmap 截图Bitmap（可选）
         */
        fun start(fromActivity: ComponentActivity, detectionResult: DetectionResult, screenshotBitmap: Bitmap? = null) {
            val intent = android.content.Intent(fromActivity, HistoryDetailActivity::class.java).apply {
                val gson = Gson()
                val json = gson.toJson(detectionResult)
                putExtra(EXTRA_DETECTION_RESULT_JSON, json)

                screenshotBitmap?.let { bitmap ->
                    // 方法1：尝试保存到临时文件（避免TransactionTooLargeException）
                    try {
                        // 创建一个临时文件来保存图片
                        val tempFile = File(fromActivity.cacheDir, "temp_screenshot_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(tempFile).use { outputStream ->
                            // 使用JPEG格式压缩以减少文件大小
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        }
                        putExtra(EXTRA_SCREENSHOT_PATH, tempFile.absolutePath)
                        LogUtils.i("HistoryDetailActivity", "图片已保存到临时文件: ${tempFile.absolutePath}, 大小: ${tempFile.length()} bytes")
                    } catch (e: Exception) {
                        LogUtils.e("HistoryDetailActivity", "保存图片到临时文件失败，回退到字节数组", e)
                        // 方法2：回退到字节数组，但使用较低质量压缩
                        val stream = ByteArrayOutputStream()
                        // 使用JPEG格式并降低质量以减少数据大小
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        val byteArray = stream.toByteArray()
                        if (byteArray.size < 900 * 1024) { // 检查是否小于900KB（留出一些余量）
                            putExtra(EXTRA_SCREENSHOT_BYTES, byteArray)
                            LogUtils.i("HistoryDetailActivity", "使用字节数组传递图片，大小: ${byteArray.size} bytes")
                        } else {
                            LogUtils.e("HistoryDetailActivity", "图片数据太大(${byteArray.size} bytes)，无法通过Intent传递")
                        }
                    }
                }
            }
            fromActivity.startActivity(intent)
        }
    }

    private var detectionResult: DetectionResult? = null
    private var screenshotBitmap: Bitmap? = null
    private var tempScreenshotPath: String? = null
    private val detectionRepository by lazy { DetectionRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 解析Intent数据
        try {
            val json = intent.getStringExtra(EXTRA_DETECTION_RESULT_JSON)
            if (json == null) {
                Toast.makeText(this, "检测数据丢失", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val gson = Gson()
            detectionResult = gson.fromJson(json, DetectionResult::class.java)

            // 优先尝试从文件路径加载图片
            val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
            if (screenshotPath != null) {
                tempScreenshotPath = screenshotPath
                try {
                    val file = File(screenshotPath)
                    if (file.exists()) {
                        screenshotBitmap = BitmapFactory.decodeFile(screenshotPath)
                        LogUtils.i("HistoryDetailActivity", "从文件路径加载图片成功: $screenshotPath, 大小: ${file.length()} bytes")
                    } else {
                        LogUtils.w("HistoryDetailActivity", "图片文件不存在: $screenshotPath")
                    }
                } catch (e: Exception) {
                    LogUtils.e("HistoryDetailActivity", "从文件路径加载图片失败", e)
                }
            }

            // 如果文件路径方式失败，回退到字节数组
            if (screenshotBitmap == null) {
                val screenshotBytes = intent.getByteArrayExtra(EXTRA_SCREENSHOT_BYTES)
                if (screenshotBytes != null) {
                    screenshotBitmap = BitmapFactory.decodeByteArray(screenshotBytes, 0, screenshotBytes.size)
                    LogUtils.i("HistoryDetailActivity", "从字节数组加载图片成功，大小: ${screenshotBytes.size} bytes")
                }
            }
        } catch (e: Exception) {
            LogUtils.e("HistoryDetailActivity", "解析检测数据失败", e)
            Toast.makeText(this, "解析检测数据失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            DirectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HistoryDetailScreen(
                        detectionResult = detectionResult!!,
                        screenshotBitmap = screenshotBitmap,
                        detectionRepository = detectionRepository,
                        onBack = { finish() },
                        onDelete = { deleteRecord() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清空图片数据以释放内存
        screenshotBitmap?.recycle()
        screenshotBitmap = null
        detectionResult = null

        // 删除临时文件
        tempScreenshotPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.delete()) {
                        LogUtils.i("HistoryDetailActivity", "临时文件已删除: $path")
                    } else {
                        LogUtils.w("HistoryDetailActivity", "临时文件删除失败: $path")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("HistoryDetailActivity", "删除临时文件时出错", e)
            }
        }
        tempScreenshotPath = null
    }

    /**
     * 删除记录
     */
    private fun deleteRecord() {
        val result = detectionResult ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val success = detectionRepository.deleteResult(result)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@HistoryDetailActivity, "记录已删除", Toast.LENGTH_SHORT).show()
                    // 设置结果并返回
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@HistoryDetailActivity, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(
    detectionResult: DetectionResult,
    screenshotBitmap: Bitmap?,
    detectionRepository: DetectionRepository,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showDetailDialog by remember { mutableStateOf(true) } // 默认显示详情对话框

    // 当对话框关闭时，自动返回历史记录列表页
    LaunchedEffect(showDetailDialog) {
        if (!showDetailDialog) {
            // 延迟一小段时间，让对话框动画完成
            kotlinx.coroutines.delay(100)
            onBack()
        }
    }

    // 显示检测详情对话框
    if (showDetailDialog) {
        DetectionDetailDialog(
            result = detectionResult,
            screenshotBitmap = screenshotBitmap,
            debugImagesBitmaps = null, // 从repository加载调试图片
            onDismiss = {
                showDetailDialog = false
                // 对话框关闭后，LaunchedEffect会自动调用onBack()
            },
            onDelete = onDelete, // 历史记录模式下显示删除按钮
            detectionRepository = detectionRepository, // 传递repository以加载图片
            refreshKey = 0
        )
    }
}
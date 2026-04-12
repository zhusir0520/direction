package com.example.direction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.example.direction.detector.classifier.NSFWClassifier
import com.example.direction.detector.classifier.BackendNsfwDetector
import com.example.direction.model.DetectionResult
import com.example.direction.repository.SettingsRepository
import com.example.direction.repository.DetectionRepository
import com.example.direction.utils.LogUtils
import com.example.direction.ui.theme.DirectionTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 实时检测页面
 * 用户上传图片进行NSFW检测
 */
class DetectionActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_CODE_DETECTION_RESULT = 1001
    }

    // 依赖项
    private val nsfwClassifier by lazy { NSFWClassifier(this) }
    private val settingsRepository by lazy { SettingsRepository(this) }
    private val detectionRepository by lazy { DetectionRepository(this) }

    // 状态标志
    private var shouldClearDataOnResume = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // 处理选中的图片
            val bitmap = uriToBitmap(uri)
            if (bitmap != null) {
                // 更新UI状态
                _selectedImage = bitmap
                _imageName = getFileName(uri)
                _detectionResult = null
                _isLoading = false
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // UI状态（在Activity中管理以便从回调访问）
    private var _selectedImage by mutableStateOf<Bitmap?>(null)
    private var _imageName by mutableStateOf<String?>(null)
    private var _detectionResult by mutableStateOf<DetectionResult?>(null)
    private var _isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DirectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DetectionScreen(
                        selectedImage = _selectedImage,
                        imageName = _imageName,
                        detectionResult = _detectionResult,
                        isLoading = _isLoading,
                        onPickImage = { pickImageLauncher.launch("image/*") },
                        onStartDetection = { bitmap ->
                            if (bitmap != null) {
                                startDetection(bitmap)
                            }
                        },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    /**
     * 将URI转换为Bitmap
     */
    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            LogUtils.e("DetectionActivity", "转换URI为Bitmap失败", e)
            null
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        cursor.getString(displayNameIndex)
                    } else {
                        "image_${System.currentTimeMillis()}"
                    }
                } else {
                    "image_${System.currentTimeMillis()}"
                }
            }
        } catch (e: Exception) {
            LogUtils.e("DetectionActivity", "获取文件名失败", e)
            "image_${System.currentTimeMillis()}"
        }
    }

    /**
     * 开始检测
     */
    private fun startDetection(bitmap: Bitmap) {
        _isLoading = true
        _detectionResult = null

        // 在IO线程执行检测
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                val result = performRealTimeDetection(bitmap)
                _detectionResult = result

                // 实时检测不保存历史记录
                LogUtils.i("DetectionActivity", "实时检测完成: isNSFW=${result.isNSFW}")

                // 设置标志，表示即将启动结果页面
                shouldClearDataOnResume = true

                // 启动实时检测结果页面
                DetectionResultActivity.start(
                    fromActivity = this@DetectionActivity,
                    detectionResult = result,
                    screenshotBitmap = bitmap
                )

                // 检测完成，重置加载状态
                _isLoading = false

            } catch (e: Exception) {
                LogUtils.e("DetectionActivity", "检测失败", e)
                Toast.makeText(
                    this@DetectionActivity,
                    "检测失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                _isLoading = false
            }
        }
    }

    /**
     * 实时检测：无条件并行执行本地和远程检测
     */
    private suspend fun performRealTimeDetection(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.IO) {
        // 读取NSFW阈值
        val nsfwThreshold = try {
            settingsRepository.nsfwThreshold.first()
        } catch (e: Exception) {
            LogUtils.e("DetectionActivity", "读取NSFW阈值失败，使用默认值", e)
            NSFWClassifier.DEFAULT_NSFW_THRESHOLD
        }

        // 并行执行本地和远程检测
        val androidResultDeferred = async {
            nsfwClassifier.classify(bitmap, nsfwThreshold)
        }

        // 尝试获取后端URL（即使backendEnabled为false也尝试读取）
        val backendUrl = try {
            val url = settingsRepository.backendUrl.first()
            if (url.isNotEmpty()) url else null
        } catch (e: Exception) {
            LogUtils.e("DetectionActivity", "读取后端URL失败", e)
            null
        }

        val backendResultDeferred = if (backendUrl != null) {
            async {
                try {
                    // 无超时限制
                    val backendDetector = BackendNsfwDetector(backendUrl, nsfwThreshold)
                    backendDetector.detect(bitmap)
                } catch (e: Exception) {
                    LogUtils.w("DetectionActivity", "后端检测失败: ${e.message}")
                    null
                }
            }
        } else {
            LogUtils.d("DetectionActivity", "后端URL为空，跳过远程检测")
            null
        }

        // 等待本地检测完成（必须完成）
        val androidResult = androidResultDeferred.await()
        LogUtils.i("DetectionActivity", "本地检测完成: isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}")

        // 等待远程检测完成（如果有）
        val backendResult = backendResultDeferred?.await()
        if (backendResult != null) {
            LogUtils.i("DetectionActivity", "远程检测完成: isNsfw=${backendResult.backendIsNsfw}, confidence=${backendResult.backendConfidence}")
        }

        // 读取调试图片保存设置
        val saveDebugImages = try {
            settingsRepository.saveDebugImages.first()
        } catch (e: Exception) {
            LogUtils.e("DetectionActivity", "读取调试图片保存设置失败，使用默认值false", e)
            false
        }

        // 合并结果（类似NsfwMonitorService中的逻辑）
        val finalIsNsfw = androidResult.isNSFW || backendResult?.backendIsNsfw == true

        // 决定保存哪些后端信息
        val shouldSaveFullBackendInfo = !androidResult.isNSFW // SFW时保存完整信息

        val modelOutputToSave = if (shouldSaveFullBackendInfo) backendResult?.modelOutput else null
        val yoloResultToSave = if (shouldSaveFullBackendInfo) backendResult?.yoloResult else null
        val debugImagesToSave = if (shouldSaveFullBackendInfo && saveDebugImages) backendResult?.debugImages else null

        LogUtils.d("DetectionActivity", "实时检测合并结果: Android.isNSFW=${androidResult.isNSFW}, shouldSaveFullBackendInfo=$shouldSaveFullBackendInfo, saveDebugImages=$saveDebugImages")

        // 创建新的DetectionResult，根据条件保存后端信息
        androidResult.copy(
            isNSFW = finalIsNsfw,
            backendIsNsfw = backendResult?.backendIsNsfw,
            backendConfidence = backendResult?.backendConfidence,
            backendThreshold = backendResult?.backendThreshold,
            backendRawScores = backendResult?.backendRawScores,
            modelOutput = modelOutputToSave,
            yoloResult = yoloResultToSave,
            debugImages = debugImagesToSave,
            backendResponseRaw = backendResult?.backendResponseRaw
        )
    }

    override fun onResume() {
        super.onResume()
        if (shouldClearDataOnResume) {
            // 清空图片和数据
            _selectedImage = null
            _imageName = null
            _detectionResult = null
            _isLoading = false
            shouldClearDataOnResume = false
            LogUtils.d("DetectionActivity", "从结果页面返回，已清空数据")
        }
    }
}

@Composable
fun DetectionScreen(
    selectedImage: Bitmap?,
    imageName: String?,
    detectionResult: DetectionResult?,
    isLoading: Boolean,
    onPickImage: () -> Unit,
    onStartDetection: (Bitmap?) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "实时检测",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            Spacer(modifier = Modifier.width(48.dp)) // 平衡布局
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 上传按钮区域
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "选择图片进行检测",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = onPickImage,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("选择图片")
                }

                // 显示选中的图片信息
                if (imageName != null) {
                    Text(
                        text = "已选择: $imageName",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 图片预览
        if (selectedImage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "图片预览",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // 图片显示
                    Image(
                        bitmap = selectedImage.asImageBitmap(),
                        contentDescription = "待检测图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )

                    // 检测按钮
                    Button(
                        onClick = { onStartDetection(selectedImage) },
                        modifier = Modifier.fillMaxWidth(0.8f),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("检测中...")
                        } else {
                            Text("开始检测")
                        }
                    }
                }
            }
        }

        // 检测结果显示
        detectionResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.isNSFW) Color(0xFFFFCDD2) else Color(0xFFC8E6C9)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 结果标题
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (result.isNSFW) "⚠️ NSFW内容" else "✅ 安全内容",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (result.isNSFW) Color.Red else Color.Green
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(result.timestamp)),
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // 本地检测结果
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "本地检测",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("置信度:", fontSize = 14.sp)
                                Text(
                                    text = String.format("%.2f%%", result.confidence * 100),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (result.confidence > 0.7) Color.Red else Color.Green
                                )
                            }
                        }
                    }

                    // 后端检测结果（如果有）
                    if (result.backendIsNsfw != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "后端检测",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("结果:", fontSize = 14.sp)
                                    Text(
                                        text = if (result.backendIsNsfw == true) "NSFW" else "安全",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (result.backendIsNsfw == true) Color.Red else Color.Green
                                    )
                                }
                                if (result.backendConfidence != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("置信度:", fontSize = 14.sp)
                                        Text(
                                            text = String.format("%.2f%%", result.backendConfidence * 100),
                                            fontSize = 14.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 最终判定
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "最终判定",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (result.isNSFW)
                                    "⚠️ 检测到NSFW内容，请谨慎查看"
                                else
                                    "✅ 内容安全，可以正常查看",
                                fontSize = 14.sp,
                                color = if (result.isNSFW) Color.Red else Color.Green
                            )
                        }
                    }
                }
            }
        }

        // 提示信息
        if (selectedImage == null && !isLoading) {
            Text(
                text = "请先选择一张图片进行检测",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

/**
 * 实时检测结果展示组件（与历史记录详情页保持一致）
 */
@Composable
fun RealTimeDetectionResult(
    result: DetectionResult,
    originalImage: Bitmap?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var debugImagesBitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullScreenImageDescription by remember { mutableStateOf("") }
    var selectedDebugImageKey by remember { mutableStateOf<String?>(null) }

    // 屏幕宽度，用于响应式布局
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val isLargeScreen = screenWidth >= 600

    // 加载调试图片（从Base64解码）
    LaunchedEffect(result) {
        isLoading = true

        // 解码调试图片
        val debugBitmaps = mutableMapOf<String, Bitmap>()
        val debugImages = result.debugImages
        if (debugImages != null) {
            debugImages.forEach { (key, imageData) ->
                try {
                    if (imageData.base64.isNotEmpty()) {
                        // 从Base64解码
                        val imageBytes = android.util.Base64.decode(imageData.base64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            debugBitmaps[key] = bitmap
                        }
                    }
                } catch (e: Exception) {
                    // 忽略解码错误
                }
            }
        }
        debugImagesBitmaps = debugBitmaps

        // 设置默认选中的调试图片
        if (debugBitmaps.isNotEmpty() && selectedDebugImageKey == null) {
            selectedDebugImageKey = debugBitmaps.keys.firstOrNull { it.contains("yolo_detections") }
                ?: debugBitmaps.keys.first()
        }

        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        // 响应式布局：大屏幕水平排列，小屏幕垂直排列
        if (isLargeScreen) {
            // 水平两栏布局
            Row(
                modifier = modifier.fillMaxSize()
            ) {
                // 左侧图片区域（占60%宽度）
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    RealTimeImageContentSection(
                        originalImage = originalImage,
                        debugImagesBitmaps = debugImagesBitmaps,
                        selectedDebugImageKey = selectedDebugImageKey,
                        onSelectDebugImage = { key -> selectedDebugImageKey = key },
                        onImageClick = { bitmap, description ->
                            fullScreenImageBitmap = bitmap
                            fullScreenImageDescription = description
                            showFullScreenViewer = true
                        },
                        result = result
                    )
                }

                // 右侧结果区域（占40%宽度）
                Box(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    RealTimeResultContentSection(
                        result = result,
                        debugImagesBitmaps = debugImagesBitmaps
                    )
                }
            }
        } else {
            // 垂直布局
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 图片区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)  // 固定高度
                        .padding(16.dp)
                ) {
                    RealTimeImageContentSection(
                        originalImage = originalImage,
                        debugImagesBitmaps = debugImagesBitmaps,
                        selectedDebugImageKey = selectedDebugImageKey,
                        onSelectDebugImage = { key -> selectedDebugImageKey = key },
                        onImageClick = { bitmap, description ->
                            fullScreenImageBitmap = bitmap
                            fullScreenImageDescription = description
                            showFullScreenViewer = true
                        },
                        result = result
                    )
                }

                // 结果区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    RealTimeResultContentSection(
                        result = result,
                        debugImagesBitmaps = debugImagesBitmaps
                    )
                }
            }
        }
    }

    // 全屏图片查看器
    if (showFullScreenViewer && fullScreenImageBitmap != null) {
        // 暂时简化：使用Toast提示，不实现完整全屏查看器
        // 可以以后添加FullScreenImageViewer组件
        LaunchedEffect(showFullScreenViewer) {
            Toast.makeText(context, "查看全屏图片: $fullScreenImageDescription", Toast.LENGTH_SHORT).show()
            // 稍后自动关闭
            delay(2000)
            showFullScreenViewer = false
            fullScreenImageBitmap = null
            fullScreenImageDescription = ""
        }
    }
}

/**
 * 实时检测图片内容区域
 */
@Composable
private fun RealTimeImageContentSection(
    originalImage: Bitmap?,
    debugImagesBitmaps: Map<String, Bitmap>,
    selectedDebugImageKey: String?,
    onSelectDebugImage: (String) -> Unit,
    onImageClick: (Bitmap, String) -> Unit,
    result: DetectionResult
) {
    // 创建包含所有图片的列表：原始图片 + 调试图片
    val allImages = mutableMapOf<String, Bitmap>()
    // 添加原始图片（如果有）
    if (originalImage != null) {
        allImages["original"] = originalImage
    }
    // 添加调试图片
    allImages.putAll(debugImagesBitmaps)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 图片卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题
                Text(
                    text = "🖼️ Debug Images",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp)
                )

                if (allImages.isNotEmpty()) {
                    val imageKeys = allImages.keys.toList()
                    val currentIndex = imageKeys.indexOf(selectedDebugImageKey).coerceAtLeast(0)
                    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

                    // 监听滑动位置变化，更新选中的图片
                    LaunchedEffect(lazyListState.firstVisibleItemIndex) {
                        val newIndex = lazyListState.firstVisibleItemIndex
                        if (newIndex in imageKeys.indices) {
                            val newKey = imageKeys[newIndex]
                            if (newKey != selectedDebugImageKey) {
                                onSelectDebugImage(newKey)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 图片水平滑动列表
                        LazyRow(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(imageKeys.size) { index ->
                                val key = imageKeys[index]
                                val bitmap = allImages[key]!!
                                val description = when {
                                    key == "original" -> "检测截图 - 原始图片"
                                    key == "screenshot" -> "检测截图 - 原始分辨率"
                                    key.contains("yolo") && key.contains("detection") -> "YOLO检测图片（带检测框）"
                                    key.contains("yolo") && key.contains("hit") -> "YOLO裁剪区域图片"
                                    key.contains("yolo") && key.contains("crop") -> "YOLO裁剪区域图片"
                                    key.contains("yolo") && key.contains("union") -> "YOLO联合框图片"
                                    key.contains("yolo") -> "YOLO图片"
                                    else -> "调试图片"
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onImageClick(bitmap, description)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = description,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )

                                    // 图片信息
                                    Text(
                                        text = "${bitmap.width}×${bitmap.height}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )

                                    // 图片标签
                                    Text(
                                        text = description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // 指示器
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            imageKeys.forEachIndexed { index, key ->
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (index == currentIndex)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .padding(2.dp)
                                        .clickable {
                                            // 点击指示器跳转到对应图片
                                            onSelectDebugImage(key)
                                        }
                                )
                                if (index < imageKeys.size - 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("无图片", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 实时检测结果内容区域
 */
@Composable
private fun RealTimeResultContentSection(
    result: DetectionResult,
    debugImagesBitmaps: Map<String, Bitmap>
) {
    // 参考debug-ui.html的颜色方案（与历史记录详情页保持一致）
    val nsfwRed = Color(0xFFff4757)
    val safeGreen = Color(0xFF2ed573)
    val primaryBlue = Color(0xFF00adb5)
    val warningYellow = Color(0xFFffa502)
    val cardBackground = Color(0xFF2a2a3e)
    val surfaceDark = Color(0xFF1a1a2e)
    val textPrimary = Color(0xFFe6e6e6)
    val textSecondary = Color(0xFFb0b0b0)
    val borderColor = Color(0xFF393e46)

    // 折叠状态
    var localDetailsExpanded by remember { mutableStateOf(false) }
    var remoteDetailsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 主结果卡片 - 参考debug-ui.html的result-card设计（与历史记录详情页保持一致）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = cardBackground
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(
                width = 1.dp,
                color = borderColor
            )
        ) {
            // 左侧边框颜色表示状态（NSFW红色，安全绿色）
            Box(modifier = Modifier.fillMaxWidth()) {
                // 左侧状态条
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(5.dp)
                        .background(if (result.isNSFW) nsfwRed else safeGreen)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 结果头部 - 参考debug-ui.html的result-header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 状态
                        Text(
                            text = if (result.isNSFW) "🚨 NSFW DETECTED" else "✅ SAFE CONTENT",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (result.isNSFW) nsfwRed else safeGreen
                        )
                    }

                    // 总体结果描述 - 参考debug-ui.html的Overall Result
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Overall Result",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textSecondary
                        )
                        Text(
                            text = if (result.isNSFW) "Not Safe For Work content detected" else "Safe For Work content",
                            fontSize = 15.sp,
                            color = textPrimary
                        )
                    }

                    // 时间戳
                    Text(
                        text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(result.timestamp)),
                        fontSize = 12.sp,
                        color = textSecondary
                    )
                }
            }
        }

        // 本地检测详情卡片（可折叠）- 与历史记录详情页保持一致
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = cardBackground
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(
                width = 1.dp,
                color = borderColor
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 折叠标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📱 Local Detection",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimary
                    )
                    IconButton(
                        onClick = { localDetailsExpanded = !localDetailsExpanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (localDetailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (localDetailsExpanded) "收起" else "展开",
                            tint = textSecondary
                        )
                    }
                }

                if (localDetailsExpanded) {
                    // 本地检测详情内容
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 置信度
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Confidence", fontSize = 13.sp, color = textSecondary)
                            Text(
                                text = String.format("%.1f%%", result.confidence * 100),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryBlue
                            )
                        }

                        // 阈值
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Threshold", fontSize = 13.sp, color = textSecondary)
                            Text(
                                text = String.format("%.2f", result.threshold),
                                fontSize = 13.sp,
                                color = textSecondary
                            )
                        }

                        // 原始分类分数（如果有）
                        if (result.rawScores.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Raw Scores", fontSize = 12.sp, color = textSecondary)
                                result.rawScores.forEach { (category, score) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(category, fontSize = 11.sp, color = textSecondary)
                                        Text(
                                            text = String.format("%.1f%%", score * 100),
                                            fontSize = 11.sp,
                                            color = primaryBlue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 后端检测详情卡片（如果有后端结果）- 与历史记录详情页保持一致
        if (result.backendIsNsfw != null || result.backendConfidence != null || result.modelOutput != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = cardBackground
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = borderColor
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 折叠标题
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌐 Remote Detection",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = textPrimary
                        )
                        IconButton(
                            onClick = { remoteDetailsExpanded = !remoteDetailsExpanded },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (remoteDetailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (remoteDetailsExpanded) "收起" else "展开",
                                tint = textSecondary
                            )
                        }
                    }

                    if (remoteDetailsExpanded) {
                        // 后端检测详情内容
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 后端检测结果
                            if (result.backendIsNsfw != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Detection", fontSize = 13.sp, color = textSecondary)
                                    Text(
                                        text = if (result.backendIsNsfw == true) "NSFW 🔴" else "SFW 🟢",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (result.backendIsNsfw == true) nsfwRed else safeGreen
                                    )
                                }
                            }

                            // 后端置信度
                            if (result.backendConfidence != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Confidence", fontSize = 13.sp, color = textSecondary)
                                    Text(
                                        text = String.format("%.1f%%", result.backendConfidence * 100),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = primaryBlue
                                    )
                                }
                            }

                            // 后端原始分数（如果有）
                            if (result.backendRawScores != null && result.backendRawScores.isNotEmpty()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Raw Scores", fontSize = 12.sp, color = textSecondary)
                                    result.backendRawScores.forEach { (category, score) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(category, fontSize = 11.sp, color = textSecondary)
                                            Text(
                                                text = String.format("%.1f%%", score * 100),
                                                fontSize = 11.sp,
                                                color = primaryBlue
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // YOLO检测结果（如果有）
        result.yoloResult?.let { yoloResult ->
            if (yoloResult.triggered) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "YOLO物体检测",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("检测到物体:", fontSize = 14.sp)
                            Text(
                                text = if (yoloResult.detectedObjects) "是" else "否",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (yoloResult.detectedObjects && yoloResult.unionBox != null) {
                            Text(
                                text = "联合框: ${yoloResult.unionBox}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        if (yoloResult.detectedClasses?.isNotEmpty() == true) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("检测到的类别:", fontSize = 12.sp, color = Color.Gray)
                                yoloResult.detectedClasses.forEach { className ->
                                    Text("• $className", fontSize = 12.sp)
                                }
                            }
                        }

                        // 裁剪区域检测结果（如果有）
                        if (yoloResult.backendIsNsfw != null) {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "裁剪区域检测",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("结果:", fontSize = 14.sp)
                                Text(
                                    text = if (yoloResult.backendIsNsfw == true) "NSFW" else "安全",
                                    fontSize = 14.sp,
                                    color = if (yoloResult.backendIsNsfw == true) Color.Red else Color.Green
                                )
                            }
                            if (yoloResult.backendConfidence != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("置信度:", fontSize = 14.sp)
                                    Text(
                                        text = String.format("%.2f%%", yoloResult.backendConfidence * 100),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 调试图片信息
        if (debugImagesBitmaps.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "调试图片",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "共 ${debugImagesBitmaps.size} 张调试图片",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    debugImagesBitmaps.keys.forEach { key ->
                        Text("• $key", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
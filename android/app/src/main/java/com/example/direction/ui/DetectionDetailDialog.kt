package com.example.direction.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.utils.LogUtils
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 通用的检测详情对话框，可用于历史记录和实时检测结果
 *
 * @param result 检测结果
 * @param screenshotBitmap 主截图Bitmap（可选，如果为null且detectionRepository不为空，则从repository加载）
 * @param debugImagesBitmaps 调试图片Bitmap映射（可选，如果为null且detectionRepository不为空，则从repository加载）
 * @param onDismiss 关闭对话框回调
 * @param onDelete 删除记录回调（可选，实时检测可能不需要）
 * @param detectionRepository 检测仓库（可选，用于加载图片）
 * @param refreshKey 刷新键，改变时重新加载图片
 */
@Composable
fun DetectionDetailDialog(
    result: DetectionResult,
    screenshotBitmap: Bitmap? = null,
    debugImagesBitmaps: Map<String, Bitmap>? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    detectionRepository: DetectionRepository? = null,
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    var loadedScreenshotBitmap by remember(refreshKey) { mutableStateOf<Bitmap?>(screenshotBitmap) }
    var loadedDebugImagesBitmaps by remember(refreshKey) { mutableStateOf<Map<String, Bitmap>>(debugImagesBitmaps ?: emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullScreenImageDescription by remember { mutableStateOf("") }
    var selectedDebugImageKey by remember { mutableStateOf<String?>(null) }

    // 屏幕宽度，用于响应式布局
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val isLargeScreen = screenWidth >= 600

    // 加载截图和调试图片（如果需要）
    LaunchedEffect(result, detectionRepository, refreshKey) {
        isLoading = true

        // 如果screenshotBitmap未提供但repository可用，则从repository加载
        if (loadedScreenshotBitmap == null && result.screenshotPath != null && detectionRepository != null) {
            loadedScreenshotBitmap = detectionRepository.getDisplayScreenshot(result)
        }

        // 如果debugImagesBitmaps未提供但repository可用，则从repository加载
        if (loadedDebugImagesBitmaps.isEmpty() && result.debugImages != null && detectionRepository != null) {
            LogUtils.d("DetectionDetailDialog", "开始加载debugImages，数量: ${result.debugImages.size}")
            val debugBitmaps = mutableMapOf<String, Bitmap>()
            result.debugImages.forEach { (key, imageData) ->
                try {
                    LogUtils.d("DetectionDetailDialog", "处理debug图片: $key, localPath: ${imageData.localPath}, base64长度: ${imageData.base64.length}")
                    val bitmap = if (imageData.localPath != null) {
                        LogUtils.d("DetectionDetailDialog", "从localPath加载: ${imageData.localPath}")
                        val loadedBitmap = detectionRepository.loadDebugImage(imageData.localPath)
                        if (loadedBitmap == null) {
                            LogUtils.w("DetectionDetailDialog", "从localPath加载失败: ${imageData.localPath}")
                            // 尝试检查文件是否存在
                            val file = java.io.File(imageData.localPath)
                            LogUtils.w("DetectionDetailDialog", "文件存在: ${file.exists()}, 大小: ${file.length()}")
                        }
                        loadedBitmap
                    } else if (imageData.base64.isNotEmpty()) {
                        LogUtils.d("DetectionDetailDialog", "从base64加载，长度: ${imageData.base64.length}")
                        val imageBytes = android.util.Base64.decode(imageData.base64, android.util.Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    } else {
                        LogUtils.d("DetectionDetailDialog", "没有localPath或base64数据")
                        null
                    }
                    if (bitmap != null) {
                        debugBitmaps[key] = bitmap
                        LogUtils.d("DetectionDetailDialog", "成功加载debug图片: $key, 尺寸: ${bitmap.width}x${bitmap.height}")
                    } else {
                        LogUtils.d("DetectionDetailDialog", "加载debug图片失败: $key")
                    }
                } catch (e: Exception) {
                    LogUtils.e("DetectionDetailDialog", "加载调试图片失败: $key", e)
                }
            }
            loadedDebugImagesBitmaps = debugBitmaps
            LogUtils.d("DetectionDetailDialog", "debug图片加载完成，成功加载: ${debugBitmaps.size} 张")
        } else {
            LogUtils.d("DetectionDetailDialog", "跳过debug图片加载: loadedDebugImagesBitmaps.isEmpty=${loadedDebugImagesBitmaps.isEmpty()}, result.debugImages=${result.debugImages != null}, detectionRepository=${detectionRepository != null}")
        }

        // 设置默认选中的调试图片（如果有）
        if (loadedDebugImagesBitmaps.isNotEmpty() && selectedDebugImageKey == null) {
            selectedDebugImageKey = loadedDebugImagesBitmaps.keys.firstOrNull { it.contains("yolo_detections") }
                ?: loadedDebugImagesBitmaps.keys.first()
        }

        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLargeScreen) 0.9f else 0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background
        ) {
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
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 左侧图片区域（占60%宽度）
                        Box(
                            modifier = Modifier
                                .weight(0.6f)
                                .fillMaxHeight()
                                .padding(16.dp)
                        ) {
                            ImageContentSection(
                                screenshotBitmap = loadedScreenshotBitmap,
                                debugImagesBitmaps = loadedDebugImagesBitmaps,
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
                            ResultContentSection(
                                result = result,
                                debugImagesBitmaps = loadedDebugImagesBitmaps,
                                onDelete = onDelete ?: {},
                                onDismiss = onDismiss
                            )
                        }
                    }
                } else {
                    // 垂直布局
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 图片区域
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)  // 调整高度，平衡截图和调试图片显示
                                .padding(16.dp)
                        ) {
                            ImageContentSection(
                                screenshotBitmap = loadedScreenshotBitmap,
                                debugImagesBitmaps = loadedDebugImagesBitmaps,
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
                            ResultContentSection(
                                result = result,
                                debugImagesBitmaps = loadedDebugImagesBitmaps,
                                onDelete = onDelete ?: {},
                                onDismiss = onDismiss
                            )
                        }
                    }
                }
            }
        }
    }

    // 全屏图片查看器
    if (showFullScreenViewer && fullScreenImageBitmap != null) {
        FullScreenImageViewer(
            bitmap = fullScreenImageBitmap,
            onDismiss = {
                showFullScreenViewer = false
                fullScreenImageBitmap = null
                fullScreenImageDescription = ""
            },
            imageDescription = fullScreenImageDescription
        )
    }
}

/**
 * 历史记录专用的检测详情对话框（兼容现有代码）
 */
@Composable
fun HistoryDetectionDetailDialog(
    result: DetectionResult,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    detectionRepository: DetectionRepository? = null,
    onItemClick: (DetectionResult) -> Unit = {},
    refreshKey: Int = 0
) {
    DetectionDetailDialog(
        result = result,
        onDismiss = onDismiss,
        onDelete = onDelete,
        detectionRepository = detectionRepository,
        refreshKey = refreshKey
    )
}

/**
 * 实时检测结果专用的检测详情对话框（无删除功能）
 */
@Composable
fun RealTimeDetectionDetailDialog(
    result: DetectionResult,
    screenshotBitmap: Bitmap? = null,
    debugImagesBitmaps: Map<String, Bitmap>? = null,
    onDismiss: () -> Unit,
    refreshKey: Int = 0
) {
    DetectionDetailDialog(
        result = result,
        screenshotBitmap = screenshotBitmap,
        debugImagesBitmaps = debugImagesBitmaps,
        onDismiss = onDismiss,
        onDelete = null,
        detectionRepository = null,
        refreshKey = refreshKey
    )
}

/**
 * 图片内容区域
 */
@Composable
private fun ImageContentSection(
    screenshotBitmap: Bitmap?,
    debugImagesBitmaps: Map<String, Bitmap>,
    selectedDebugImageKey: String?,
    onSelectDebugImage: (String) -> Unit,
    onImageClick: (Bitmap, String) -> Unit,
    result: DetectionResult
) {
    // 调试日志
    LogUtils.d("ImageContentSection", "调用ImageContentSection: debugImagesBitmaps大小=${debugImagesBitmaps.size}, 键=${debugImagesBitmaps.keys}, selectedDebugImageKey=$selectedDebugImageKey")

    val scope = rememberCoroutineScope()

    // 创建包含所有图片的列表：截图 + 调试图片
    val allImages = mutableMapOf<String, Bitmap>()
    // 添加截图（如果有）
    if (screenshotBitmap != null) {
        allImages["screenshot"] = screenshotBitmap
    }
    // 添加调试图片
    allImages.putAll(debugImagesBitmaps)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Debug Images卡片 - 包含所有图片
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
                                    key == "screenshot" -> {
                                        val screenshotType = if (result.hasOriginalScreenshot) "原始分辨率" else "缩略图"
                                        "检测截图 - $screenshotType"
                                    }
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
                                            scope.launch {
                                                lazyListState.animateScrollToItem(index)
                                            }
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
 * 结果内容区域
 */
@Composable
private fun ResultContentSection(
    result: DetectionResult,
    debugImagesBitmaps: Map<String, Bitmap>,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // 参考debug-ui.html的颜色方案
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

    // 添加调试日志
    SideEffect {
        LogUtils.d("ResultContentSection", "YOLO结果状态: ${result.yoloResult != null}")
        LogUtils.d("ResultContentSection", "YOLO结果详情: ${result.yoloResult}")
        LogUtils.d("ResultContentSection", "调试图片数量: ${debugImagesBitmaps.size}, 键: ${debugImagesBitmaps.keys}")
        LogUtils.d("ResultContentSection", "原始debugImages: ${result.debugImages?.keys}")
        LogUtils.d("ResultContentSection", "modelOutput: ${result.modelOutput?.take(100)}...")
        LogUtils.d("ResultContentSection", "backendConfidence: ${result.backendConfidence}, backendIsNsfw: ${result.backendIsNsfw}")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 主结果卡片 - 参考debug-ui.html的result-card设计
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

        // 本地检测详情卡片（可折叠）
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
                            imageVector = if (localDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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

        // 后端检测详情卡片（如果有后端结果）
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
                                imageVector = if (remoteDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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

                            // YOLO结果（如果有）
                            if (result.yoloResult != null) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("🎯 YOLO Detection", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textPrimary)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Triggered", fontSize = 12.sp, color = textSecondary)
                                        Text(
                                            text = if (result.yoloResult.triggered) "Yes" else "No",
                                            fontSize = 12.sp,
                                            color = if (result.yoloResult.triggered) primaryBlue else textSecondary
                                        )
                                    }

                                    if (result.yoloResult.detectedObjects) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Objects Detected", fontSize = 12.sp, color = textSecondary)
                                            Text(
                                                text = "Yes",
                                                fontSize = 12.sp,
                                                color = warningYellow
                                            )
                                        }

                                        if (result.yoloResult.unionBox != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Union Box", fontSize = 12.sp, color = textSecondary)
                                                Text(
                                                    text = result.yoloResult.unionBox,
                                                    fontSize = 10.sp,
                                                    color = textSecondary
                                                )
                                            }
                                        }

                                        if (result.yoloResult.backendIsNsfw != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Crop Result", fontSize = 12.sp, color = textSecondary)
                                                Text(
                                                    text = if (result.yoloResult.backendIsNsfw == true) "NSFW" else "SFW",
                                                    fontSize = 12.sp,
                                                    color = if (result.yoloResult.backendIsNsfw == true) nsfwRed else safeGreen
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 模型输出（如果有）
                            if (result.modelOutput != null && result.modelOutput.isNotBlank()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Model Output", fontSize = 12.sp, color = textSecondary)
                                    Text(
                                        text = result.modelOutput.take(200) + if (result.modelOutput.length > 200) "..." else "",
                                        fontSize = 10.sp,
                                        color = textSecondary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(surfaceDark, RoundedCornerShape(4.dp))
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 删除按钮（仅当onDelete不为空时显示）
            if (onDelete != {}) {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除")
                }
            }

            // 关闭按钮
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}
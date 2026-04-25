package com.example.direction.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.utils.LogUtils
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.snapshotFlow
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

        // 设置默认选中的图片（优先级：yoloHitImage > yolo_detections > screenshot > 其他）
        val priorityOrder = listOf("yoloHitImage", "yolo_detections", "screenshot")
        val allImageKeys = (listOfNotNull("screenshot".takeIf { loadedScreenshotBitmap != null }) + loadedDebugImagesBitmaps.keys)
        if (allImageKeys.isNotEmpty() && selectedDebugImageKey == null) {
            selectedDebugImageKey = priorityOrder.firstOrNull { it in allImageKeys }
                ?: allImageKeys.first()
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
 * 图片内容区域 - 单图显示，优先级：命中图片 > 检测框图片 > 原始截图
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
    val scope = rememberCoroutineScope()

    // 创建有序图片列表（优先级：yoloHitImage > yolo_detections > screenshot > 其他）
    val priorityOrder = listOf("yoloHitImage", "yolo_detections", "screenshot")
    val allImageMap = mutableMapOf<String, Bitmap>()
    if (screenshotBitmap != null) allImageMap["screenshot"] = screenshotBitmap
    allImageMap.putAll(debugImagesBitmaps)

    // 按优先级排序
    val imageKeys = allImageMap.keys.toList().sortedBy { key ->
        val idx = priorityOrder.indexOf(key)
        if (idx >= 0) idx else priorityOrder.size
    }

    val totalCount = imageKeys.size
    val initialIndex = (imageKeys.indexOf(selectedDebugImageKey)).coerceIn(0, (totalCount - 1).coerceAtLeast(0))

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageKeys.size }
    )

    // 同步页面滑动 → 更新选中图片
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                if (imageKeys.isNotEmpty() && page < imageKeys.size) {
                    onSelectDebugImage(imageKeys[page])
                }
            }
    }

    // 根据key生成中文标签
    fun getImageLabel(key: String): String = when {
        key == "screenshot" -> "原始截图"
        key.contains("yoloHitImage") || (key.contains("yolo") && key.contains("hit")) -> "命中图片"
        key.contains("yolo_detections") || (key.contains("yolo") && key.contains("detection")) -> "检测框图片"
        key.contains("yolo") && key.contains("crop") -> "裁剪图片"
        key.contains("yolo") -> "检测图片"
        key.contains("preprocessed") -> "预处理图片"
        else -> "调试图片"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题 + 计数
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("检测图片", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (totalCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1}/$totalCount 张",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (totalCount > 0) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 左右滑动翻页
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val key = imageKeys[page]
                            val bitmap = allImageMap[key]
                            if (bitmap != null) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = getImageLabel(key),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onImageClick(bitmap, getImageLabel(key)) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentScale = ContentScale.Fit
                                    )

                                    // 图片标签（左上）
                                    Text(
                                        text = getImageLabel(key),
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(12.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )

                                    // 分辨率（右下）
                                    Text(
                                        text = "${bitmap.width}×${bitmap.height}",
                                        fontSize = 10.sp,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(12.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // 底部指示点
                        if (totalCount > 1) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                imageKeys.forEachIndexed { index, key ->
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                                else Color.White.copy(alpha = 0.5f)
                                            )
                                            .clickable {
                                                scope.launch { pagerState.animateScrollToPage(index) }
                                            }
                                    )
                                    if (index < imageKeys.size - 1) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无图片", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 结果内容区域 - 全中文显示
 */
@Composable
private fun ResultContentSection(
    result: DetectionResult,
    debugImagesBitmaps: Map<String, Bitmap>,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // 折叠状态
    var localDetailsExpanded by remember { mutableStateOf(false) }
    var remoteDetailsExpanded by remember { mutableStateOf(false) }

    // 分类名翻译
    fun translateCategory(category: String): String = when (category.lowercase()) {
        "drawing" -> "绘画"
        "hentai" -> "色情动漫"
        "neutral" -> "正常"
        "porn" -> "色情"
        "sexy" -> "性感"
        "nsfw" -> "NSFW"
        "sfw" -> "安全"
        else -> category
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ========== 主结果卡片 ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 左侧结果色条
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(6.dp)
                        .background(
                            if (result.isNSFW) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.secondary
                        )
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 状态徽章 + 标题
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 药丸形状态徽章
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (result.isNSFW)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (result.isNSFW) "不适宜" else "安全",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (result.isNSFW) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = "检测结果",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 结果说明
                    val backendNote = if (result.backendIsNsfw == true) "（后端辅助判定）" else ""
                    Text(
                        text = if (result.isNSFW) "检测到不适宜内容，请注意$backendNote" else "当前屏幕内容安全$backendNote",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 时间
                    Text(
                        text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(result.timestamp)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ========== 本地检测详情卡片（可折叠） ==========
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 折叠标题 - 整行可点击
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { localDetailsExpanded = !localDetailsExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 标题左侧小色条
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Text("本地检测", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Icon(
                        imageVector = if (localDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (localDetailsExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (localDetailsExpanded) {
                    // 数据区域 - 使用浅色背景卡片内嵌
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 置信度
                            DataRow("置信度", String.format("%.1f%%", result.confidence * 100), MaterialTheme.colorScheme.primary)

                            // 阈值
                            DataRow("阈值", String.format("%.0f%%", result.threshold * 100), MaterialTheme.colorScheme.onSurfaceVariant)

                            // 原始分类分数（带缩进）
                            if (result.rawScores.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "原始分数",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                    result.rawScores.forEach { (category, score) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(translateCategory(category), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = String.format("%.1f%%", score * 100),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
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

        // ========== 远程检测详情卡片（如果有后端结果） ==========
        if (result.backendIsNsfw != null || result.backendConfidence != null || result.modelOutput != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 折叠标题 - 整行可点击
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { remoteDetailsExpanded = !remoteDetailsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Text("远程检测", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Icon(
                            imageVector = if (remoteDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (remoteDetailsExpanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (remoteDetailsExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 检测结果
                                if (result.backendIsNsfw != null) {
                                    DataRow(
                                        "检测结果",
                                        if (result.backendIsNsfw == true) "不适宜内容" else "内容安全",
                                        if (result.backendIsNsfw == true) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.secondary
                                    )
                                }

                                // 置信度
                                if (result.backendConfidence != null) {
                                    DataRow("置信度", String.format("%.1f%%", result.backendConfidence * 100), MaterialTheme.colorScheme.primary)
                                }

                                // 后端原始分数（带缩进）
                                if (result.backendRawScores != null && result.backendRawScores.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "原始分数",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Medium
                                        )
                                        result.backendRawScores.forEach { (category, score) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(translateCategory(category), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    text = String.format("%.1f%%", score * 100),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                // YOLO物体检测结果（精简）
                                if (result.yoloResult != null) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "物体检测",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        DataRow(
                                            "触发检测",
                                            if (result.yoloResult.triggered) "是" else "否",
                                            if (result.yoloResult.triggered) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (result.yoloResult.detectedObjects) {
                                            DataRow("检测到目标", "是", MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }

                                // 模型输出
                                if (result.modelOutput != null && result.modelOutput.isNotBlank()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "模型输出",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = result.modelOutput.take(200) + if (result.modelOutput.length > 200) "..." else "",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
                                                .padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========== 底部操作按钮 ==========
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
            Button(onClick = onDismiss) { Text("关闭") }
        }
    }
}

/**
 * 数据行组件 - 用于显示标签+值的键值对
 */
@Composable
private fun DataRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}
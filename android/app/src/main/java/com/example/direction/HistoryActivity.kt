package com.example.direction

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
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
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.ui.FullScreenImageViewer
import com.example.direction.ui.HistoryDetectionDetailDialog
import com.example.direction.ui.theme.DirectionTheme
import com.example.direction.utils.LogUtils
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import com.google.gson.Gson

/**
 * 历史记录页面
 * 展示NSFW检测历史记录
 */
class HistoryActivity : ComponentActivity() {
    // 依赖项
    private val detectionRepository by lazy { DetectionRepository(this) }

    // 刷新请求标志
    private var refreshRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DirectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HistoryScreen(
                        detectionRepository = detectionRepository,
                        onBack = { finish() },
                        onOpenDetail = { result ->
                            openDetailActivity(result)
                        }
                    )
                }
            }
        }
    }

    /**
     * 打开检测详情页面
     */
    private fun openDetailActivity(result: com.example.direction.model.DetectionResult) {
        // 异步加载截图，然后启动详情页
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 加载截图（如果有）
                val screenshotBitmap = detectionRepository.getDisplayScreenshot(result)

                withContext(Dispatchers.Main) {
                    // 启动历史记录详情页，使用清理过的副本避免TransactionTooLargeException
                    val cleanedResult = result.createCleanedCopy()
                    HistoryDetailActivity.start(this@HistoryActivity, cleanedResult, screenshotBitmap)
                }
            } catch (e: Exception) {
                LogUtils.e("HistoryActivity", "加载截图失败，启动详情页时不带截图", e)
                withContext(Dispatchers.Main) {
                    // 即使截图加载失败，也启动详情页，使用清理过的副本避免TransactionTooLargeException
                    val cleanedResult = result.createCleanedCopy()
                    HistoryDetailActivity.start(this@HistoryActivity, cleanedResult, null)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    detectionRepository: DetectionRepository,
    onBack: () -> Unit,
    onOpenDetail: (com.example.direction.model.DetectionResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 状态
    val history = remember { mutableStateListOf<DetectionResult>() }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var resultToDelete by remember { mutableStateOf<DetectionResult?>(null) }
    var isRefreshing by remember { mutableStateOf(false) } // 防重入标志

    // 下拉刷新状态
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = isLoading || isRefreshing)

    // 加载历史记录函数
    fun loadHistory() {
        if (isRefreshing) return // 防重入检查

        scope.launch {
            isRefreshing = true
            isLoading = true
            try {
                val newHistory = detectionRepository.getRecentResults(limit = 0)
                history.clear()
                history.addAll(newHistory)
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    // 初始加载历史记录
    LaunchedEffect(Unit) {
        loadHistory()
    }

    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = { loadHistory() },
        swipeEnabled = true,
        refreshTriggerDistance = 80.dp,
        indicatorPadding = PaddingValues(top = 60.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
                    text = "历史记录",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                )
                Spacer(modifier = Modifier.width(48.dp)) // 平衡布局
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 加载状态
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (history.isEmpty()) {
                // 空状态
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "📊",
                        fontSize = 48.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = "暂无检测记录",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "进行实时检测后，记录将在此处显示",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            } else {
                // 历史记录列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 按日期分组
                    val grouped = history.groupBy { result ->
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(Date(result.timestamp))
                    }

                    grouped.forEach { (date, results) ->
                        item {
                            Text(
                                text = date,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                            )
                        }

                        items(results) { result ->
                            HistoryRecordItem(
                                result = result,
                                detectionRepository = detectionRepository,
                                onClick = {
                                    Log.d("HistoryActivity", "选择记录: ${result.timestamp}")
                                    onOpenDetail(result)
                                },
                                onDelete = {
                                    resultToDelete = result
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }


    // 删除确认对话框
    if (showDeleteConfirm && resultToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条检测记录吗？此操作将删除截图文件和检测记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val success = detectionRepository.deleteResult(resultToDelete!!)
                            if (success) {
                                Toast.makeText(context, "记录已删除", Toast.LENGTH_SHORT).show()
                                // 从列表中移除
                                history.remove(resultToDelete)
                            } else {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                            showDeleteConfirm = false
                            resultToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        resultToDelete = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 历史记录列表项（带缩略图）
 */
@Composable
fun HistoryRecordItem(
    result: DetectionResult,
    detectionRepository: DetectionRepository,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // 异步加载缩略图
    LaunchedEffect(result, detectionRepository) {
        if (result.screenshotPath != null && screenshotBitmap == null && !isLoading) {
            isLoading = true
            try {
                // 使用协程加载缩略图
                screenshotBitmap = withContext(Dispatchers.IO) {
                    detectionRepository.getScreenshot(result)
                }
            } catch (e: Exception) {
                // 忽略加载错误
                Log.e("HistoryRecordItem", "加载缩略图失败: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    Log.d("HistoryActivity", "点击记录: ${result.timestamp}")
                    onClick()
                },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isNSFW) Color(0xFFFFCDD2) else Color(0xFFC8E6C9)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图区域
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                if (screenshotBitmap != null) {
                    Image(
                        bitmap = screenshotBitmap!!.asImageBitmap(),
                        contentDescription = "检测截图",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    // 无缩略图占位符
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "无截图",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 文本信息区域
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 第一行：状态和时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态标签
                    Text(
                        text = if (result.isNSFW) "⚠️ NSFW" else "✅ 安全",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (result.isNSFW) Color.Red else Color.Green
                    )

                    // 时间
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(result.timestamp)),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 第二行：置信度信息
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "置信度: ${String.format("%.1f%%", result.confidence * 100)}",
                            fontSize = 13.sp
                        )
                        if (result.backendConfidence != null) {
                            Text(
                                text = "后端: ${String.format("%.1f%%", result.backendConfidence * 100)}",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    // 删除按钮
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // 后端检测结果（如果有）
                if (result.backendIsNsfw != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "后端: ${if (result.backendIsNsfw == true) "NSFW" else "安全"}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.example.direction.ui.theme.DirectionTheme
import com.example.direction.utils.LogUtils
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.state.ToggleableState
import java.text.SimpleDateFormat
import java.util.*

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

    // 状态
    val history = remember { mutableStateListOf<DetectionResult>() }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var resultToDelete by remember { mutableStateOf<DetectionResult?>(null) }
    var isRefreshing by remember { mutableStateOf(false) } // 防重入标志
    // 批量选择状态
    var isEditMode by remember { mutableStateOf(false) }
    val selectedResults = remember { mutableStateListOf<DetectionResult>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 折叠状态：记录已折叠的日期字符串
    val collapsedDates = remember { mutableStateListOf<String>() }

    // 下拉刷新状态
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = isLoading || isRefreshing)

    // 加载历史记录函数
    fun loadHistory() {
        if (isRefreshing) return // 防重入检查

        scope.launch {
            isRefreshing = true
            isLoading = true
            try {
                val newHistory = withContext(Dispatchers.IO) {
                    detectionRepository.getRecentResults(limit = 0)
                }
                history.clear()
                history.addAll(newHistory)
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    // 退出编辑模式
    fun exitEditMode() {
        isEditMode = false
        selectedResults.clear()
        collapsedDates.clear()
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
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isEditMode) exitEditMode() else onBack()
                }) {
                    Icon(
                        if (isEditMode) Icons.Default.Close else Icons.Default.ArrowBack,
                        contentDescription = if (isEditMode) "取消选择" else "返回"
                    )
                }
                Text(
                    text = if (isEditMode) "已选 ${selectedResults.size} 项" else "历史记录",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isEditMode) {
                    // 编辑模式：删除选中按钮
                    if (selectedResults.isNotEmpty()) {
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "批量删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                } else {
                    // 普通模式：进入选择模式按钮
                    IconButton(onClick = {
                        isEditMode = true
                        // 进入编辑模式时默认全部折叠
                        val grouped = history.groupBy { result ->
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                .format(Date(result.timestamp))
                        }
                        collapsedDates.clear()
                        collapsedDates.addAll(grouped.keys)
                    }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "选择"
                        )
                    }
                }
            }

            // 加载状态
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在加载历史记录...",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else if (history.isEmpty()) {
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
                        val isCollapsed = collapsedDates.contains(date)
                        val allSelected = results.all { selectedResults.contains(it) }

                        item(key = "date_$date") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (isCollapsed) collapsedDates.remove(date)
                                        else collapsedDates.add(date)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 展开/折叠指示器
                                Text(
                                    text = if (isCollapsed) "▶" else "▼",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Text(
                                    text = date,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isEditMode) {
                                    val triState = when {
                                        allSelected -> ToggleableState.On
                                        results.any { selectedResults.contains(it) } -> ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    }
                                    TriStateCheckbox(
                                        state = triState,
                                        onClick = {
                                            if (allSelected) {
                                                for (r in results) selectedResults.remove(r)
                                            } else {
                                                for (r in results) {
                                                    if (!selectedResults.contains(r)) selectedResults.add(r)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // 未折叠时才显示该日期的记录
                        val displayResults = if (isCollapsed) emptyList() else results
                        items(displayResults, key = { it.timestamp }) { result ->
                            HistoryRecordItem(
                                result = result,
                                detectionRepository = detectionRepository,
                                isEditMode = isEditMode,
                                isSelected = selectedResults.contains(result),
                                onClick = {
                                    if (isEditMode) {
                                        // 编辑模式：切换选中状态
                                        if (selectedResults.contains(result)) {
                                            selectedResults.remove(result)
                                        } else {
                                            selectedResults.add(result)
                                        }
                                    } else {
                                        Log.d("HistoryActivity", "选择记录: ${result.timestamp}")
                                        onOpenDetail(result)
                                    }
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

    // 单条删除确认对话框
    if (showDeleteConfirm && resultToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条检测记录吗？此操作将删除截图文件和检测记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = resultToDelete!!
                        // 立即从UI移除（乐观更新）
                        history.remove(toDelete)
                        showDeleteConfirm = false
                        resultToDelete = null
                        // 后台执行实际删除
                        scope.launch(Dispatchers.IO) {
                            val success = detectionRepository.deleteResult(toDelete)
                            if (!success) {
                                // 删除失败，重新加载
                                loadHistory()
                            }
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

    // 批量删除确认对话框
    if (showBatchDeleteConfirm && selectedResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 ${selectedResults.size} 条检测记录吗？此操作将删除相应的截图文件和检测记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selectedResults.toList()
                        // 立即从UI移除（乐观更新）
                        history.removeAll(toDelete)
                        exitEditMode()
                        showBatchDeleteConfirm = false
                        // 后台执行实际删除
                        scope.launch(Dispatchers.IO) {
                            val deleted = detectionRepository.deleteResults(toDelete)
                            if (deleted != toDelete.size) {
                                loadHistory()
                            }
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
                    onClick = { showBatchDeleteConfirm = false }
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
    isEditMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // 每次result变化时重新加载缩略图（修复列表复用导致缩略图错乱的bug）
    LaunchedEffect(result) {
        screenshotBitmap = null
        if (result.screenshotPath != null) {
            isLoading = true
            try {
                screenshotBitmap = withContext(Dispatchers.IO) {
                    detectionRepository.getScreenshot(result)
                }
            } catch (e: Exception) {
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
                onClick = onClick,
                indication = if (isEditMode) null else null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                result.isNSFW -> Color(0xFFFFCDD2)
                else -> Color(0xFFC8E6C9)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 编辑模式选中态圆圈
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color(0xFFE0E0E0)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 缩略图区域 - 放大到80dp
            Box(
                modifier = Modifier.size(80.dp),
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
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 文本信息区域（精简）
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态标签 + 时间
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (result.isNSFW) "⚠️ NSFW" else "✅ 安全",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (result.isNSFW) Color.Red else Color.Green
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(result.timestamp)),
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // 非编辑模式才显示单个删除按钮
                    if (!isEditMode) {
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
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 置信度：NSFW且由后端检测到时显示后端置信度
                val displayConfidence = if (result.backendIsNsfw == true && result.backendConfidence != null) {
                    result.backendConfidence
                } else {
                    result.confidence
                }
                val confidenceLabel = if (result.backendIsNsfw == true && result.backendConfidence != null) {
                    "后端置信度"
                } else {
                    "置信度"
                }
                Text(
                    text = "$confidenceLabel: ${String.format("%.1f%%", displayConfidence * 100)}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
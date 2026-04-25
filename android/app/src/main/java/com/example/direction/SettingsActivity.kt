package com.example.direction

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.direction.repository.SettingsRepository
import com.example.direction.ui.theme.DirectionTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页面
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var viewModel: SettingsViewModel
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建ViewModel
        val settingsRepository = SettingsRepository(applicationContext)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(settingsRepository) as T
            }
        }).get(SettingsViewModel::class.java)

        // 初始化通知权限Launcher
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                // 权限授予后自动开启通知设置
                lifecycleScope.launch {
                    viewModel.setNotificationSettings(true, true, true)
                    Toast.makeText(this@SettingsActivity, "通知权限已授予，通知已启用", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@SettingsActivity, "需要通知权限才能启用通知", Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            DirectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        onBack = { finish() },
                        viewModel = viewModel,
                        notificationPermissionLauncher = notificationPermissionLauncher
                    )
                }
            }
        }
    }
}

/**
 * 设置页面ViewModel
 */
class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    // 时间窗口设置
    val timeWindow = settingsRepository.timeWindow
    suspend fun saveTimeWindow(startHour: Int, endHour: Int, enabled: Boolean) {
        settingsRepository.saveTimeWindow(
            com.example.direction.model.TimeWindow(startHour, endHour, enabled)
        )
    }

    // 检测设置
    val detectionInterval = settingsRepository.detectionInterval
    suspend fun setDetectionInterval(minutes: Int) {
        settingsRepository.setDetectionInterval(minutes)
    }

    val nsfwThreshold = settingsRepository.nsfwThreshold
    suspend fun setNsfwThreshold(threshold: Float) {
        settingsRepository.setNsfwThreshold(threshold)
    }

    // 通知设置
    val notificationEnabled = settingsRepository.notificationEnabled
    val vibrationEnabled = settingsRepository.vibrationEnabled
    val soundEnabled = settingsRepository.soundEnabled
    suspend fun setNotificationSettings(enabled: Boolean, vibration: Boolean, sound: Boolean) {
        settingsRepository.setNotificationSettings(enabled, vibration, sound)
    }

    // 悬浮窗设置
    val floatingWindowEnabled = settingsRepository.floatingWindowEnabled
    val floatingWindowShowWarning = settingsRepository.floatingWindowShowWarning
    suspend fun setFloatingWindowEnabled(enabled: Boolean) {
        settingsRepository.setFloatingWindowEnabled(enabled)
    }
    suspend fun setFloatingWindowShowWarning(showWarning: Boolean) {
        settingsRepository.setFloatingWindowShowWarning(showWarning)
    }

    // 回到主页设置
    val bringToForeground = settingsRepository.bringToForeground
    suspend fun setBringToForeground(enabled: Boolean) {
        settingsRepository.setBringToForeground(enabled)
    }

    // 后端设置
    val backendEnabled = settingsRepository.backendEnabled
    val backendUrl = settingsRepository.backendUrl
    val saveDebugImages = settingsRepository.saveDebugImages
    suspend fun setBackendEnabled(enabled: Boolean) {
        settingsRepository.setBackendEnabled(enabled)
    }
    suspend fun setBackendUrl(url: String) {
        settingsRepository.setBackendUrl(url)
    }
    suspend fun setSaveDebugImages(enabled: Boolean) {
        settingsRepository.setSaveDebugImages(enabled)
    }

    // 统计
    suspend fun getDetectionStats() = settingsRepository.getDetectionStats()
    suspend fun resetDetectionStats() {
        settingsRepository.resetDetectionStats()
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    notificationPermissionLauncher: ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 状态变量
    var detectionInterval by remember { mutableStateOf(1) }
    var nsfwThreshold by remember { mutableStateOf(0.85f) }

    var notificationEnabled by remember { mutableStateOf(true) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var soundEnabled by remember { mutableStateOf(true) }

    var bringToForeground by remember { mutableStateOf(false) }

    var floatingWindowEnabled by remember { mutableStateOf(false) }
    var floatingWindowShowWarning by remember { mutableStateOf(true) }

    var backendEnabled by remember { mutableStateOf(true) }
    var backendUrl by remember { mutableStateOf("") }
    var saveDebugImages by remember { mutableStateOf(true) }

    // 从ViewModel加载设置
    LaunchedEffect(Unit) {
        // 检测设置
        detectionInterval = viewModel.detectionInterval.first()
        nsfwThreshold = viewModel.nsfwThreshold.first()

        // 通知设置
        notificationEnabled = viewModel.notificationEnabled.first()
        vibrationEnabled = viewModel.vibrationEnabled.first()
        soundEnabled = viewModel.soundEnabled.first()

        // 悬浮窗设置
        floatingWindowEnabled = viewModel.floatingWindowEnabled.first()
        floatingWindowShowWarning = viewModel.floatingWindowShowWarning.first()

        // 回到主页设置
        bringToForeground = viewModel.bringToForeground.first()

        // 后端设置
        backendEnabled = viewModel.backendEnabled.first()
        backendUrl = viewModel.backendUrl.first()
        saveDebugImages = viewModel.saveDebugImages.first()
    }

    // 检查通知权限
    val notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true // Android 13以下默认有权限
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "设置",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            Spacer(modifier = Modifier.width(48.dp)) // 平衡布局
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 检测设置
        SettingsSection(title = "本地检测设置") {
            Text(
                text = "以下设置控制本地TFLite模型的检测行为，区别于后端兜底检测。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsSlider(
                title = "检测间隔（分钟）",
                value = detectionInterval.toFloat(),
                onValueChange = { value ->
                    detectionInterval = value.toInt()
                    scope.launch {
                        viewModel.setDetectionInterval(detectionInterval)
                    }
                },
                valueRange = 1f..60f,
                steps = 58
            )

            SettingsSlider(
                title = "NSFW阈值",
                    value = nsfwThreshold,
                    onValueChange = { value ->
                        nsfwThreshold = value
                        scope.launch {
                            viewModel.setNsfwThreshold(value)
                        }
                    },
                    valueRange = 0f..1f,
                    steps = 99,
                    valueDisplay = { "${(it * 100).toInt()}%" }
                )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 通知设置
        SettingsSection(title = "通知设置") {
            // 智能通知开关：复合状态（权限状态 + 应用设置）
            val notificationSwitchState = remember(notificationPermissionGranted, notificationEnabled) {
                when {
                    !notificationPermissionGranted -> false  // 权限未授予时显示为关闭
                    else -> notificationEnabled              // 权限已授予时显示应用设置
                }
            }

            SettingsSwitch(
                title = "启用通知",
                checked = notificationSwitchState,
                onCheckedChange = { userWantsEnabled ->
                    if (userWantsEnabled && !notificationPermissionGranted) {
                        // 用户想开启但无权限 → 请求权限
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Android 13以下，通知权限默认授予
                            scope.launch {
                                viewModel.setNotificationSettings(true, vibrationEnabled, soundEnabled)
                                Toast.makeText(context, "Android 13以下系统已默认授予通知权限，通知已启用", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // 直接切换应用设置
                        notificationEnabled = userWantsEnabled
                        scope.launch {
                            viewModel.setNotificationSettings(userWantsEnabled, vibrationEnabled, soundEnabled)
                        }
                    }
                }
            )

            // 权限状态提示
            if (!notificationPermissionGranted) {
                Text(
                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        "需要通知权限才能启用通知（点击开关请求权限）"
                    } else {
                        "Android 13以下系统已默认授予通知权限"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (notificationPermissionGranted && notificationEnabled) {
                SettingsSwitch(
                    title = "启用震动",
                    checked = vibrationEnabled,
                    onCheckedChange = { checked ->
                        vibrationEnabled = checked
                        scope.launch {
                            viewModel.setNotificationSettings(notificationEnabled, checked, soundEnabled)
                        }
                    }
                )

                SettingsSwitch(
                    title = "启用声音",
                    checked = soundEnabled,
                    onCheckedChange = { checked ->
                        soundEnabled = checked
                        scope.launch {
                            viewModel.setNotificationSettings(notificationEnabled, vibrationEnabled, checked)
                        }
                    }
                )

                SettingsSwitch(
                    title = "NSFW检测时回到详情页",
                    checked = bringToForeground,
                    onCheckedChange = { checked ->
                        bringToForeground = checked
                        scope.launch {
                            viewModel.setBringToForeground(checked)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 悬浮窗设置
        SettingsSection(title = "悬浮窗设置") {
            SettingsSwitch(
                title = "启用悬浮窗",
                checked = floatingWindowEnabled,
                onCheckedChange = { checked ->
                    floatingWindowEnabled = checked
                    scope.launch {
                        viewModel.setFloatingWindowEnabled(checked)
                    }
                }
            )

            if (floatingWindowEnabled) {
                SettingsSwitch(
                    title = "显示NSFW警告",
                    checked = floatingWindowShowWarning,
                    onCheckedChange = { checked ->
                        floatingWindowShowWarning = checked
                        scope.launch {
                            viewModel.setFloatingWindowShowWarning(checked)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 后端设置
        SettingsSection(title = "后端服务兜底检测") {
            Text(
                text = "当本地TFLite模型判定为安全时，通过后端服务器进行二次验证以提高准确性。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsSwitch(
                title = "启用后端检测",
                checked = backendEnabled,
                onCheckedChange = { checked ->
                    backendEnabled = checked
                    scope.launch {
                        viewModel.setBackendEnabled(checked)
                    }
                }
            )

            if (backendEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "后端URL",
                        fontSize = 16.sp
                    )
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { value ->
                            backendUrl = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://10.0.2.2:8082/api/nsfw/detect") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.setBackendUrl(backendUrl)
                                Toast.makeText(context, "后端URL已保存", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存URL")
                    }
                }

                SettingsSwitch(
                    title = "保存调试图片",
                    checked = saveDebugImages,
                    onCheckedChange = { checked ->
                        saveDebugImages = checked
                        scope.launch {
                            viewModel.setSaveDebugImages(checked)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 统计信息
        SettingsSection(title = "统计信息") {
            var totalDetections by remember { mutableStateOf(0) }
            var nsfwDetections by remember { mutableStateOf(0) }
            var safeDetections by remember { mutableStateOf(0) }
            var nsfwPercentage by remember { mutableStateOf(0f) }

            LaunchedEffect(Unit) {
                val statsFlow = viewModel.getDetectionStats()
                val stats = statsFlow.first()
                totalDetections = stats.totalDetections
                nsfwDetections = stats.nsfwDetections
                safeDetections = stats.safeDetections
                nsfwPercentage = stats.nsfwPercentage
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("总检测次数")
                    Text("$totalDetections")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NSFW检测次数")
                    Text("$nsfwDetections")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("安全检测次数")
                    Text("$safeDetections")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("NSFW比例")
                    Text("%.1f%%".format(nsfwPercentage))
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        viewModel.resetDetectionStats()
                        Toast.makeText(context, "统计信息已重置", Toast.LENGTH_SHORT).show()
                        // 重新加载
                        val statsFlow = viewModel.getDetectionStats()
                        val stats = statsFlow.first()
                        totalDetections = stats.totalDetections
                        nsfwDetections = stats.nsfwDetections
                        safeDetections = stats.safeDetections
                        nsfwPercentage = stats.nsfwPercentage
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("重置统计信息")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 设置分区组件
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

/**
 * 设置开关组件
 */
@Composable
fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 16.sp
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 设置滑块组件
 */
@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueDisplay: (Float) -> String = { "%.2f".format(it) }
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 16.sp
            )
            Text(
                text = valueDisplay(value),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
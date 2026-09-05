package com.example.direction

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import com.example.direction.utils.LogUtils
import android.widget.Toast
import android.app.ActivityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.direction.manager.TimeWindowManager
import com.example.direction.service.NsfwMonitorService
import com.example.direction.service.NsfwAccessibilityService
import com.example.direction.detector.classifier.NSFWClassifier
import com.example.direction.detector.classifier.BackendNsfwDetector
import com.example.direction.model.DetectionResult
import com.example.direction.repository.DetectionRepository
import com.example.direction.ui.theme.DirectionTheme
import com.example.direction.utils.NotificationUtils
import com.example.direction.DetectionResultActivity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import rikka.shizuku.Shizuku
import com.example.direction.manager.ShizukuManager
import com.example.direction.manager.ShizukuState
import androidx.compose.foundation.Canvas
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private val timeWindowManager by lazy { TimeWindowManager(this) }
    private val nsfwClassifier by lazy { NSFWClassifier(this) }
    private val settingsRepository by lazy { com.example.direction.repository.SettingsRepository(this) }
    private val detectionRepository by lazy { DetectionRepository(this) }
    private val notificationUtils by lazy { NotificationUtils(this) }
    private val floatingWindowManager by lazy {
        com.example.direction.manager.FloatingWindowManager(
            this,
            onPositionChanged = { x, y ->
                scope.launch {
                    settingsRepository.setFloatingWindowPosition(x, y)
                    LogUtils.i("MainActivity", "悬浮窗位置已保存: x=$x, y=$y")
                }
            }
        )
    }
    private val scope = CoroutineScope(Dispatchers.Main)

    val shizukuManager = ShizukuManager()
    private var shizukuState by mutableStateOf(ShizukuState.NOT_RUNNING)

    /**
     * 检查NsfwMonitorService是否正在运行
     * @return 服务是否运行
     */
    private fun isNsfwMonitorServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = manager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { serviceInfo ->
            serviceInfo.service.className == NsfwMonitorService::class.java.name
        }
    }

    // UI权限状态（用于触发重组）
    private var uiPermissionState by mutableStateOf(false)
    // 通知权限状态
    private var notificationPermissionState by mutableStateOf(false)
    // 日志对话框显示状态
    private var showLogDialog by mutableStateOf(false)
    // 无障碍服务是否已开启
    private var accessibilityEnabled by mutableStateOf(false)
    // 无障碍监控开关（应用内设置）
    private var accessibilityMonitorEnabled by mutableStateOf(true)

    // 实时检测loading对话框显示状态
    private var showDetectionLoadingDialog by mutableStateOf(false)

    // MediaProjection停止广播接收器
    private lateinit var mediaProjectionStoppedReceiver: BroadcastReceiver

    // NSFW检测广播接收器（用于悬浮窗警告）
    private lateinit var nsfwDetectedReceiver: BroadcastReceiver

    // Shizuku 监听器
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch {
            shizukuManager.resetCommandCache()
            shizukuState = shizukuManager.getCurrentState()
            LogUtils.i("MainActivity", "Shizuku binder received, state=$shizukuState")
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch {
            shizukuManager.resetCommandCache()
            shizukuState = ShizukuState.NOT_RUNNING
            LogUtils.i("MainActivity", "Shizuku binder dead, state=NOT_RUNNING")
        }
    }

    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        scope.launch {
            shizukuManager.resetCommandCache()
            shizukuState = shizukuManager.getCurrentState()
            LogUtils.i("MainActivity", "Shizuku permission result: requestCode=$requestCode, grantResult=$grantResult, state=$shizukuState")
        }
    }

    // Compose状态（在Activity中管理以便从回调访问）
    private var _detectionResult by mutableStateOf<DetectionResult?>(null)
    private var _selectedImage by mutableStateOf<Bitmap?>(null)
    private var _isLoading by mutableStateOf(false)

    // 注册Activity结果回调
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogUtils.i("MainActivity", "MediaProjection权限结果: resultCode=${result.resultCode}, data=${if (result.data != null) "非空" else "空"}")
        val granted = result.resultCode == Activity.RESULT_OK

        // 更新UI权限状态
        uiPermissionState = granted
        LogUtils.i("MainActivity", "uiPermissionState更新为: $granted")

        if (granted) {
            LogUtils.i("MainActivity", "MediaProjection权限已授予")
            // 启动NSFW监控服务
            startNsfwMonitorService(result.resultCode, result.data)

            // 检查悬浮窗设置，如果启用则请求悬浮窗权限
            scope.launch {
                val floatingWindowEnabled = settingsRepository.floatingWindowEnabled.first()
                LogUtils.i("MainActivity", "悬浮窗设置状态: $floatingWindowEnabled")
                if (floatingWindowEnabled) {
                    requestOverlayPermission()
                } else {
                    LogUtils.i("MainActivity", "悬浮窗功能未启用，跳过权限请求")
                }
            }
        } else {
            LogUtils.w("MainActivity", "MediaProjection权限被拒绝")
        }
    }

    // 通知权限请求回调（Android 13+）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        LogUtils.i("MainActivity", "通知权限结果: granted=$granted")
        notificationPermissionState = granted
        // 同时更新通知权限状态（如果用户后来在设置中更改，需要在onResume中重新检查）
    }

    // 悬浮窗权限请求回调
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogUtils.i("MainActivity", "悬浮窗权限结果: resultCode=${result.resultCode}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            LogUtils.i("MainActivity", "悬浮窗权限已授予")
            // 权限已授予，启用悬浮窗
            scope.launch {
                settingsRepository.setFloatingWindowEnabled(true)
                // 读取保存的悬浮窗位置
                val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                floatingWindowManager.showFloatingWindow(savedX, savedY)
                LogUtils.i("MainActivity", "悬浮窗已显示，位置: x=$savedX, y=$savedY")
            }
        } else {
            LogUtils.w("MainActivity", "悬浮窗权限被拒绝")
            scope.launch {
                settingsRepository.setFloatingWindowEnabled(false)
            }
        }
    }


    // 注册文件选择器回调
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedImageUri ->
            scope.launch {
                _isLoading = true
                showDetectionLoadingDialog = true  // 显示loading对话框
                LogUtils.i("MainActivity", "开始处理选中的图片URI: $selectedImageUri")
                try {
                    // 将URI转换为Bitmap
                    LogUtils.i("MainActivity", "正在将URI转换为Bitmap")
                    val bitmap = uriToBitmap(selectedImageUri)
                    if (bitmap != null) {
                        LogUtils.i("MainActivity", "Bitmap加载成功: ${bitmap.width}x${bitmap.height}")
                        _selectedImage = bitmap
                        // 实时检测：无条件并行执行本地和远程检测
                        LogUtils.i("MainActivity", "开始执行实时检测")
                        val result = performRealTimeDetection(bitmap)
                        _detectionResult = result
                        LogUtils.i("MainActivity", "实时检测完成: isNSFW=${result.isNSFW}, 本地置信度=${result.confidence}, 后端结果=${result.backendIsNsfw}")

                        // 保存到检测历史（不保存截图，因为用户上传的是图片文件）
                        val saveDebugImages = try {
                            settingsRepository.saveDebugImages.first()
                        } catch (e: Exception) {
                            LogUtils.e("MainActivity", "读取调试图片保存设置失败，使用默认值false", e)
                            false
                        }

                        // 先保存结果，获取更新后的result（包含localPath）
                        LogUtils.i("MainActivity", "开始保存检测结果到历史记录，saveDebugImages=$saveDebugImages")
                        val savedResult = if (saveDebugImages && result.debugImages != null) {
                            // 如果启用了debug图片保存，需要等待保存完成以获取更新后的result
                            LogUtils.d("MainActivity", "等待debug图片保存完成...")
                            try {
                                // 调用一个同步版本的saveResult，等待保存完成，同时保存用户上传的图片作为截图
                                val savedResultWithLocalPath = detectionRepository.saveResultAndWait(result, bitmap, saveDebugImages)
                                LogUtils.d("MainActivity", "saveResultAndWait完成，检查debugImages:")
                                savedResultWithLocalPath.debugImages?.forEach { (key, imageData) ->
                                    LogUtils.d("MainActivity", "debugImage[$key]: localPath=${imageData.localPath}, base64长度=${imageData.base64.length}")
                                }
                                // 检查截图路径是否已设置
                                LogUtils.d("MainActivity", "截图路径: screenshotPath=${savedResultWithLocalPath.screenshotPath}, originalScreenshotPath=${savedResultWithLocalPath.originalScreenshotPath}")
                                savedResultWithLocalPath
                            } catch (e: Exception) {
                                LogUtils.e("MainActivity", "保存debug图片失败，使用原始结果", e)
                                result
                            }
                        } else {
                            // 不保存debug图片，直接异步保存，同时保存用户上传的图片作为截图
                            detectionRepository.saveResult(result, bitmap, null, saveDebugImages)
                            result
                        }
                        LogUtils.i("MainActivity", "实时检测结果已保存到历史记录")

                        // 发送通知（如果检测到NSFW内容）
                        if (savedResult.error == null) {
                            if (savedResult.isNSFW) {
                                LogUtils.i("MainActivity", "检测到NSFW内容，发送通知")
                                // 读取震动设置
                                val vibrationEnabled = try {
                                    settingsRepository.vibrationEnabled.first()
                                } catch (e: Exception) {
                                    LogUtils.e("MainActivity", "读取震动设置失败，使用默认值", e)
                                    true // 默认启用
                                }
                                notificationUtils.sendDetectionNotification(savedResult, enableVibration = vibrationEnabled)
                            } else {
                                LogUtils.i("MainActivity", "图片内容安全，不发送通知")
                            }
                        }

                        // 打开实时检测结果页面
                        LogUtils.i("MainActivity", "准备打开实时检测结果页面")
                        // 先隐藏loading，再启动结果页，做到无缝过渡
                        showDetectionLoadingDialog = false
                        try {
                            LogUtils.i("MainActivity", "正在打开DetectionResultActivity")
                            // 创建清理过的副本，移除大字段以避免TransactionTooLargeException
                            val cleanedResult = savedResult.createCleanedCopy()
                            LogUtils.i("MainActivity", "使用清理过的DetectionResult副本，debugImages数量: ${cleanedResult.debugImages?.size ?: 0}")
                            DetectionResultActivity.start(this@MainActivity, cleanedResult, bitmap)
                            LogUtils.i("MainActivity", "DetectionResultActivity启动调用完成")
                        } catch (e: Exception) {
                            LogUtils.e("MainActivity", "打开检测结果页面失败", e)
                            Toast.makeText(this@MainActivity, "打开检测结果页面失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        LogUtils.e("MainActivity", "无法加载选中的图片")
                        Toast.makeText(this@MainActivity, "无法加载选中的图片", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    LogUtils.e("MainActivity", "图片分类失败", e)
                    _detectionResult = DetectionResult.failure("分类失败: ${e.message}")
                    Toast.makeText(this@MainActivity, "图片分类失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    _isLoading = false
                    showDetectionLoadingDialog = false  // 隐藏loading对话框
                    LogUtils.i("MainActivity", "图片处理完成，isLoading设置为false")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化UI权限状态：检查服务是否正在运行
        uiPermissionState = isNsfwMonitorServiceRunning()
        LogUtils.i("MainActivity", "onCreate权限检查: 服务运行状态=$uiPermissionState")
        // 初始化通知权限状态
        notificationPermissionState = notificationUtils.areNotificationsEnabled()
        LogUtils.i("MainActivity", "onCreate权限检查: 初始状态为false，通知权限=$notificationPermissionState")
        // 初始化无障碍服务状态
        refreshAccessibilityState()
        scope.launch {
            settingsRepository.accessibilityMonitorEnabled.collect { accessibilityMonitorEnabled = it }
        }

        setContent {
            DirectionTheme {
                // 使用Surface替代Scaffold以保持简单
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scope = rememberCoroutineScope()
                    var shizukuKillEnabled by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        settingsRepository.shizukuKillEnabled.collect { shizukuKillEnabled = it }
                    }
                    ClashStyleAppContent(
                        timeWindowManager = timeWindowManager,
                        onRequestPermission = { toggleScreenCapturePermission() },
                        onUploadImage = { openImagePicker() },
                        onViewLogs = { showLogDialog = true },
                        onViewHistory = { startHistoryActivity() },
                        onShareLogs = { shareLogs() },
                        detectionResult = _detectionResult,
                        selectedImage = _selectedImage,
                        isLoading = _isLoading,
                        uiPermissionState = uiPermissionState,
                        notificationPermissionState = notificationPermissionState,
                        showDetectionLoadingDialog = showDetectionLoadingDialog,
                        shizukuState = shizukuState,
                        onRequestShizukuPermission = { shizukuManager.requestPermission() },
                        shizukuKillEnabled = shizukuKillEnabled,
                        onToggleShizukuKill = { enabled ->
                            scope.launch {
                                settingsRepository.setShizukuKillEnabled(enabled)
                            }
                        },
                        accessibilityEnabled = accessibilityEnabled,
                        onAccessibilityCardClick = { openAccessibilitySettings() }
                    )
                }

                // 日志对话框
                if (showLogDialog) {
                    LogDialog(
                        onDismiss = { showLogDialog = false },
                        onShare = {
                            shareLogs()
                            // 分享后可以保持对话框打开，也可以关闭
                            // showLogDialog = false
                        }
                    )
                }


            }
        }

        // 注册MediaProjection停止广播接收器
        mediaProjectionStoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == NsfwMonitorService.ACTION_MEDIA_PROJECTION_STOPPED) {
                    LogUtils.i("MainActivity", "收到MediaProjection停止广播，更新UI状态")
                    uiPermissionState = false
                }
            }
        }
        val filter = IntentFilter(NsfwMonitorService.ACTION_MEDIA_PROJECTION_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.registerReceiver(this, mediaProjectionStoppedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, mediaProjectionStoppedReceiver, filter, 0)
        }
        LogUtils.i("MainActivity", "MediaProjection停止广播接收器已注册")

        // 注册NSFW检测广播接收器（用于悬浮窗警告）
        nsfwDetectedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                LogUtils.d("MainActivity", "广播接收器onReceive被调用，action: ${intent?.action}")
                if (intent?.action == NsfwMonitorService.ACTION_NSFW_DETECTED) {
                    LogUtils.i("MainActivity", "收到NSFW检测广播，显示悬浮窗警告")
                    LogUtils.d("MainActivity", "广播意图: $intent, extras: ${intent.extras}")

                    scope.launch {
                        try {
                            val showWarning = settingsRepository.floatingWindowShowWarning.first()
                            LogUtils.d("MainActivity", "悬浮窗警告设置状态: $showWarning")
                            if (showWarning) {
                                LogUtils.d("MainActivity", "准备显示悬浮窗警告")
                                // 显示中央悬浮通知
                                floatingWindowManager.showCenteredNotification("想想你该干什么！")
                                LogUtils.d("MainActivity", "中央通知显示方法已调用")

                                // 回到应用首页由NsfwMonitorService在释放录屏资源后处理
                                // 此处不再调用bringAppToForeground()，避免与服务的调用冲突
                            } else {
                                LogUtils.w("MainActivity", "悬浮窗警告功能已禁用")
                            }
                        } catch (e: Exception) {
                            LogUtils.e("MainActivity", "处理NSFW检测广播失败", e)
                        }
                    }
                } else {
                    LogUtils.d("MainActivity", "收到非NSFW检测广播，忽略")
                }
            }
        }
        val nsfwFilter = IntentFilter(NsfwMonitorService.ACTION_NSFW_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.registerReceiver(this, nsfwDetectedReceiver, nsfwFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, nsfwDetectedReceiver, nsfwFilter, 0)
        }
        LogUtils.i("MainActivity", "NSFW检测广播接收器已注册")

        // 注册 Shizuku 监听器
        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        LogUtils.i("MainActivity", "Shizuku 监听器已注册")

        // 初始化 Shizuku 状态
        shizukuState = shizukuManager.getCurrentState()
        LogUtils.i("MainActivity", "Shizuku 初始状态: $shizukuState")

        // 检查是否需要请求权限
        checkAndRequestPermission()
    }


    override fun onResume() {
        super.onResume()
        LogUtils.i("MainActivity", "onResume: 简化版本，不自动启动服务")
        // 简化：不再自动启动服务，需要用户手动点击授权
        // 更新UI权限状态：检查服务是否正在运行
        uiPermissionState = isNsfwMonitorServiceRunning()
        LogUtils.i("MainActivity", "onResume: 服务运行状态=$uiPermissionState")
        // 更新通知权限状态（用户可能从设置中更改）
        notificationPermissionState = notificationUtils.areNotificationsEnabled()
        LogUtils.i("MainActivity", "onResume: 通知权限状态=$notificationPermissionState")
        // 刷新无障碍服务状态（用户可能从系统无障碍设置返回）
        refreshAccessibilityState()

        // 检查并恢复悬浮窗（如果已启用且有权限）
        scope.launch {
            try {
                val floatingWindowEnabled = settingsRepository.floatingWindowEnabled.first()
                LogUtils.d("MainActivity", "onResume: 悬浮窗设置状态=$floatingWindowEnabled")

                if (floatingWindowEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            LogUtils.i("MainActivity", "onResume: 悬浮窗已启用且有权限，恢复显示")
                            val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                            floatingWindowManager.showFloatingWindow(savedX, savedY)

                            // 检查最近是否有NSFW检测结果，有则显示悬浮窗警告
                            checkRecentNsfwForFloatingWarning()
                        } else {
                            LogUtils.w("MainActivity", "onResume: 悬浮窗已启用但无权限")
                        }
                    } else {
                        // Android M以下，悬浮窗权限可能默认授予
                        LogUtils.i("MainActivity", "onResume: Android M以下，悬浮窗权限可能默认授予，尝试显示")
                        val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                        floatingWindowManager.showFloatingWindow(savedX, savedY)
                        checkRecentNsfwForFloatingWarning()
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("MainActivity", "onResume: 恢复悬浮窗失败", e)
            }
        }
    }

    /**
     * 检查最近是否有NSFW检测结果，有则显示悬浮窗警告
     * 解决广播在Activity后台时无法送达的问题
     */
    private fun checkRecentNsfwForFloatingWarning() {
        scope.launch {
            try {
                val showWarning = settingsRepository.floatingWindowShowWarning.first()
                if (!showWarning) {
                    LogUtils.d("MainActivity", "checkRecentNsfw: 悬浮窗警告功能已禁用，跳过")
                    return@launch
                }

                val recentResults = detectionRepository.getRecentResults(limit = 1)
                if (recentResults.isNotEmpty()) {
                    val latest = recentResults.first()
                    val now = System.currentTimeMillis()
                    // 30秒内的NSFW结果才显示警告
                    if (latest.isNSFW && (now - latest.timestamp) < 30_000) {
                        LogUtils.i("MainActivity", "checkRecentNsfw: 检测到最近NSFW结果，显示悬浮窗警告")
                        floatingWindowManager.showCenteredNotification("想想你该干什么！")
                    } else {
                        LogUtils.d("MainActivity", "checkRecentNsfw: 无最新NSFW结果或已过期")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("MainActivity", "checkRecentNsfw: 检查失败", e)
            }
        }
    }

    /**
     * 启动历史记录页面
     */
    private fun startHistoryActivity() {
        val intent = Intent(this, HistoryActivity::class.java)
        startActivity(intent)
    }

    /**
     * 检查并在需要时请求权限
     */
    private fun checkAndRequestPermission() {
        // 简化：不自动检查权限
        LogUtils.i("MainActivity", "checkAndRequestPermission: 简化版本，不自动检查")
    }

    /**
     * 请求屏幕截图权限
     */
    private fun requestScreenCapturePermission() {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "请求屏幕截图权限失败", e)
            // 可以在这里显示错误提示
        }
    }

    /**
     * 请求通知权限（Android 13+）
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 13以下，通知权限默认授予
            LogUtils.i("MainActivity", "Android 13以下，通知权限默认授予")
        }
    }

    /**
     * 检查并请求悬浮窗权限
     * 在用户启用悬浮窗开关时调用
     */
    fun checkAndRequestFloatingWindowPermission() {
        LogUtils.i("MainActivity", "检查并请求悬浮窗权限")

        scope.launch {
            try {
                val floatingWindowEnabled = settingsRepository.floatingWindowEnabled.first()
                LogUtils.d("MainActivity", "当前悬浮窗设置状态: $floatingWindowEnabled")

                if (floatingWindowEnabled) {
                    LogUtils.i("MainActivity", "悬浮窗已启用，检查权限")
                    requestOverlayPermission()
                } else {
                    LogUtils.w("MainActivity", "悬浮窗未启用，跳过权限检查")
                }
            } catch (e: Exception) {
                LogUtils.e("MainActivity", "检查悬浮窗设置失败", e)
                Toast.makeText(this@MainActivity, "检查悬浮窗设置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 隐藏悬浮窗
     * 供外部调用（如设置界面）
     */
    fun hideFloatingWindow() {
        LogUtils.i("MainActivity", "隐藏悬浮窗")
        floatingWindowManager.hideFloatingWindow()
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // 跳转到系统设置页面
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                // 已有权限，直接启用悬浮窗
                LogUtils.i("MainActivity", "已有悬浮窗权限，直接启用")
                scope.launch {
                    settingsRepository.setFloatingWindowEnabled(true)
                    // 读取保存的悬浮窗位置
                    val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                    floatingWindowManager.showFloatingWindow(savedX, savedY)
                    LogUtils.i("MainActivity", "悬浮窗已显示，位置: x=$savedX, y=$savedY")
                }
            }
        } else {
            // Android M以下，悬浮窗权限默认授予（但通常不支持）
            LogUtils.i("MainActivity", "Android M以下，悬浮窗权限可能默认授予")
            scope.launch {
                settingsRepository.setFloatingWindowEnabled(true)
                // 读取保存的悬浮窗位置
                val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                floatingWindowManager.showFloatingWindow(savedX, savedY)
                LogUtils.i("MainActivity", "悬浮窗已显示，位置: x=$savedX, y=$savedY")
            }
        }
    }


    /**
     * 启动NSFW监控服务
     */
    private fun startNsfwMonitorService(resultCode: Int, data: Intent?) {
        try {
            NsfwMonitorService.startService(this, resultCode, data)
            LogUtils.i("MainActivity", "NSFW监控服务已启动")
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "启动NSFW监控服务失败", e)
        }
    }

    /**
     * 停止NSFW监控服务
     */
    private fun stopNsfwMonitorService() {
        try {
            NsfwMonitorService.stopService(this)
            LogUtils.i("MainActivity", "NSFW监控服务已停止")
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "停止NSFW监控服务失败", e)
        }
    }

    /**
     * 刷新无障碍服务状态
     */
    private fun refreshAccessibilityState() {
        accessibilityEnabled = NsfwAccessibilityService.isEnabled(this)
        LogUtils.i("MainActivity", "无障碍服务状态: $accessibilityEnabled")
    }

    /**
     * 打开无障碍服务设置（未开启时跳系统无障碍列表，已开启时深链到本应用详情）
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = if (accessibilityEnabled) {
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:$packageName")
                }
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            startActivity(intent)
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "打开无障碍设置失败", e)
            Toast.makeText(this, "打开无障碍设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换录屏权限（开启/关闭）
     */
    private fun toggleScreenCapturePermission() {
        // 无障碍优先：无障碍监控运行中时，停用手动录屏，避免双循环
        if (accessibilityEnabled && accessibilityMonitorEnabled) {
            LogUtils.i("MainActivity", "无障碍监控运行中，已停用手动录屏")
            Toast.makeText(this, "无障碍监控运行中，已停用手动录屏", Toast.LENGTH_SHORT).show()
            return
        }
        val isServiceRunning = isNsfwMonitorServiceRunning()
        if (isServiceRunning) {
            // 如果服务正在运行，停止它
            stopNsfwMonitorService()
            uiPermissionState = false
        } else {
            // 如果服务没有运行，请求权限开启
            requestScreenCapturePermission()
            // uiPermissionState会在权限授予后通过广播更新
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
            LogUtils.e("MainActivity", "转换URI为Bitmap失败", e)
            null
        }
    }


    /**
     * 打开图片选择器
     */
    fun openImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    /**
     * 实时检测：无条件并行执行本地和远程检测
     * 规则：
     * 1. 同时启动本地TFLite检测和远程后端检测（并行执行）
     * 2. 不需要检查backendEnabled开关，始终尝试远程检测
     * 3. 合并结果：finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw == true
     * 4. 保存策略：如果本地检测为SFW，保存完整远程信息；如果本地检测为NSFW，只保存基本远程信息
     * @param bitmap 要检测的图片
     * @return 合并后的DetectionResult
     */
    private suspend fun performRealTimeDetection(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.IO) {
        LogUtils.i("MainActivity", "performRealTimeDetection开始执行")
        // 读取NSFW阈值
        val nsfwThreshold = try {
            settingsRepository.nsfwThreshold.first()
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "读取NSFW阈值失败，使用默认值", e)
            NSFWClassifier.DEFAULT_NSFW_THRESHOLD
        }
        LogUtils.i("MainActivity", "NSFW阈值: $nsfwThreshold")

        // 并行执行本地和远程检测
        val androidResultDeferred = CoroutineScope(Dispatchers.IO).async {
            LogUtils.i("MainActivity", "开始本地TFLite分类")
            val result = nsfwClassifier.classify(bitmap, nsfwThreshold)
            LogUtils.i("MainActivity", "本地TFLite分类完成: isNSFW=${result.isNSFW}, confidence=${result.confidence}")
            result
        }

        // 尝试获取后端URL（即使backendEnabled为false也尝试读取）
        val backendUrl = try {
            val url = settingsRepository.backendUrl.first()
            if (url.isNotEmpty()) url else null
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "读取后端URL失败", e)
            null
        }

        val backendResultDeferred = if (backendUrl != null) {
            CoroutineScope(Dispatchers.IO).async {
                try {
                    // 无超时限制
                    val backendDetector = BackendNsfwDetector(backendUrl, nsfwThreshold)
                    backendDetector.detect(bitmap)
                } catch (e: Exception) {
                    LogUtils.w("MainActivity", "后端检测失败: ${e.message}")
                    null
                }
            }
        } else {
            LogUtils.d("MainActivity", "后端URL为空，跳过远程检测")
            null
        }

        // 等待本地检测完成（必须完成）
        val androidResult = androidResultDeferred.await()
        LogUtils.i("MainActivity", "本地检测完成: isNSFW=${androidResult.isNSFW}, confidence=${androidResult.confidence}")

        // 等待远程检测完成（如果有）
        val backendResult = backendResultDeferred?.await()
        if (backendResult != null) {
            LogUtils.i("MainActivity", "远程检测完成: isNsfw=${backendResult.backendIsNsfw}, confidence=${backendResult.backendConfidence}")
            LogUtils.d("MainActivity", "后端debugImages: ${backendResult.debugImages?.size ?: 0} 张, yoloResult: ${backendResult.yoloResult != null}")
            backendResult.debugImages?.forEach { (key, imageData) ->
                LogUtils.d("MainActivity", "后端debug图片: $key, localPath: ${imageData.localPath}, base64长度: ${imageData.base64.length}")
            }
        }

        // 读取调试图片保存设置
        val saveDebugImages = try {
            settingsRepository.saveDebugImages.first()
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "读取调试图片保存设置失败，使用默认值false", e)
            false
        }

        // 合并结果（类似NsfwMonitorService中的逻辑）
        val finalIsNsfw = androidResult.isNSFW || backendResult?.backendIsNsfw == true

        // 决定保存哪些后端信息
        // 修改：无论Android检测结果如何，都尝试保存debugImages和yoloResult（如果存在且saveDebugImages启用）
        val shouldSaveFullBackendInfo = !androidResult.isNSFW // SFW时保存完整信息

        val modelOutputToSave = if (shouldSaveFullBackendInfo) backendResult?.modelOutput else null
        // 修改：只要saveDebugImages启用，就保存yoloResult和debugImages
        val yoloResultToSave = if (saveDebugImages) backendResult?.yoloResult else null
        val debugImagesToSave = if (saveDebugImages) backendResult?.debugImages else null

        LogUtils.d("MainActivity", "实时检测合并结果: Android.isNSFW=${androidResult.isNSFW}, shouldSaveFullBackendInfo=$shouldSaveFullBackendInfo, saveDebugImages=$saveDebugImages, yoloResultToSave=${yoloResultToSave != null}, debugImagesToSave=${debugImagesToSave != null}")

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

    /**
     * 分享日志内容
     */
    fun shareLogs() {
        try {
            val logContent = LogUtils.getLogContent()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Direction应用日志")
                putExtra(Intent.EXTRA_TEXT, logContent)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志"))
            LogUtils.i("MainActivity", "分享日志，长度: ${logContent.length}")
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "分享日志失败", e)
            Toast.makeText(this, "分享日志失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        if (::mediaProjectionStoppedReceiver.isInitialized) {
            unregisterReceiver(mediaProjectionStoppedReceiver)
            LogUtils.i("MainActivity", "MediaProjection停止广播接收器已注销")
        }
        if (::nsfwDetectedReceiver.isInitialized) {
            unregisterReceiver(nsfwDetectedReceiver)
            LogUtils.i("MainActivity", "NSFW检测广播接收器已注销")
        }

        // 注销 Shizuku 监听器
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
            LogUtils.i("MainActivity", "Shizuku 监听器已注销")
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "注销 Shizuku 监听器失败", e)
        }
    }

    /**
     * 将应用带到前台
     * 当NSFW检测到并显示警告后，立即回到应用首页
     */
    private fun bringAppToForeground() {
        try {
            LogUtils.i("MainActivity", "尝试将应用带到前台")

            // 创建一个Intent来启动MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                // 清除任务栈，确保MainActivity是唯一的Activity
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            // 启动Activity
            startActivity(intent)
            LogUtils.i("MainActivity", "MainActivity已启动/带到前台")

        } catch (e: Exception) {
            LogUtils.e("MainActivity", "将应用带到前台失败", e)
        }
    }

}

@Composable
fun ClashStyleAppContent(
    timeWindowManager: TimeWindowManager,
    onRequestPermission: () -> Unit,
    onUploadImage: () -> Unit,
    onViewLogs: () -> Unit,
    onViewHistory: () -> Unit,
    onShareLogs: () -> Unit,
    detectionResult: DetectionResult?,
    selectedImage: Bitmap?,
    isLoading: Boolean,
    uiPermissionState: Boolean,
    notificationPermissionState: Boolean,
    showDetectionLoadingDialog: Boolean,
    shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    onRequestShizukuPermission: () -> Unit = {},
    shizukuKillEnabled: Boolean = true,
    onToggleShizukuKill: (Boolean) -> Unit = {},
    accessibilityEnabled: Boolean = false,
    onAccessibilityCardClick: () -> Unit = {}
) {
    // 状态
    val hasPermission = remember(uiPermissionState) { derivedStateOf {
        LogUtils.i("MainActivity", "UI状态计算: hasPermission=$uiPermissionState")
        uiPermissionState
    } }
    val permissionValid = remember(uiPermissionState) { derivedStateOf {
        // 简化：权限总是有效（没有过期机制）
        LogUtils.i("MainActivity", "UI状态计算: permissionValid=true")
        true
    } }
    val hasNotificationPermission = remember(notificationPermissionState) { derivedStateOf {
        LogUtils.i("MainActivity", "UI状态计算: hasNotificationPermission=$notificationPermissionState")
        notificationPermissionState
    } }

    var showAboutDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // 实时检测loading对话框 - 只显示旋转圆圈
    if (showDetectionLoadingDialog) {
        Dialog(
            onDismissRequest = { /* 不允许用户取消 */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            // 只显示旋转圆圈，没有背景框
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // 录屏状态：开启或关闭
    val isScreenCaptureEnabled = hasPermission.value && permissionValid.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 应用标题 - 调整位置：继续往下移
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 70.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Direction",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.weight(1f))

            // Shizuku 状态指示灯
            ShizukuIndicator(
                state = shizukuState,
                onRequestPermission = onRequestShizukuPermission,
                killEnabled = shizukuKillEnabled,
                onToggleKill = onToggleShizukuKill
            )
        }

        // 三个功能卡片（圆角矩形框）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. 无障碍监控卡片（主监控路径）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (accessibilityEnabled) {
                        Color(0xFF4CAF50) // 绿色 - 已开启
                    } else {
                        Color(0xFF9E9E9E) // 灰色 - 未开启
                    }
                ),
                onClick = {
                    onAccessibilityCardClick()
                }
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 无障碍监控图标
                    Image(
                        painter = painterResource(id = R.drawable.ic_screen_record),
                        contentDescription = "无障碍监控图标",
                        modifier = Modifier.size(28.dp)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (accessibilityEnabled) "无障碍监控已开启" else "点击开启无障碍监控",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = if (accessibilityEnabled) "一次授权，持续监控" else "一次授权，开机自动运行",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // 1. 录屏按钮卡片（备用路径）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isScreenCaptureEnabled) {
                        Color(0xFF4CAF50) // 绿色 - 已开启
                    } else {
                        Color(0xFF9E9E9E) // 灰色 - 已关闭
                    }
                ),
                onClick = {
                    // 点击切换录屏状态
                    onRequestPermission()
                }
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 录屏图标
                    Image(
                        painter = painterResource(id = R.drawable.ic_screen_record),
                        contentDescription = "录屏图标",
                        modifier = Modifier.size(28.dp)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isScreenCaptureEnabled) "录屏已开启" else "点击开启录屏",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = if (accessibilityEnabled) {
                                "备用模式（无障碍监控运行中）"
                            } else if (isScreenCaptureEnabled) {
                                "点击可关闭录屏权限"
                            } else {
                                "点击开启屏幕截图权限"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // 2. 检测卡片（实时检测）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                onClick = {
                    // 打开图片选择器进行实时检测
                    onUploadImage()
                }
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 检测图标
                    Image(
                        painter = painterResource(id = R.drawable.ic_detect),
                        contentDescription = "检测图标",
                        modifier = Modifier.size(28.dp)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "检测",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "实时检测图片内容",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // 3. 记录卡片（历史记录）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                onClick = {
                    // 查看历史记录
                    onViewHistory()
                }
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 记录图标
                    Image(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = "记录图标",
                        modifier = Modifier.size(28.dp)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "历史记录",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "查看检测历史记录",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 底部选项列表
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 日志选项
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = onViewLogs
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 日志图标
                        Image(
                            painter = painterResource(id = R.drawable.ic_log),
                            contentDescription = "日志图标",
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "日志",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = ">",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. 设置选项（包含通知权限开关）
            val context = LocalContext.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = {
                    // 打开设置页面
                    val intent = Intent(context, SettingsActivity::class.java)
                    context.startActivity(intent)
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 设置图标
                        Image(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "设置图标",
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "设置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = ">",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. 关于选项
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { showAboutDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 关于图标
                        Image(
                            painter = painterResource(id = R.drawable.ic_about),
                            contentDescription = "关于图标",
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "关于",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = ">",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // 关于对话框
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

/**
 * 关于对话框
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "1.0"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Direction", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "v$versionName",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "NSFW 屏幕内容检测工具",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "自动检测屏幕中的不适宜内容，\n保护您的隐私安全。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 日志对话框
 */
@Composable
fun LogDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val logContent = remember { LogUtils.getLogContent() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "应用日志", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "最近日志内容（最多1000行）",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 日志内容显示框
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(logContent) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Text(
                        text = logContent,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "日志文件: ${LogUtils.getLogFilePath() ?: "未初始化"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("分享")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}


/**
 * Shizuku 状态指示灯组件
 *
 * @param state Shizuku 状态
 * @param onRequestPermission 点击请求权限回调（仅在 NO_PERMISSION 状态可点击）
 * @param modifier 修饰符
 */
@Composable
fun ShizukuIndicator(
    state: ShizukuState,
    onRequestPermission: () -> Unit = {},
    killEnabled: Boolean = true,
    onToggleKill: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isRunning = state != ShizukuState.NOT_RUNNING
    val isOn = killEnabled && isRunning

    val backgroundColor = when {
        !isRunning -> Color(0xFFE53935) // 红 — 未运行，开关禁用
        isOn -> Color(0xFF43A047)        // 绿 — 运行中 + 开关开
        else -> Color(0xFFFFA000)        // 黄 — 运行中 + 开关关
    }

    val toggle = {
        if (isOn) {
            onToggleKill(false)
        } else if (state == ShizukuState.NO_PERMISSION) {
            onToggleKill(true)
            onRequestPermission()
        } else {
            onToggleKill(true)
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(backgroundColor)
            .clickable(enabled = isRunning, onClick = toggle)
            .padding(start = 5.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "SHIZUKU",
            color = Color.White,
            fontSize = 9.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        // 自定义迷你开关
        Box(
            modifier = Modifier
                .size(18.dp, 10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (isOn) Color.White.copy(alpha = 0.5f)
                    else Color.White.copy(alpha = 0.25f)
                )
                .padding(1.dp),
            contentAlignment = if (isOn) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(3.5.dp))
                    .background(Color.White)
            )
        }
    }
}
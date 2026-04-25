package com.example.direction

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.util.Base64
import com.example.direction.utils.LogUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.direction.model.BackendModelResultDto
import android.widget.Toast
import android.app.ActivityManager
import java.util.Calendar
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
// import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.direction.manager.TimeWindowManager
import com.example.direction.service.NsfwMonitorService
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore

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

    // 实时检测loading对话框显示状态
    private var showDetectionLoadingDialog by mutableStateOf(false)

    // MediaProjection停止广播接收器
    private lateinit var mediaProjectionStoppedReceiver: BroadcastReceiver

    // NSFW检测广播接收器（用于悬浮窗警告）
    private lateinit var nsfwDetectedReceiver: BroadcastReceiver

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
                        // 先显示一个Toast测试
                        Toast.makeText(this@MainActivity, "检测完成，正在打开结果页面...", Toast.LENGTH_SHORT).show()
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

        setContent {
            DirectionTheme {
                // 使用Surface替代Scaffold以保持简单
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
                        showDetectionLoadingDialog = showDetectionLoadingDialog
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
                        } else {
                            LogUtils.w("MainActivity", "onResume: 悬浮窗已启用但无权限")
                        }
                    } else {
                        // Android M以下，悬浮窗权限可能默认授予
                        LogUtils.i("MainActivity", "onResume: Android M以下，悬浮窗权限可能默认授予，尝试显示")
                        val (savedX, savedY) = settingsRepository.floatingWindowPosition.first()
                        floatingWindowManager.showFloatingWindow(savedX, savedY)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("MainActivity", "onResume: 恢复悬浮窗失败", e)
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
     * 切换录屏权限（开启/关闭）
     */
    private fun toggleScreenCapturePermission() {
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

} // 修复：正确闭合 MainActivity 类

/**
 * 应用主内容（Clash for Android样式）
 */
/* @Composable
旧UI函数，已弃用
fun DirectionAppContent(
    timeWindowManager: TimeWindowManager,
    onRequestPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onUploadImage: () -> Unit,
    onViewLogs: () -> Unit,
    onViewHistory: () -> Unit,
    onShareLogs: () -> Unit,
    detectionResult: DetectionResult?,
    selectedImage: Bitmap?,
    isLoading: Boolean,
    uiPermissionState: Boolean,
    notificationPermissionState: Boolean
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

    val scrollState = rememberScrollState()

    // 录屏状态：开启或关闭
    val isScreenCaptureEnabled = hasPermission.value && permissionValid.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "屏幕内容检测",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "应用状态",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )




                // 通知权限按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "通知权限",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (hasNotificationPermission.value) {
                        // 已授予 - 绿色小按钮
                        Button(
                            onClick = { },
                            modifier = Modifier.width(80.dp).height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50), // 绿色
                                contentColor = Color.White
                            ),
                            enabled = true // 保持颜色，但点击无反应
                        ) {
                            Text("已授予", fontSize = 12.sp)
                        }
                    } else {
                        // 未授予 - 灰色按钮，点击可授予
                        Button(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier.width(80.dp).height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF9E9E9E), // 灰色
                                contentColor = Color.White
                            )
                        ) {
                            Text("未授予", fontSize = 12.sp)
                        }
                    }
                }

            }
        }


        // 屏幕截图权限状态卡片（始终显示）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (hasPermission.value && permissionValid.value) {
                    Color(0xFF4CAF50) // 绿色 - 已授权且有效
                } else if (hasPermission.value && !permissionValid.value) {
                    Color(0xFFFF9800) // 橙色 - 已授权但过期
                } else {
                    Color(0xFF9E9E9E) // 灰色 - 未授权
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标和状态文本
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // 电源符号（白色）
                    if (hasPermission.value && permissionValid.value) {
                        Text(
                            text = "⚡",
                            fontSize = 24.sp,
                            color = Color.White
                        )
                    } else {
                        Text(
                            text = "📱",
                            fontSize = 24.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = when {
                                hasPermission.value && permissionValid.value -> "屏幕截图权限已开启"
                                hasPermission.value && !permissionValid.value -> "屏幕截图权限已过期"
                                else -> "屏幕截图权限已关闭"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = when {
                                hasPermission.value && permissionValid.value -> "检测功能运行中"
                                hasPermission.value && !permissionValid.value -> "权限已过期，请重新授权"
                                else -> "点击圆形按钮授权"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                // 圆形按钮（始终显示，根据状态变化）
                Button(
                    onClick = {
                        if (!hasPermission.value || !permissionValid.value) {
                            onRequestPermission()
                        }
                        // 如果权限有效，点击不执行操作（或可以执行停止服务等）
                    },
                    modifier = Modifier.size(100.dp).border(2.dp, Color.White, CircleShape),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            hasPermission.value && permissionValid.value -> Color(0xFF4CAF50) // 绿色 - 已授权且有效
                            hasPermission.value && !permissionValid.value -> Color(0xFFFF9800) // 橙色 - 已授权但过期
                            else -> Color(0xFF757575) // 深灰色 - 未授权（与背景#9E9E9E形成对比）
                        },
                        contentColor = Color.White
                    ),
                    enabled = !hasPermission.value || !permissionValid.value // 权限有效时不可点击
                ) {
                    Text(
                        text = when {
                            hasPermission.value && permissionValid.value -> "已开启"
                            hasPermission.value && !permissionValid.value -> "已过期"
                            else -> "授权"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }




        // 图片上传和分类功能
        ImageClassificationSection(
            onUploadImage = onUploadImage,
            detectionResult = detectionResult,
            selectedImage = selectedImage,
            isLoading = isLoading
        )

        // 检测设置已迁移到SettingsActivity中
        // DetectionSettingsSection() // 已弃用

        // 日志管理
        LogHistorySection(
            onViewLogs = onViewLogs,
            onViewHistory = onViewHistory,
            onShareLogs = onShareLogs
        )

        // 信息说明（可折叠）
        var isExpanded by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 可点击的标题行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ℹ️ 功能说明",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isExpanded) "▲" else "▼",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 可折叠内容
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "• 按设定间隔自动检测屏幕内容\n" +
                                    "• 检测到不适宜内容时会发送通知\n" +
                                    "• 截图权限仅用于内容检测，数据不会上传\n" +
                                    "• 权限在授予后24小时内有效",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
*/

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
    showDetectionLoadingDialog: Boolean
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "direction",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp,top = 70.dp, bottom = 8.dp)
            )
        }

        // 三个功能卡片（圆角矩形框）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 录屏按钮卡片
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
                            text = if (isScreenCaptureEnabled) "点击可关闭录屏权限" else "点击开启屏幕截图权限",
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
                            text = "记录",
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
                onClick = {
                    // 显示关于信息
                    // 暂时显示功能说明
                    // 可以打开一个对话框
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
}

/**
 * 状态行组件
 */
@Composable
fun StatusRow(
    label: String,
    value: String,
    status: Status
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = when (status) {
                Status.ACTIVE -> Color(0xFF2E7D32) // 绿色
                Status.INACTIVE -> Color(0xFF757575) // 灰色
                Status.WARNING -> Color(0xFFF57C00) // 橙色
                Status.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * 状态枚举
 */
enum class Status {
    ACTIVE,    // 活跃/正常
    INACTIVE,  // 不活跃/禁用
    WARNING,   // 警告
    NEUTRAL    // 中性
}

// @Preview(showBackground = true)
// @Composable
// fun DirectionAppPreview() {
//     DirectionTheme {
//         val mockPermissionManager = object : PermissionManager(androidx.compose.ui.platform.LocalContext.current) {
//             override fun hasMediaProjectionPermission(): Boolean = false
//             override fun isMediaProjectionPermissionValid(): Boolean = false
//         }
//         val mockTimeManager = TimeWindowManager(androidx.compose.ui.platform.LocalContext.current)
//
//         DirectionAppContent(
//             permissionManager = mockPermissionManager,
//             timeWindowManager = mockTimeManager,
//             onRequestPermission = {}
//         )
//     }
// }

/**
 * 图片上传和分类界面
 */
@Composable
fun ImageClassificationSection(
    onUploadImage: () -> Unit,
    detectionResult: DetectionResult?,
    selectedImage: Bitmap?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🖼️ 图片内容检测",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "上传图片实时查看NSFW/SFW分类结果",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 上传按钮
            Button(
                onClick = onUploadImage,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("处理中...")
                } else {
                    Text("📁")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("选择图片")
                }
            }

            // 图片预览
            selectedImage?.let { bitmap ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "已选图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 分类结果
            detectionResult?.let { result ->
                if (result.error != null) {
                    // 错误状态
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFEBEE) // 浅红色
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "❌ 分类失败",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFC62828)
                            )
                            Text(
                                text = result.error,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    // 成功结果
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 总体结果卡片
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (result.isNSFW) Color(0xFFFFF3E0) else Color(0xFFE8F5E9)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (result.isNSFW) "⚠️ NSFW（不适宜内容）" else "✅ SFW（安全内容）",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (result.isNSFW) Color(0xFFF57C00) else Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = "${"%.1f".format(result.confidence * 100)}%",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // SFW/NSFW分数条
                                ScoreBar(
                                    sfwScore = result.sfwScore,
                                    nsfwScore = result.nsfwScore
                                )

                                // SFW/NSFW分数文本
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "SFW: ${"%.1f".format(result.sfwScore * 100)}%",
                                        fontSize = 14.sp,
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = "NSFW: ${"%.1f".format(result.nsfwScore * 100)}%",
                                        fontSize = 14.sp,
                                        color = Color(0xFFF57C00)
                                    )
                                }
                            }
                        }

                        // 原始分类分数（五项）
                        if (result.rawScores.isNotEmpty()) {
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
                                        text = "📊 原始分类分数",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )

                                    // 五项分类分数
                                    result.rawScores.entries.forEach { (category, score) ->
                                        RawScoreRow(category = category, score = score)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * SFW/NSFW分数条
 */
@Composable
private fun ScoreBar(
    sfwScore: Float,
    nsfwScore: Float,
    modifier: Modifier = Modifier
) {
    val total = sfwScore + nsfwScore
    val sfwRatio = if (total > 0) sfwScore / total else 0.5f
    val nsfwRatio = if (total > 0) nsfwScore / total else 0.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.Start
    ) {
        // SFW部分（绿色）
        Box(
            modifier = Modifier
                .weight(sfwRatio)
                .fillMaxHeight()
                .background(Color(0xFF4CAF50))
        )
        // NSFW部分（橙色）
        Box(
            modifier = Modifier
                .weight(nsfwRatio)
                .fillMaxHeight()
                .background(Color(0xFFFF9800))
        )
    }
}

/**
 * 原始分类分数行
 */
@Composable
private fun RawScoreRow(
    category: String,
    score: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (category) {
                "Drawing" -> "绘画"
                "Hentai" -> "动漫色情"
                "Neutral" -> "正常图片"
                "Porn" -> "真人色情"
                "Sexy" -> "性感"
                else -> category
            },
            fontSize = 14.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 分数条
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width((score.coerceIn(0f, 1f) * 100f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2196F3))
                )
            }

            Text(
                text = "${"%.1f".format(score * 100)}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 检测设置界面
 */
@Deprecated(
    "此函数已弃用，检测设置已迁移到SettingsActivity中",
    replaceWith = ReplaceWith("SettingsActivity", "com.example.direction.SettingsActivity")
)
@Composable
fun DetectionSettingsSection() {
    val context = LocalContext.current
    val settingsRepository = remember { com.example.direction.repository.SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // 检测间隔状态
    var detectionInterval by remember { mutableIntStateOf(15) }
    var isUpdating by remember { mutableStateOf(false) }
    // NSFW阈值状态
    var threshold by remember { mutableStateOf(0.85f) }
    var isUpdatingThreshold by remember { mutableStateOf(false) }
    // 悬浮窗设置状态
    var floatingWindowEnabled by remember { mutableStateOf(false) }
    var floatingWindowShowWarning by remember { mutableStateOf(true) }
    var isUpdatingFloatingWindow by remember { mutableStateOf(false) }

    // 后端兜底设置状态
    var backendEnabled by remember { mutableStateOf(true) }
    var backendUrl by remember { mutableStateOf("") }
    var saveDebugImages by remember { mutableStateOf(false) }
    var isUpdatingBackend by remember { mutableStateOf(false) }
    // 无效间隔弹窗状态（已移除）

    // 从设置加载当前间隔
    LaunchedEffect(Unit) {
        try {
            settingsRepository.detectionInterval.collect { interval ->
                detectionInterval = interval
            }
        } catch (e: Exception) {
            // 忽略错误，使用默认值
        }
    }

    // 从设置加载当前NSFW阈值
    LaunchedEffect(Unit) {
        try {
            settingsRepository.nsfwThreshold.collect { value ->
                threshold = value
            }
        } catch (e: Exception) {
            // 忽略错误，使用默认值
        }
    }

    // 从设置加载悬浮窗设置
    LaunchedEffect(Unit) {
        try {
            settingsRepository.floatingWindowEnabled.collect { enabled ->
                floatingWindowEnabled = enabled
            }
        } catch (e: Exception) {
            // 忽略错误，使用默认值
        }
        try {
            settingsRepository.floatingWindowShowWarning.collect { showWarning ->
                floatingWindowShowWarning = showWarning
            }
        } catch (e: Exception) {
            // 忽略错误，使用默认值
        }
    }

    // 从设置加载后端兜底设置
    LaunchedEffect(Unit) {
        // 使用独立的launch块收集每个Flow，避免一个异常影响其他
        launch {
            try {
                settingsRepository.backendEnabled.collect { enabled ->
                    LogUtils.d("DetectionSettings", "backendEnabled Flow发出: $enabled")
                    backendEnabled = enabled
                }
            } catch (e: Exception) {
                LogUtils.e("DetectionSettings", "收集backendEnabled失败", e)
                // 忽略错误，使用默认值
            }
        }
        launch {
            try {
                settingsRepository.backendUrl.collect { url ->
                    LogUtils.d("DetectionSettings", "backendUrl Flow发出: $url")
                    backendUrl = url
                }
            } catch (e: Exception) {
                LogUtils.e("DetectionSettings", "收集backendUrl失败", e)
                // 忽略错误，使用默认值
            }
        }
        launch {
            try {
                settingsRepository.saveDebugImages.collect { enabled ->
                    LogUtils.d("DetectionSettings", "saveDebugImages Flow发出: $enabled")
                    saveDebugImages = enabled
                }
            } catch (e: Exception) {
                LogUtils.e("DetectionSettings", "收集saveDebugImages失败", e)
                // 忽略错误，使用默认值
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚙️ 检测设置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            // 检测间隔设置
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "检测间隔",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "${detectionInterval}分钟",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }




                // 自定义间隔输入
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "自定义:",
                        fontSize = 14.sp
                    )

                    var customIntervalText by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = customIntervalText,
                        onValueChange = { customIntervalText = it.filter { char -> char.isDigit() } },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入分钟数") },
                        singleLine = true,
                        supportingText = {
                            if (customIntervalText.isNotEmpty()) {
                                val value = customIntervalText.toIntOrNull()
                                when {
                                    value == null -> Text("请输入有效数字")
                                    value < 1 || value > 1440 -> Text("输入1-1440之间的数字")
                                    value < 1 -> Text("间隔必须大于0分钟")
                                    else -> null
                                }
                            }
                        }
                    )

                    Button(
                        onClick = {
                            val value = customIntervalText.toIntOrNull()
                            if (value != null && value in 1..1440 && !isUpdating && detectionInterval != value) {
                                detectionInterval = value
                                isUpdating = true
                                customIntervalText = ""

                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        settingsRepository.setDetectionInterval(value)
                                        // 前台服务将通过Flow自动更新间隔
                                        LogUtils.i("DetectionSettings", "检测间隔已更新为${value}分钟")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "检测间隔已更新为${value}分钟", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        LogUtils.e("DetectionSettings", "更新检测间隔失败", e)
                                    } finally {
                                        isUpdating = false
                                    }
                                }
                            }
                        },
                        enabled = customIntervalText.toIntOrNull() in 1..1440 && !isUpdating
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("应用")
                        }
                    }
                }

                if (isUpdating) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "更新中...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider( // 修复：使用 HorizontalDivider 替换 Divider
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // NSFW阈值设置
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "NSFW置信度阈值",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "${(threshold * 100).toInt()}%",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 阈值滑块
                Slider(
                    value = threshold,
                    onValueChange = { newValue ->
                        threshold = newValue
                    },
                    valueRange = 0.0f..1.0f,
                    steps = 19, // 0.05步长
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("0%", fontSize = 12.sp)
                    Text("50%", fontSize = 12.sp)
                    Text("100%", fontSize = 12.sp)
                }

                // 阈值保存按钮
                Button(
                    onClick = {
                        if (!isUpdatingThreshold) {
                            isUpdatingThreshold = true

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    settingsRepository.setNsfwThreshold(threshold)
                                    LogUtils.i("DetectionSettings", "NSFW阈值已更新为${(threshold * 100).toInt()}%")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "NSFW阈值已更新为${(threshold * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    LogUtils.e("DetectionSettings", "更新NSFW阈值失败", e)
                                } finally {
                                    isUpdatingThreshold = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUpdatingThreshold
                ) {
                    if (isUpdatingThreshold) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("更新中...")
                    } else {
                        Text("保存阈值设置")
                    }
                }

                if (isUpdatingThreshold) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "更新中...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "阈值说明：NSFW概率超过此阈值时判定为不适宜内容。\n" +
                            "当前阈值：${(threshold * 100).toInt()}%，服务将在下次检测时自动应用新阈值。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            // 悬浮窗设置
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "悬浮窗设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                // 启用悬浮窗开关
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "启用悬浮窗",
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = floatingWindowEnabled,
                        onCheckedChange = { newValue ->
                            floatingWindowEnabled = newValue
                            isUpdatingFloatingWindow = true
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    settingsRepository.setFloatingWindowEnabled(newValue)
                                    LogUtils.i("DetectionSettings", "悬浮窗启用状态已更新: $newValue")

                                    if (newValue) {
                                        // 如果启用悬浮窗，检查并请求权限
                                        withContext(Dispatchers.Main) {
                                            val mainActivity = context as? MainActivity
                                            if (mainActivity != null) {
                                                mainActivity.checkAndRequestFloatingWindowPermission()
                                            } else {
                                                LogUtils.e("DetectionSettings", "无法获取MainActivity实例")
                                                Toast.makeText(context, "无法启用悬浮窗", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        // 如果禁用悬浮窗，隐藏悬浮窗
                                        withContext(Dispatchers.Main) {
                                            val mainActivity = context as? MainActivity
                                            if (mainActivity != null) {
                                                mainActivity.hideFloatingWindow()
                                            }
                                            Toast.makeText(context, "悬浮窗已禁用", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogUtils.e("DetectionSettings", "更新悬浮窗设置失败", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "更新悬浮窗设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    isUpdatingFloatingWindow = false
                                }
                            }
                        },
                        enabled = !isUpdatingFloatingWindow
                    )
                }

                // NSFW警告提示开关
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "NSFW警告提示",
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = floatingWindowShowWarning,
                        onCheckedChange = { newValue ->
                            floatingWindowShowWarning = newValue
                            isUpdatingFloatingWindow = true
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    settingsRepository.setFloatingWindowShowWarning(newValue)
                                    LogUtils.i("DetectionSettings", "悬浮窗警告显示状态已更新: $newValue")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "NSFW警告${if (newValue) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    LogUtils.e("DetectionSettings", "更新悬浮窗警告设置失败", e)
                                } finally {
                                    isUpdatingFloatingWindow = false
                                }
                            }
                        },
                        enabled = !isUpdatingFloatingWindow
                    )
                }

                Text(
                    text = "说明：启用悬浮窗后，授权录屏权限时将自动请求悬浮窗权限。检测到NSFW内容时，悬浮窗会显示警告\"想想你该干什么！\"",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            HorizontalDivider( // 修复：使用 HorizontalDivider 替换 Divider
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // 后端兜底设置
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "后端兜底检测设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                // 启用后端检测开关
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "启用后端兜底检测",
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = backendEnabled,
                        onCheckedChange = { newValue ->
                            backendEnabled = newValue
                            isUpdatingBackend = true
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    settingsRepository.setBackendEnabled(newValue)
                                    LogUtils.i("DetectionSettings", "后端兜底检测启用状态已更新: $newValue")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "后端兜底检测${if (newValue) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    LogUtils.e("DetectionSettings", "更新后端启用状态失败", e)
                                } finally {
                                    isUpdatingBackend = false
                                }
                            }
                        },
                        enabled = !isUpdatingBackend
                    )
                }

                // 后端URL设置
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "后端服务URL",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    var backendUrlText by remember { mutableStateOf(backendUrl) }
                    // 当backendUrl变化时更新backendUrlText
                    LaunchedEffect(backendUrl) {
                        backendUrlText = backendUrl
                    }
                    OutlinedTextField(
                        value = backendUrlText,
                        onValueChange = { backendUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入后端服务URL") },
                        singleLine = true,
                        supportingText = {
                            Text("格式: http://IP:端口/api/nsfw/detect")
                        }
                    )
                    Button(
                        onClick = {
                            if (backendUrlText.isNotEmpty() && backendUrlText != backendUrl && !isUpdatingBackend) {
                                isUpdatingBackend = true
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        settingsRepository.setBackendUrl(backendUrlText)
                                        LogUtils.i("DetectionSettings", "后端URL已更新: $backendUrlText")
                                        backendUrl = backendUrlText
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "后端URL已更新", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        LogUtils.e("DetectionSettings", "更新后端URL失败", e)
                                    } finally {
                                        isUpdatingBackend = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = backendUrlText.isNotEmpty() && backendUrlText != backendUrl && !isUpdatingBackend
                    ) {
                        if (isUpdatingBackend) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("更新中...")
                        } else {
                            Text("更新后端URL")
                        }
                    }
                }

                // 保存调试图片开关
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "保存调试图片",
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = saveDebugImages,
                        onCheckedChange = { newValue ->
                            saveDebugImages = newValue
                            isUpdatingBackend = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    LogUtils.i("DetectionSettings", "开始保存调试图片设置: $newValue")
                                    settingsRepository.setSaveDebugImages(newValue)
                                    LogUtils.i("DetectionSettings", "调试图片保存状态已更新: $newValue")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "调试图片保存${if (newValue) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    LogUtils.e("DetectionSettings", "更新调试图片保存状态失败", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                        // 恢复之前的UI状态，因为保存失败
                                        saveDebugImages = !newValue
                                    }
                                } finally {
                                    isUpdatingBackend = false
                                }
                            }
                        },
                        enabled = !isUpdatingBackend
                    )
                }

                Text(
                    text = "说明：后端URL需指向运行nsfw-server的计算机IP地址。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            HorizontalDivider( // 修复：使用 HorizontalDivider 替换 Divider
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            Text(
                text = "注意：更改间隔后，前台服务将自动应用新间隔；阈值更改将在下次检测时生效；后端设置更改后立即生效",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
    }

    // 无效间隔弹窗已移除
}

/**
 * 日志管理界面
 */
@Composable
fun LogSection(
    onViewLogs: () -> Unit,
    onShareLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "📋 应用日志",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "查看和分享应用日志，便于调试问题",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onViewLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看日志")
                }

                Button(
                    onClick = onShareLogs,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("分享日志")
                }
            }
        }
    }
}

/**
 * 日志和历史记录管理部分
 */
@Composable
fun LogHistorySection(
    onViewLogs: () -> Unit,
    onViewHistory: () -> Unit,
    onShareLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "📋 记录与日志",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "查看检测历史、应用日志和分享数据",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onViewHistory,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看历史")
                }

                Button(
                    onClick = onViewLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看日志")
                }

                Button(
                    onClick = onShareLogs,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("分享日志")
                }
            }
        }
    }
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






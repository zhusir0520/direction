package com.example.direction

import android.app.Application
import android.util.Log
import com.example.direction.utils.LogUtils
import com.example.direction.utils.NotificationUtils

/**
 * 应用主Application类
 * 负责全局初始化和资源管理
 */
class DirectionApplication : Application() {

    companion object {
        private const val TAG = "DirectionApplication"

        // 全局Application实例
        lateinit var instance: DirectionApplication
            private set
    }

    // 全局组件实例（懒加载）
    val notificationUtils by lazy { NotificationUtils(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化日志工具
        LogUtils.initialize(this, minLevel = LogUtils.Level.DEBUG)

        LogUtils.i(TAG, "DirectionApplication创建")

        // WorkManager通过默认初始化器自动初始化（AndroidManifest中已启用）
        // 应用实现Configuration.Provider接口，WorkManager将自动使用提供的配置

        // 初始化通知渠道（Android 8.0+需要）
        initializeNotificationChannels()

        // 清理过期的权限记录（已移除）

        // 初始化WorkerManager（但不立即调度，等待用户交互）
        // workerManager.initializeAndSchedule()

        // 调试：自动启动后台检测（使用模拟截图）- 已禁用以减少卡顿
        // Log.i(TAG, "调试模式：自动启动后台检测")
        // startBackgroundWork()

        // 调试：立即执行一次检测（跳过时间检查）- 已禁用以减少卡顿
        // Log.i(TAG, "调试模式：调度立即检测任务")
        // workerManager.forceExecuteDetection()

        LogUtils.i(TAG, "DirectionApplication初始化完成")
    }

    override fun onTerminate() {
        LogUtils.i(TAG, "DirectionApplication终止")
        super.onTerminate()
    }

    override fun onLowMemory() {
        LogUtils.w(TAG, "系统内存不足")
        // 可以在这里释放非关键资源
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        LogUtils.i(TAG, "收到内存修剪通知，级别: $level")
        when (level) {
            // 应用在后台，内存不足
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                LogUtils.i(TAG, "应用在后台，释放资源")
                // 可以释放一些缓存资源
            }
            // 应用在前台，但系统希望我们释放内存
            TRIM_MEMORY_RUNNING_MODERATE,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                LogUtils.i(TAG, "应用在前台但内存紧张，释放非关键资源")
                // 可以释放一些非关键的缓存
            }
            // 应用在后台被杀死前
            TRIM_MEMORY_UI_HIDDEN -> {
                LogUtils.i(TAG, "应用UI已隐藏，可以释放UI资源")
            }
        }
        super.onTrimMemory(level)
    }

    /**
     * 初始化通知渠道（Android 8.0+必需）
     */
    private fun initializeNotificationChannels() {
        try {
            NotificationUtils.ensureNotificationChannels(this)
            LogUtils.i(TAG, "通知渠道初始化完成")
        } catch (e: Exception) {
            LogUtils.e(TAG, "初始化通知渠道失败", e)
        }
    }

    /**
     * 清理过期的权限记录（已移除）
     */
    private fun cleanupExpiredPermissions() {
        // 简化：不再清理权限记录
        LogUtils.i(TAG, "权限清理已禁用")
    }


    /**
     * 检查应用状态
     */
    fun getAppStatus(): String {
        return """
            应用状态:
            - MediaProjection权限: 简化版本（无持久化）
            - 通知权限: 简化版本
            - 检测模式: 前台服务控制（每分钟检测）
        """.trimIndent()
    }

}
package com.example.direction.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.direction.MainActivity
import com.example.direction.R
import com.example.direction.model.DetectionResult

/**
 * 通知工具类
 */
class NotificationUtils(private val context: Context) {

    companion object {
        // 通知渠道ID
        const val CHANNEL_ID_DETECTION = "detection_channel"
        const val CHANNEL_ID_SERVICE = "service_channel"
        const val CHANNEL_ID_ALERT = "alert_channel"

        // 通知ID
        const val NOTIFICATION_ID_DETECTION = 1001
        const val NOTIFICATION_ID_SERVICE = 1002
        const val NOTIFICATION_ID_ALERT = 1003

        // 通知渠道名称和描述
        private const val CHANNEL_NAME_DETECTION = "检测通知"
        private const val CHANNEL_DESC_DETECTION = "屏幕内容检测结果通知"

        private const val CHANNEL_NAME_SERVICE = "服务通知"
        private const val CHANNEL_DESC_SERVICE = "后台服务运行状态通知"

        private const val CHANNEL_NAME_ALERT = "警报通知"
        private const val CHANNEL_DESC_ALERT = "重要警报通知"

        /**
         * 确保通知渠道已创建（Android 8.0+）
         */
        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)

                // 创建检测通知渠道
                val detectionChannel = NotificationChannel(
                    CHANNEL_ID_DETECTION,
                    CHANNEL_NAME_DETECTION,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESC_DETECTION
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 250, 1000, 250, 1000)
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build())
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }

                // 创建服务通知渠道
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    CHANNEL_NAME_SERVICE,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESC_SERVICE
                    setShowBadge(false)
                }

                // 创建警报通知渠道
                val alertChannel = NotificationChannel(
                    CHANNEL_ID_ALERT,
                    CHANNEL_NAME_ALERT,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESC_ALERT
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 250, 1000, 250, 1000)
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }

                // 创建所有渠道
                notificationManager.createNotificationChannels(
                    listOf(detectionChannel, serviceChannel, alertChannel)
                )
            }
        }
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        // 确保通知渠道已创建
        ensureNotificationChannels(context)
    }

    /**
     * 发送检测结果通知
     */
    fun sendDetectionNotification(result: DetectionResult, enableVibration: Boolean = true) {
        val title = if (result.isNSFW) {
            "⚠️ 检测到不适宜内容"
        } else {
            "✅ 屏幕内容安全"
        }

        val confidencePercent = "%.1f".format(result.confidence * 100)
        val message = if (result.isNSFW) {
            "置信度: ${confidencePercent}% (NSFW: ${"%.1f".format(result.nsfwScore * 100)}%, " +
                    "SFW: ${"%.1f".format(result.sfwScore * 100)}%)"
        } else {
            "屏幕内容安全，置信度: ${confidencePercent}%"
        }

        // 创建点击通知后打开应用的主意图
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DETECTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 使用系统图标，稍后替换
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (result.isNSFW) {
                    // 对于NSFW内容，添加更多警报特征
                    if (enableVibration) {
                        setVibrate(longArrayOf(0, 1000, 250, 1000, 250, 1000))
                    }
                    setLights(android.graphics.Color.RED, 1000, 1000)
                    setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                    setCategory(NotificationCompat.CATEGORY_ALARM)
                }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID_DETECTION, notification)
    }

    /**
     * 发送服务运行通知
     */
    fun sendServiceNotification(isRunning: Boolean, remainingTime: String = "") {
        val title = if (isRunning) {
            "🔍 屏幕内容检测运行中"
        } else {
            "⏸️ 屏幕内容检测已暂停"
        }

        val message = if (isRunning) {
            if (remainingTime.isNotEmpty()) {
                "检测将在 $remainingTime 后暂停"
            } else {
                "22:00-02:00期间每1分钟检测一次"
            }
        } else {
            "当前不在检测时间段（22:00-02:00）"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isRunning) // 运行中时持续显示
            .setAutoCancel(!isRunning) // 暂停时可取消
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
    }

    /**
     * 发送警报通知（用于重要事件）
     */
    fun sendAlertNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("🚨 $title")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 1000, 250, 1000, 250, 1000))
            .setLights(android.graphics.Color.RED, 1000, 1000)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    /**
     * 发送权限请求通知
     */
    fun sendPermissionNotification() {
        val title = "📱 需要屏幕截图权限"
        val message = "点击授权以启用屏幕内容检测功能"

        // 创建打开权限请求活动的意图
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("action", "request_permission")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DETECTION)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .build()

        notificationManager.notify(NOTIFICATION_ID_DETECTION, notification)
    }

    /**
     * 取消特定通知
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * 取消所有通知
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    /**
     * 检查通知权限是否已授予（Android 13+）
     */
    fun areNotificationsEnabled(): Boolean {
        // 首先检查应用级别的通知权限（Android 13+）或系统开关（Android 13以下）
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notificationsEnabled) {
            return false
        }

        // 对于Android 8.0+，还需要检查通知渠道是否未被禁用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID_DETECTION)
            // 如果渠道重要性为NONE，表示用户禁用了该渠道
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }

        return true
    }

    /**
     * 获取通知设置意图（跳转到应用通知设置）
     */
    fun getNotificationSettingsIntent(): Intent {
        return Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                    action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra("app_package", context.packageName)
                    putExtra("app_uid", context.applicationInfo.uid)
                }
                else -> {
                    action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
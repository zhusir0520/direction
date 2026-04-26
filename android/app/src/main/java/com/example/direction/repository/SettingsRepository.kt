package com.example.direction.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.direction.model.TimeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设置仓库
 * 使用DataStore管理应用设置
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

        // 偏好设置键
        private val TIME_WINDOW_START_HOUR = intPreferencesKey("time_window_start_hour")
        private val TIME_WINDOW_END_HOUR = intPreferencesKey("time_window_end_hour")
        private val TIME_WINDOW_ENABLED = booleanPreferencesKey("time_window_enabled")

        private val DETECTION_ENABLED = booleanPreferencesKey("detection_enabled")
        private val DETECTION_INTERVAL_MINUTES = intPreferencesKey("detection_interval_minutes")
        private val NSFW_THRESHOLD = intPreferencesKey("nsfw_threshold") // 存储阈值 * 100

        private val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")

        private val LAST_DETECTION_TIME = longPreferencesKey("last_detection_time")
        private val TOTAL_DETECTIONS = intPreferencesKey("total_detections")
        private val NSFW_DETECTIONS = intPreferencesKey("nsfw_detections")

        // 悬浮窗设置
        private val FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
        private val FLOATING_WINDOW_SHOW_WARNING = booleanPreferencesKey("floating_window_show_warning")
        private val FLOATING_WINDOW_POSITION_X = intPreferencesKey("floating_window_position_x")
        private val FLOATING_WINDOW_POSITION_Y = intPreferencesKey("floating_window_position_y")

        // 后端兜底设置
        private val BACKEND_ENABLED = booleanPreferencesKey("backend_enabled")
        private val BACKEND_URL = stringPreferencesKey("backend_url")
        private val BACKEND_FALLBACK_THRESHOLD_MARGIN = intPreferencesKey("backend_fallback_threshold_margin") // 存储阈值 * 100
        private val SAVE_DEBUG_IMAGES = booleanPreferencesKey("save_debug_images")

        // NSFW检测时回到主页
        private val BRING_TO_FOREGROUND = booleanPreferencesKey("bring_to_foreground")

        // Shizuku 设置
        private val SHIZUKU_KILL_ENABLED = booleanPreferencesKey("shizuku_kill_enabled")

        // 默认值
        private const val DEFAULT_START_HOUR = 22
        private const val DEFAULT_END_HOUR = 2
        private const val DEFAULT_DETECTION_INTERVAL = 1 // 前台服务最小间隔1分钟
        private const val DEFAULT_NSFW_THRESHOLD = 85 // 0.85 * 100
        private const val DEFAULT_FLOATING_WINDOW_ENABLED = false
        private const val DEFAULT_FLOATING_WINDOW_SHOW_WARNING = true
        private const val DEFAULT_FLOATING_WINDOW_POSITION_X = 100
        private const val DEFAULT_FLOATING_WINDOW_POSITION_Y = 300

        // 后端兜底默认值
        private const val DEFAULT_BACKEND_ENABLED = true
        private const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8082/api/nsfw/detect" // 模拟器连接本地服务（端口8082）
        private const val DEFAULT_BACKEND_FALLBACK_THRESHOLD_MARGIN = 5 // 0.05 * 100
        private const val DEFAULT_SAVE_DEBUG_IMAGES = true
        private const val DEFAULT_BRING_TO_FOREGROUND = false

        // Shizuku 默认值
        private const val DEFAULT_SHIZUKU_KILL_ENABLED = true

    }

    /**
     * 获取时间窗口设置
     */
    val timeWindow: Flow<TimeWindow> = context.dataStore.data
        .map { preferences ->
            TimeWindow(
                startHour = preferences[TIME_WINDOW_START_HOUR] ?: DEFAULT_START_HOUR,
                endHour = preferences[TIME_WINDOW_END_HOUR] ?: DEFAULT_END_HOUR,
                enabled = preferences[TIME_WINDOW_ENABLED] ?: true
            )
        }

    /**
     * 获取时间窗口设置（一次性）
     */
    suspend fun getTimeWindow(): kotlinx.coroutines.flow.Flow<TimeWindow> {
        return context.dataStore.data
            .map { preferences ->
                TimeWindow(
                    startHour = preferences[TIME_WINDOW_START_HOUR] ?: DEFAULT_START_HOUR,
                    endHour = preferences[TIME_WINDOW_END_HOUR] ?: DEFAULT_END_HOUR,
                    enabled = preferences[TIME_WINDOW_ENABLED] ?: true
                )
            }
    }

    /**
     * 保存时间窗口设置
     */
    suspend fun saveTimeWindow(timeWindow: TimeWindow) {
        context.dataStore.edit { preferences ->
            preferences[TIME_WINDOW_START_HOUR] = timeWindow.startHour
            preferences[TIME_WINDOW_END_HOUR] = timeWindow.endHour
            preferences[TIME_WINDOW_ENABLED] = timeWindow.enabled
        }
    }

    /**
     * 获取检测是否启用
     */
    val isDetectionEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DETECTION_ENABLED] ?: true
        }

    /**
     * 设置检测启用状态
     */
    suspend fun setDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DETECTION_ENABLED] = enabled
        }
    }

    /**
     * 获取检测间隔（分钟）
     */
    val detectionInterval: Flow<Int> = context.dataStore.data
        .map { preferences ->
            val value = preferences[DETECTION_INTERVAL_MINUTES] ?: DEFAULT_DETECTION_INTERVAL
            Log.i("SettingsRepository", "读取检测间隔: ${value}分钟")
            value
        }

    /**
     * 设置检测间隔
     */
    suspend fun setDetectionInterval(minutes: Int) {
        require(minutes in 1..1440) { "检测间隔必须在1-1440分钟之间" }
        Log.i("SettingsRepository", "设置检测间隔: ${minutes}分钟")
        context.dataStore.edit { preferences ->
            preferences[DETECTION_INTERVAL_MINUTES] = minutes
        }
    }

    /**
     * 获取NSFW阈值
     */
    val nsfwThreshold: Flow<Float> = context.dataStore.data
        .map { preferences ->
            val intValue = preferences[NSFW_THRESHOLD] ?: DEFAULT_NSFW_THRESHOLD
            intValue / 100.0f
        }

    /**
     * 设置NSFW阈值
     */
    suspend fun setNsfwThreshold(threshold: Float) {
        require(threshold in 0.0f..1.0f) { "阈值必须在0.0-1.0之间" }
        context.dataStore.edit { preferences ->
            preferences[NSFW_THRESHOLD] = (threshold * 100).toInt()
        }
    }

    /**
     * 获取通知设置
     */
    val notificationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[NOTIFICATION_ENABLED] ?: true
        }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[VIBRATION_ENABLED] ?: true
        }

    val soundEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SOUND_ENABLED] ?: true
        }

    /**
     * 设置通知选项
     */
    suspend fun setNotificationSettings(
        enabled: Boolean,
        vibration: Boolean = true,
        sound: Boolean = true
    ) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_ENABLED] = enabled
            preferences[VIBRATION_ENABLED] = vibration
            preferences[SOUND_ENABLED] = sound
        }
    }

    /**
     * 更新检测统计
     */
    suspend fun updateDetectionStats(isNSFW: Boolean) {
        context.dataStore.edit { preferences ->
            // 更新最后检测时间
            preferences[LAST_DETECTION_TIME] = System.currentTimeMillis()

            // 更新总检测次数
            val total = preferences[TOTAL_DETECTIONS] ?: 0
            preferences[TOTAL_DETECTIONS] = total + 1

            // 如果是NSFW，更新NSFW检测次数
            if (isNSFW) {
                val nsfwCount = preferences[NSFW_DETECTIONS] ?: 0
                preferences[NSFW_DETECTIONS] = nsfwCount + 1
            }
        }
    }

    /**
     * 获取检测统计
     */
    suspend fun getDetectionStats(): kotlinx.coroutines.flow.Flow<DetectionStats> {
        return context.dataStore.data
            .map { preferences ->
                DetectionStats(
                    lastDetectionTime = preferences[LAST_DETECTION_TIME] ?: 0L,
                    totalDetections = preferences[TOTAL_DETECTIONS] ?: 0,
                    nsfwDetections = preferences[NSFW_DETECTIONS] ?: 0
                )
            }
    }

    /**
     * 重置检测统计
     */
    suspend fun resetDetectionStats() {
        context.dataStore.edit { preferences ->
            preferences.remove(TOTAL_DETECTIONS)
            preferences.remove(NSFW_DETECTIONS)
            preferences.remove(LAST_DETECTION_TIME)
        }
    }

    /**
     * 悬浮窗设置
     */
    val floatingWindowEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[FLOATING_WINDOW_ENABLED] ?: DEFAULT_FLOATING_WINDOW_ENABLED
        }

    val floatingWindowShowWarning: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[FLOATING_WINDOW_SHOW_WARNING] ?: DEFAULT_FLOATING_WINDOW_SHOW_WARNING
        }

    val floatingWindowPosition: Flow<Pair<Int, Int>> = context.dataStore.data
        .map { preferences ->
            val x = preferences[FLOATING_WINDOW_POSITION_X] ?: DEFAULT_FLOATING_WINDOW_POSITION_X
            val y = preferences[FLOATING_WINDOW_POSITION_Y] ?: DEFAULT_FLOATING_WINDOW_POSITION_Y
            Pair(x, y)
        }

    /**
     * 设置悬浮窗启用状态
     */
    suspend fun setFloatingWindowEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_WINDOW_ENABLED] = enabled
        }
    }

    /**
     * 设置悬浮窗警告显示状态
     */
    suspend fun setFloatingWindowShowWarning(showWarning: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_WINDOW_SHOW_WARNING] = showWarning
        }
    }

    /**
     * 设置悬浮窗位置
     */
    suspend fun setFloatingWindowPosition(x: Int, y: Int) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_WINDOW_POSITION_X] = x
            preferences[FLOATING_WINDOW_POSITION_Y] = y
        }
    }

    /**
     * 后端兜底设置
     */
    val backendEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BACKEND_ENABLED] ?: DEFAULT_BACKEND_ENABLED
        }

    val backendUrl: Flow<String> = context.dataStore.data
        .map { preferences ->
            val url = preferences[BACKEND_URL] ?: DEFAULT_BACKEND_URL
            Log.i("SettingsRepository", "backendUrl Flow发出: $url (偏好值: ${preferences[BACKEND_URL]}, 默认值: $DEFAULT_BACKEND_URL)")
            url
        }

    val backendFallbackThresholdMargin: Flow<Float> = context.dataStore.data
        .map { preferences ->
            val intValue = preferences[BACKEND_FALLBACK_THRESHOLD_MARGIN] ?: DEFAULT_BACKEND_FALLBACK_THRESHOLD_MARGIN
            intValue / 100.0f
        }

    val saveDebugImages: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            val value = preferences[SAVE_DEBUG_IMAGES] ?: DEFAULT_SAVE_DEBUG_IMAGES
            Log.i("SettingsRepository", "saveDebugImages Flow读取: $value (偏好值: ${preferences[SAVE_DEBUG_IMAGES]}, 默认值: $DEFAULT_SAVE_DEBUG_IMAGES)")
            value
        }

    /**
     * NSFW检测时回到主页
     */
    val bringToForeground: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BRING_TO_FOREGROUND] ?: DEFAULT_BRING_TO_FOREGROUND
        }

    /**
     * Shizuku 应用强制停止设置
     */
    val shizukuKillEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHIZUKU_KILL_ENABLED] ?: DEFAULT_SHIZUKU_KILL_ENABLED
        }

    /**
     * 设置后端启用状态
     */
    suspend fun setBackendEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKEND_ENABLED] = enabled
        }
    }

    /**
     * 设置后端URL
     */
    suspend fun setBackendUrl(url: String) {
        Log.i("SettingsRepository", "保存后端URL: $url")
        context.dataStore.edit { preferences ->
            preferences[BACKEND_URL] = url
        }
    }

    /**
     * 设置兜底阈值容差
     */
    suspend fun setBackendFallbackThresholdMargin(margin: Float) {
        require(margin in 0.0f..1.0f) { "容差必须在0.0-1.0之间" }
        context.dataStore.edit { preferences ->
            preferences[BACKEND_FALLBACK_THRESHOLD_MARGIN] = (margin * 100).toInt()
        }
    }

    /**
     * 设置是否保存调试图片
     */
    suspend fun setSaveDebugImages(enabled: Boolean) {
        Log.i("SettingsRepository", "设置saveDebugImages: $enabled")
        context.dataStore.edit { preferences ->
            preferences[SAVE_DEBUG_IMAGES] = enabled
        }
        Log.i("SettingsRepository", "saveDebugImages已保存: $enabled")
    }

    /**
     * 设置NSFW检测时是否回到主页
     */
    suspend fun setBringToForeground(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BRING_TO_FOREGROUND] = enabled
        }
    }

    /**
     * 设置Shizuku强制停止应用功能
     */
    suspend fun setShizukuKillEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHIZUKU_KILL_ENABLED] = enabled
        }
    }

    /**
     * 获取所有设置（用于备份或导出）
     */
    suspend fun getAllSettings(): kotlinx.coroutines.flow.Flow<Map<String, Any>> {
        return context.dataStore.data
            .map { preferences ->
                val map = mutableMapOf<String, Any>()

                preferences.asMap().forEach { (key, value) ->
                    map[key.name] = value
                }

                // 添加默认值
                map.putIfAbsent(TIME_WINDOW_START_HOUR.name, DEFAULT_START_HOUR)
                map.putIfAbsent(TIME_WINDOW_END_HOUR.name, DEFAULT_END_HOUR)
                map.putIfAbsent(TIME_WINDOW_ENABLED.name, true)
                map.putIfAbsent(DETECTION_ENABLED.name, true)
                map.putIfAbsent(DETECTION_INTERVAL_MINUTES.name, DEFAULT_DETECTION_INTERVAL)
                map.putIfAbsent(NSFW_THRESHOLD.name, DEFAULT_NSFW_THRESHOLD)
                map.putIfAbsent(NOTIFICATION_ENABLED.name, true)
                map.putIfAbsent(VIBRATION_ENABLED.name, true)
                map.putIfAbsent(SOUND_ENABLED.name, true)
                map.putIfAbsent(FLOATING_WINDOW_ENABLED.name, DEFAULT_FLOATING_WINDOW_ENABLED)
                map.putIfAbsent(FLOATING_WINDOW_SHOW_WARNING.name, DEFAULT_FLOATING_WINDOW_SHOW_WARNING)
                map.putIfAbsent(FLOATING_WINDOW_POSITION_X.name, DEFAULT_FLOATING_WINDOW_POSITION_X)
                map.putIfAbsent(FLOATING_WINDOW_POSITION_Y.name, DEFAULT_FLOATING_WINDOW_POSITION_Y)
                map.putIfAbsent(BACKEND_ENABLED.name, DEFAULT_BACKEND_ENABLED)
                map.putIfAbsent(BACKEND_URL.name, DEFAULT_BACKEND_URL)
                map.putIfAbsent(BACKEND_FALLBACK_THRESHOLD_MARGIN.name, DEFAULT_BACKEND_FALLBACK_THRESHOLD_MARGIN)
                map.putIfAbsent(BRING_TO_FOREGROUND.name, DEFAULT_BRING_TO_FOREGROUND)
                map.putIfAbsent(SHIZUKU_KILL_ENABLED.name, DEFAULT_SHIZUKU_KILL_ENABLED)

                map
            }
    }

    /**
     * 清除所有设置
     */
    suspend fun clearAllSettings() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * 检测统计数据类
     */
    data class DetectionStats(
        val lastDetectionTime: Long,
        val totalDetections: Int,
        val nsfwDetections: Int
    ) {
        val safeDetections: Int get() = totalDetections - nsfwDetections
        val nsfwPercentage: Float get() = if (totalDetections > 0) {
            nsfwDetections.toFloat() / totalDetections * 100
        } else {
            0f
        }
    }
}
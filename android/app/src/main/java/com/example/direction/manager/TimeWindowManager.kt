package com.example.direction.manager

import android.content.Context
import android.util.Log
import com.example.direction.model.TimeWindow
import java.util.Calendar

/**
 * 时间窗口管理器
 * 负责管理22:00-02:00时间窗口的逻辑
 */
class TimeWindowManager(private val context: Context) {

    // 默认时间窗口：22:00 - 02:00（跨天）
    private val defaultTimeWindow = TimeWindow(startHour = 22, endHour = 2, enabled = true)

    /**
     * 检查当前是否在检测时间窗口内
     * 修改：只要时间窗口启用，就始终返回true（全天运行）
     */
    fun isInDetectionWindow(timeWindow: TimeWindow = defaultTimeWindow): Boolean {
        if (!timeWindow.enabled) {
            Log.i("TimeWindowManager", "时间窗口检查: 时间窗口未启用")
            return false
        }

        // 修改：只要时间窗口启用，就始终返回true（全天运行）
        Log.i("TimeWindowManager", "时间窗口检查: 返回true（全天运行模式）")
        return true
    }

    /**
     * 计算距离下一个时间窗口开始的延迟（毫秒）
     * 修改：总是返回0（全天运行）
     */
    fun calculateDelayToNextWindow(timeWindow: TimeWindow = defaultTimeWindow): Long {
        Log.i("TimeWindowManager", "calculateDelayToNextWindow: 返回0（全天运行模式）")
        return 0L
    }

    /**
     * 获取时间窗口剩余时间（毫秒）
     * 修改：总是返回24小时（全天运行）
     */
    fun getRemainingTimeInWindow(timeWindow: TimeWindow = defaultTimeWindow): Long {
        if (!isInDetectionWindow(timeWindow)) return 0L

        Log.i("TimeWindowManager", "getRemainingTimeInWindow: 返回24小时（全天运行模式）")
        return 24 * 60 * 60 * 1000L // 24小时
    }

    /**
     * 获取时间窗口描述
     */
    fun getTimeWindowDescription(timeWindow: TimeWindow = defaultTimeWindow): String {
        return timeWindow.description
    }

    /**
     * 检查是否应该请求权限（只要时间窗口启用且权限未授予）
     * 修改：移除时间窗口内检查（全天运行）
     */
    fun shouldRequestPermission(
        timeWindow: TimeWindow = defaultTimeWindow,
        hasMediaProjectionPermission: Boolean
    ): Boolean {
        val shouldRequest = timeWindow.enabled && !hasMediaProjectionPermission
        Log.i("TimeWindowManager", "shouldRequestPermission: 返回$shouldRequest（全天运行模式）")
        return shouldRequest
    }

    companion object {
        // 静态方法：快速检查当前是否在默认时间窗口内
        // 修改：总是返回true（全天运行）
        fun isInDefaultDetectionWindow(): Boolean {
            Log.i("TimeWindowManager", "isInDefaultDetectionWindow: 返回true（全天运行模式）")
            return true
        }

        // 静态方法：获取当前小时
        fun getCurrentHour(): Int {
            val calendar = Calendar.getInstance()
            return calendar.get(Calendar.HOUR_OF_DAY)
        }

        // 静态方法：获取当前时间描述
        fun getCurrentTimeDescription(): String {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            return String.format("%02d:%02d", hour, minute)
        }
    }
}
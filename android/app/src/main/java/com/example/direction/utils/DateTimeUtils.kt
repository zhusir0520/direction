package com.example.direction.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日期时间工具类
 */
object DateTimeUtils {

    private val defaultLocale = Locale.getDefault()

    /**
     * 格式化时间戳为可读字符串
     */
    fun formatTimestamp(timestamp: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val date = Date(timestamp)
        val formatter = SimpleDateFormat(pattern, defaultLocale)
        return formatter.format(date)
    }

    /**
     * 格式化时间间隔为可读字符串
     */
    fun formatDuration(milliseconds: Long): String {
        return when {
            milliseconds < 1000 -> "${milliseconds}ms"
            milliseconds < 60 * 1000 -> {
                val seconds = milliseconds / 1000
                val remainingMs = milliseconds % 1000
                if (remainingMs > 0) {
                    "${seconds}.${remainingMs}s"
                } else {
                    "${seconds}s"
                }
            }
            milliseconds < 60 * 60 * 1000 -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                        TimeUnit.MINUTES.toSeconds(minutes)
                "${minutes}m ${seconds}s"
            }
            else -> {
                val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                        TimeUnit.HOURS.toMinutes(hours)
                "${hours}h ${minutes}m"
            }
        }
    }

    /**
     * 获取当前时间戳
     */
    fun currentTimestamp(): Long = System.currentTimeMillis()

    /**
     * 获取当前时间描述
     */
    fun currentTimeDescription(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        return String.format("%02d:%02d:%02d", hour, minute, second)
    }

    /**
     * 获取当前日期描述
     */
    fun currentDateDescription(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1 // 月份从0开始
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }

    /**
     * 获取当前星期几
     */
    fun currentDayOfWeek(): String {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        return when (dayOfWeek) {
            Calendar.SUNDAY -> "星期日"
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> "未知"
        }
    }

    /**
     * 检查两个时间戳是否在同一天
     */
    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }

        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 获取今天的开始时间（00:00:00）
     */
    fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /**
     * 获取今天的结束时间（23:59:59.999）
     */
    fun getEndOfDay(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return calendar.timeInMillis
    }

    /**
     * 计算距离指定小时还有多少毫秒
     */
    fun calculateDelayToHour(targetHour: Int): Long {
        require(targetHour in 0..23) { "目标小时必须在0-23之间" }

        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)

        val targetCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果目标时间已经过去，则设置到第二天
        if (currentHour >= targetHour) {
            targetCalendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return targetCalendar.timeInMillis - calendar.timeInMillis
    }

    /**
     * 将小时和分钟转换为时间描述
     */
    fun formatHourMinute(hour: Int, minute: Int = 0): String {
        require(hour in 0..23) { "小时必须在0-23之间" }
        require(minute in 0..59) { "分钟必须在0-59之间" }

        return String.format("%02d:%02d", hour, minute)
    }

    /**
     * 将毫秒转换为天、小时、分钟、秒
     */
    fun breakDownMilliseconds(milliseconds: Long): Breakdown {
        val days = TimeUnit.MILLISECONDS.toDays(milliseconds)
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds) - TimeUnit.DAYS.toHours(days)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.DAYS.toMinutes(days) -
                TimeUnit.HOURS.toMinutes(hours)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.DAYS.toSeconds(days) -
                TimeUnit.HOURS.toSeconds(hours) -
                TimeUnit.MINUTES.toSeconds(minutes)

        return Breakdown(days, hours, minutes, seconds)
    }

    /**
     * 时间分解数据类
     */
    data class Breakdown(
        val days: Long,
        val hours: Long,
        val minutes: Long,
        val seconds: Long
    ) {
        fun toDescription(): String {
            val parts = mutableListOf<String>()
            if (days > 0) parts.add("${days}天")
            if (hours > 0) parts.add("${hours}小时")
            if (minutes > 0) parts.add("${minutes}分钟")
            if (seconds > 0) parts.add("${seconds}秒")
            return parts.joinToString(" ") { it }.ifEmpty { "0秒" }
        }
    }
}
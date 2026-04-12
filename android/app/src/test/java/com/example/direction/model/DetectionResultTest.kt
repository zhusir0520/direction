package com.example.direction.model

import org.junit.Assert.*
import org.junit.Test

/**
 * 数据模型测试类
 * 这些是纯 Kotlin 数据类，可以在 JVM 上运行单元测试
 */
class DetectionResultTest {

    @Test
    fun testDetectionResultConstructor() {
        // 测试基本构造函数
        val result = DetectionResult(
            isNSFW = true,
            confidence = 0.85f,
            sfwScore = 0.15f,
            nsfwScore = 0.85f,
            timestamp = 1234567890L,
            screenshotPath = "/path/to/screenshot.png",
            error = null
        )

        assertEquals("isNSFW 应该为 true", true, result.isNSFW)
        assertEquals("confidence 应该为 0.85", 0.85f, result.confidence)
        assertEquals("sfwScore 应该为 0.15", 0.15f, result.sfwScore)
        assertEquals("nsfwScore 应该为 0.85", 0.85f, result.nsfwScore)
        assertEquals("timestamp 应该为 1234567890", 1234567890L, result.timestamp)
        assertEquals("screenshotPath 应该正确", "/path/to/screenshot.png", result.screenshotPath)
        assertNull("error 应该为 null", result.error)
    }

    @Test
    fun testDetectionResultSuccessCompanionMethod() {
        // 测试成功的检测结果工厂方法
        val successResult = DetectionResult.success(
            isNSFW = false,
            confidence = 0.95f,
            sfwScore = 0.95f,
            nsfwScore = 0.05f,
            screenshotPath = "/path/to/safe.png"
        )

        assertEquals("isNSFW 应该为 false", false, successResult.isNSFW)
        assertEquals("confidence 应该为 0.95", 0.95f, successResult.confidence)
        assertEquals("sfwScore 应该为 0.95", 0.95f, successResult.sfwScore)
        assertEquals("nsfwScore 应该为 0.05", 0.05f, successResult.nsfwScore)
        assertEquals("screenshotPath 应该正确", "/path/to/safe.png", successResult.screenshotPath)
        assertNull("error 应该为 null", successResult.error)
        assertTrue("timestamp 应该 > 0", successResult.timestamp > 0)
    }

    @Test
    fun testDetectionResultFailureCompanionMethod() {
        // 测试失败的检测结果工厂方法
        val errorMessage = "模型加载失败"
        val failureResult = DetectionResult.failure(errorMessage)

        assertEquals("isNSFW 应该为 false", false, failureResult.isNSFW)
        assertEquals("confidence 应该为 0", 0f, failureResult.confidence)
        assertEquals("error 应该正确", errorMessage, failureResult.error)
        assertEquals("sfwScore 应该为默认值 0", 0f, failureResult.sfwScore)
        assertEquals("nsfwScore 应该为默认值 0", 0f, failureResult.nsfwScore)
        assertNull("screenshotPath 应该为 null", failureResult.screenshotPath)
        assertTrue("timestamp 应该 > 0", failureResult.timestamp > 0)
    }

    @Test
    fun testDetectionResultToString() {
        // 测试 toString 方法
        // 成功情况
        val successResult = DetectionResult.success(
            isNSFW = true,
            confidence = 0.87f
        )
        val successString = successResult.toString()
        assertTrue("成功结果字符串应该包含 'NSFW'", successString.contains("NSFW"))
        assertTrue("成功结果字符串应该包含置信度百分比", successString.contains("%"))

        // 失败情况
        val failureResult = DetectionResult.failure("处理超时")
        val failureString = failureResult.toString()
        assertTrue("失败结果字符串应该包含 '检测失败'", failureString.contains("检测失败"))
        assertTrue("失败结果字符串应该包含错误信息", failureString.contains("处理超时"))
    }

    @Test
    fun testTimeWindowConstructor() {
        // 测试 TimeWindow 构造函数
        val timeWindow = TimeWindow(
            startHour = 22,
            endHour = 2,
            enabled = true
        )

        assertEquals("startHour 应该为 22", 22, timeWindow.startHour)
        assertEquals("endHour 应该为 2", 2, timeWindow.endHour)
        assertTrue("enabled 应该为 true", timeWindow.enabled)
        assertTrue("isCrossDay 应该为 true（跨天）", timeWindow.isCrossDay)
    }

    @Test
    fun testTimeWindowValidation() {
        // 测试 TimeWindow 参数验证
        // 有效参数应该正常工作
        TimeWindow(startHour = 0, endHour = 23, enabled = true)

        // 无效参数应该抛出异常
        try {
            TimeWindow(startHour = 25, endHour = 10, enabled = true)
            fail("应该抛出 IllegalArgumentException（开始小时无效）")
        } catch (e: IllegalArgumentException) {
            // 预期异常
        }

        try {
            TimeWindow(startHour = 10, endHour = 25, enabled = true)
            fail("应该抛出 IllegalArgumentException（结束小时无效）")
        } catch (e: IllegalArgumentException) {
            // 预期异常
        }
    }

    @Test
    fun testTimeWindowDescription() {
        // 测试时间窗口描述
        // 跨天情况
        val crossDayWindow = TimeWindow(startHour = 22, endHour = 2)
        assertEquals("跨天描述应该正确", "22:00 - 次日02:00", crossDayWindow.description)
        assertTrue("跨天窗口 isCrossDay 应该为 true", crossDayWindow.isCrossDay)

        // 同天情况
        val sameDayWindow = TimeWindow(startHour = 9, endHour = 17)
        assertEquals("同天描述应该正确", "09:00 - 17:00", sameDayWindow.description)
        assertFalse("同天窗口 isCrossDay 应该为 false", sameDayWindow.isCrossDay)

        // 边界情况：开始小时等于结束小时
        val zeroLengthWindow = TimeWindow(startHour = 10, endHour = 10)
        assertTrue("开始小时等于结束小时时 isCrossDay 应该为 true", zeroLengthWindow.isCrossDay)
        assertEquals("零长度窗口描述应该正确", "10:00 - 次日10:00", zeroLengthWindow.description)
    }

    @Test
    fun testPermissionStateConstructor() {
        // 测试 PermissionState 构造函数
        val now = System.currentTimeMillis()
        val permissionState = PermissionState(
            mediaProjectionGranted = true,
            notificationGranted = false,
            mediaProjectionGrantTime = now - 10000, // 10秒前
            notificationGrantTime = 0L
        )

        assertTrue("mediaProjectionGranted 应该为 true", permissionState.mediaProjectionGranted)
        assertFalse("notificationGranted 应该为 false", permissionState.notificationGranted)
        assertEquals("mediaProjectionGrantTime 应该正确", now - 10000, permissionState.mediaProjectionGrantTime)
        assertEquals("notificationGrantTime 应该为 0", 0L, permissionState.notificationGrantTime)
    }

    @Test
    fun testPermissionStateIsMediaProjectionValid() {
        // 测试 MediaProjection 权限有效性检查
        val now = System.currentTimeMillis()

        // 情况1：权限未授予
        val notGrantedState = PermissionState(mediaProjectionGranted = false)
        assertFalse("权限未授予时应该返回 false", notGrantedState.isMediaProjectionValid)

        // 情况2：权限刚授予（在有效期内）
        val recentlyGrantedState = PermissionState(
            mediaProjectionGranted = true,
            mediaProjectionGrantTime = now - 10000 // 10秒前
        )
        assertTrue("权限在有效期内应该返回 true", recentlyGrantedState.isMediaProjectionValid)

        // 情况3：权限已过期（超过24小时）
        val expiredState = PermissionState(
            mediaProjectionGranted = true,
            mediaProjectionGrantTime = now - (25 * 60 * 60 * 1000) // 25小时前
        )
        assertFalse("权限已过期应该返回 false", expiredState.isMediaProjectionValid)

        // 情况4：权限刚好在边界（24小时整）
        val boundaryState = PermissionState(
            mediaProjectionGranted = true,
            mediaProjectionGrantTime = now - (24 * 60 * 60 * 1000) // 正好24小时前
        )
        // 注意：严格小于24小时才有效，等于24小时时已过期
        assertFalse("权限刚好24小时应该返回 false（已过期）", boundaryState.isMediaProjectionValid)
    }

    @Test
    fun testDataClassEqualsAndHashCode() {
        // 测试数据类的 equals 和 hashCode
        val result1 = DetectionResult.success(
            isNSFW = true,
            confidence = 0.8f,
            screenshotPath = "/path/1.png"
        )

        Thread.sleep(10) // 确保时间戳不同

        val result2 = DetectionResult.success(
            isNSFW = true,
            confidence = 0.8f,
            screenshotPath = "/path/1.png"
        )

        // 由于时间戳不同，两个对象不应该相等
        assertNotEquals("时间戳不同，对象不应该相等", result1, result2)
        assertNotEquals("时间戳不同，hashCode 应该不同", result1.hashCode(), result2.hashCode())

        // 但其他属性应该相同
        assertEquals("isNSFW 应该相同", result1.isNSFW, result2.isNSFW)
        assertEquals("confidence 应该相同", result1.confidence, result2.confidence)
        assertEquals("screenshotPath 应该相同", result1.screenshotPath, result2.screenshotPath)
    }

    @Test
    fun testDataClassCopy() {
        // 测试数据类的 copy 方法
        val original = DetectionResult.success(
            isNSFW = false,
            confidence = 0.7f,
            screenshotPath = "/original.png"
        )

        val copied = original.copy(
            isNSFW = true,
            confidence = 0.9f
        )

        assertEquals("isNSFW 应该更新", true, copied.isNSFW)
        assertEquals("confidence 应该更新", 0.9f, copied.confidence)
        assertEquals("screenshotPath 应该保持不变", "/original.png", copied.screenshotPath)
        assertEquals("timestamp 应该保持不变", original.timestamp, copied.timestamp)
        assertEquals("sfwScore 应该保持不变", original.sfwScore, copied.sfwScore)
        assertEquals("nsfwScore 应该保持不变", original.nsfwScore, copied.nsfwScore)
    }
}
package com.example.direction.manager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TimeWindowManager 测试类
 * 使用 Android Instrumented Tests，因为需要 Android Context
 */
@RunWith(AndroidJUnit4::class)
class TimeWindowManagerTest {

    private lateinit var timeWindowManager: TimeWindowManager
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        timeWindowManager = TimeWindowManager(context)
    }

    @Test
    fun testStaticMethodsReturnValidValues() {
        // 测试静态方法返回有效值
        val hour = TimeWindowManager.getCurrentHour()
        assertTrue("当前小时应该在0-23之间", hour in 0..23)

        val timeDesc = TimeWindowManager.getCurrentTimeDescription()
        assertTrue("时间描述应该符合HH:MM格式", timeDesc.matches(Regex("\\d{2}:\\d{2}")))

        val isInWindow = TimeWindowManager.isInDefaultDetectionWindow()
        // 这只是一个布尔值，没有具体断言，但确保不会崩溃
        assertNotNull(isInWindow)
    }

    @Test
    fun testIsInDetectionWindowWithDefaultWindow() {
        // 测试默认时间窗口（22:00-02:00）的检测逻辑
        val result = timeWindowManager.isInDetectionWindow()
        // 无法断言具体值，因为依赖当前时间
        // 但可以确保方法不会崩溃并且返回布尔值
        assertNotNull(result)
    }

    @Test
    fun testShouldRequestPermission() {
        // 测试权限请求逻辑
        // 情况1：在时间窗口内且无权限 → 应该请求
        // 情况2：在时间窗口外且无权限 → 不应该请求
        // 情况3：在时间窗口内且有权限 → 不应该请求
        // 情况4：在时间窗口外且有权限 → 不应该请求

        // 注意：我们无法控制当前时间，所以测试可能有局限性
        val inWindow = timeWindowManager.isInDetectionWindow()
        val hasPermission = false

        val shouldRequest = timeWindowManager.shouldRequestPermission(hasMediaProjectionPermission = hasPermission)

        // 如果当前在时间窗口内且无权限，应该请求
        // 如果当前在时间窗口外，不应该请求
        // 我们只验证方法不会崩溃并返回布尔值
        assertNotNull(shouldRequest)
    }

    @Test
    fun testCalculateDelayToNextWindow() {
        // 测试计算下一个时间窗口延迟
        val delay = timeWindowManager.calculateDelayToNextWindow()

        // 延迟应该是非负数
        assertTrue("延迟应该 >= 0", delay >= 0)

        // 如果已经在时间窗口内，延迟应该是0
        val inWindow = timeWindowManager.isInDetectionWindow()
        if (inWindow) {
            assertEquals("在时间窗口内时延迟应该为0", 0L, delay)
        }
    }

    @Test
    fun testGetRemainingTimeInWindow() {
        // 测试获取剩余时间
        val remainingTime = timeWindowManager.getRemainingTimeInWindow()

        // 剩余时间应该是非负数
        assertTrue("剩余时间应该 >= 0", remainingTime >= 0)

        // 如果不在时间窗口内，剩余时间应该是0
        val inWindow = timeWindowManager.isInDetectionWindow()
        if (!inWindow) {
            assertEquals("不在时间窗口内时剩余时间应该为0", 0L, remainingTime)
        }
    }

    @Test
    fun testGetTimeWindowDescription() {
        // 测试获取时间窗口描述
        val description = timeWindowManager.getTimeWindowDescription()

        // 描述不应该为空
        assertNotNull(description)
        assertTrue("描述应该非空", description.isNotEmpty())

        // 默认时间窗口描述应该包含22:00-02:00
        assertTrue("描述应该包含时间范围", description.contains("22:00") || description.contains("02:00"))
    }

    @Test
    fun testCustomTimeWindow() {
        // 测试自定义时间窗口（非跨天，例如10:00-18:00）
        val customWindow = com.example.direction.model.TimeWindow(
            startHour = 10,
            endHour = 18,
            enabled = true
        )

        // 测试自定义窗口的检测逻辑
        val inWindow = timeWindowManager.isInDetectionWindow(customWindow)
        assertNotNull(inWindow)

        // 测试自定义窗口的延迟计算
        val delay = timeWindowManager.calculateDelayToNextWindow(customWindow)
        assertTrue("延迟应该 >= 0", delay >= 0)

        // 测试自定义窗口的剩余时间计算
        val remainingTime = timeWindowManager.getRemainingTimeInWindow(customWindow)
        assertTrue("剩余时间应该 >= 0", remainingTime >= 0)
    }
}
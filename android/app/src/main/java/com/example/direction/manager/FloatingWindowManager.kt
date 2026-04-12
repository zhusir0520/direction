package com.example.direction.manager

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.direction.R
import com.example.direction.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮窗管理器
 * 负责创建、显示、隐藏悬浮窗，以及显示NSFW警告
 */
class FloatingWindowManager(
    private val context: Context,
    private val onPositionChanged: ((Int, Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "FloatingWindowManager"

        // 悬浮窗默认尺寸
        private const val WINDOW_WIDTH_DP = 60
        private const val WINDOW_HEIGHT_DP = 60

        // 警告显示时间（毫秒）
        private const val WARNING_DURATION_MS = 5000L // 5秒
    }

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var floatingView: View? = null
    private var floatingButton: Button? = null
    private var warningTextView: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // 拖拽相关变量
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // 警告显示任务
    private var warningJob: Job? = null

    // 原始按钮状态（用于恢复）
    private var originalButtonText: CharSequence? = null
    private var originalButtonBackground: Drawable? = null

    // 独立警告窗口相关
    private var warningWindowView: View? = null
    private var warningWindowParams: WindowManager.LayoutParams? = null
    private var warningWindowTextView: TextView? = null

    /**
     * 显示悬浮窗
     * @param initialX 初始X坐标（默认100）
     * @param initialY 初始Y坐标（默认100）
     */
    fun showFloatingWindow(initialX: Int = 100, initialY: Int = 100) {
        if (floatingView != null) {
            // 检查视图是否仍然附加到窗口
            val isAttached = floatingView?.isAttachedToWindow == true
            LogUtils.i(TAG, "悬浮窗已显示，isAttachedToWindow=$isAttached")
            if (isAttached) {
                return
            } else {
                // 视图已分离，清理后重新创建
                LogUtils.w(TAG, "悬浮窗视图已分离，清理后重新创建")
                hideFloatingWindow()
            }
        }

        try {
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)) {
                LogUtils.e(TAG, "没有悬浮窗权限，无法显示")
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }

            // 创建悬浮窗视图
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.floating_window, null)

            // 设置窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP

                // 计算安全的最小Y坐标（考虑状态栏高度）
                val statusBarHeight = getStatusBarHeight()
                val safeMinY = statusBarHeight + 20 // 状态栏高度 + 20像素边距

                // 调整初始位置，避免出现在状态栏或边框线上
                val adjustedX = initialX
                val adjustedY = if (initialY < safeMinY) {
                    // Y坐标太小，可能位于状态栏区域，调整到安全位置
                    safeMinY
                } else {
                    initialY
                }

                x = adjustedX
                y = adjustedY
                alpha = 0.0f // 设置透明度，0.0 = 完全透明

                LogUtils.d(TAG, "悬浮窗初始位置: 原始($initialX, $initialY), 调整后($adjustedX, $adjustedY), statusBarHeight=$statusBarHeight, safeMinY=$safeMinY")

                // 如果位置被调整了，通知回调保存新位置
                if (adjustedY != initialY) {
                    onPositionChanged?.invoke(adjustedX, adjustedY)
                    LogUtils.d(TAG, "已通知位置变化回调保存调整后的位置")
                }
            }

            windowParams = params

            // 获取并保存按钮引用
            floatingButton = floatingView?.findViewById<Button>(R.id.floating_button)

            // 保存原始按钮状态
            originalButtonText = floatingButton?.text
            originalButtonBackground = floatingButton?.background

            // 设置初始文本
            floatingButton?.text = "录制中"

            // 设置点击事件
            floatingButton?.setOnClickListener {
                // 点击悬浮窗时，可以打开主应用或执行其他操作
                Toast.makeText(context, "悬浮窗点击", Toast.LENGTH_SHORT).show()
            }

            // 设置拖拽事件
            floatingButton?.setOnTouchListener { view, event ->
                handleTouchEvent(view, event)
            }

            // 添加视图到窗口
            windowManager.addView(floatingView, params)
            LogUtils.i(TAG, "悬浮窗已显示")

            // 初始化警告文本视图（初始时隐藏）
            warningTextView = floatingView?.findViewById(R.id.warning_text)
            warningTextView?.visibility = View.GONE

        } catch (e: Exception) {
            LogUtils.e(TAG, "显示悬浮窗失败", e)
            Toast.makeText(context, "显示悬浮窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 隐藏悬浮窗
     */
    fun hideFloatingWindow() {
        warningJob?.cancel()
        warningJob = null

        floatingView?.let { view ->
            try {
                windowManager.removeView(view)
                LogUtils.i(TAG, "悬浮窗已隐藏")
            } catch (e: Exception) {
                LogUtils.e(TAG, "隐藏悬浮窗失败", e)
            }
            floatingView = null
        }
        windowParams = null
        warningTextView = null
        floatingButton = null
        originalButtonText = null
        originalButtonBackground = null
    }

    /**
     * 显示警告消息
     * @param message 警告消息内容
     */
    fun showWarning(message: String) {
        LogUtils.d(TAG, "showWarning被调用，消息: $message")
        LogUtils.d(TAG, "悬浮窗状态: floatingView=${floatingView != null}, warningTextView=${warningTextView != null}")

        if (floatingView == null) {
            LogUtils.w(TAG, "悬浮窗未显示，无法显示警告")
            LogUtils.w(TAG, "可能的原因: 1.悬浮窗功能未启用 2.悬浮窗权限未授予 3.悬浮窗显示失败")
            return
        }

        if (warningTextView == null) {
            LogUtils.w(TAG, "警告文本视图为空，可能布局加载失败")
            return
        }

        // 取消之前的警告任务
        warningJob?.cancel()
        LogUtils.d(TAG, "已取消之前的警告任务")

        warningJob = coroutineScope.launch {
            try {
                // 显示警告文本
                warningTextView?.let { textView ->
                    var useButtonWarning = false

                    textView.text = message
                    textView.visibility = View.VISIBLE

                    // 强制整个视图树更新
                    textView.invalidate()
                    textView.requestLayout()
                    floatingView?.invalidate()
                    floatingView?.requestLayout()

                    // 确保布局完成
                    textView.post {
                        // 再次检查布局
                        LogUtils.d(TAG, "警告文本位置(post): [${textView.left}, ${textView.top}, ${textView.right}, ${textView.bottom}]")
                        LogUtils.d(TAG, "警告文本测量: width=${textView.width}, height=${textView.height}")

                        // 如果位置仍然有问题，使用按钮警告替代
                        if (textView.top < 0 || textView.height == 0) {
                            LogUtils.w(TAG, "警告文本位置异常(top=${textView.top})，使用按钮警告")
                            useButtonWarning = true

                            // 隐藏文本警告
                            textView.visibility = View.GONE

                            // 显示按钮警告
                            restoreButton() // 确保按钮恢复原始状态
                            floatingButton?.let { button ->
                                button.text = message
                                button.setBackgroundColor(Color.RED)
                                button.setTextColor(Color.WHITE)
                                LogUtils.i(TAG, "已切换到按钮警告: $message")
                            }
                        } else {
                            LogUtils.i(TAG, "文本警告显示正常")
                        }
                    }

                    LogUtils.i(TAG, "显示警告: $message")
                    LogUtils.d(TAG, "警告文本已设置，可见性: ${textView.visibility}")

                    // 等待指定时间后隐藏警告
                    delay(WARNING_DURATION_MS)

                    // 恢复原始状态
                    if (useButtonWarning) {
                        restoreButton()
                    } else {
                        textView.visibility = View.GONE
                    }

                    // 更新视图
                    floatingView?.invalidate()
                    floatingView?.requestLayout()

                    LogUtils.i(TAG, "警告已隐藏")
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "显示警告失败", e)
            } finally {
                LogUtils.d(TAG, "警告显示协程结束")
            }
        }
        LogUtils.d(TAG, "警告显示任务已启动")
    }

    /**
     * 在按钮上显示警告（直接修改按钮状态）
     * @param message 警告消息内容
     */
    fun showWarningOnButton(message: String) {
        LogUtils.d(TAG, "showWarningOnButton被调用，消息: $message")

        if (floatingButton == null) {
            LogUtils.w(TAG, "悬浮窗按钮为空，无法显示按钮警告")
            return
        }

        // 保存原始状态（如果还没保存）
        if (originalButtonText == null) {
            originalButtonText = floatingButton?.text
            originalButtonBackground = floatingButton?.background
            LogUtils.d(TAG, "已保存原始按钮状态")
        }

        // 取消之前的警告任务
        warningJob?.cancel()
        LogUtils.d(TAG, "已取消之前的警告任务")

        // 在UI线程更新按钮状态
        floatingButton?.let { button ->
            button.text = message
            button.setBackgroundColor(Color.RED)
            button.setTextColor(Color.WHITE)
            LogUtils.i(TAG, "按钮警告已显示: $message")
        }

        // 启动恢复任务
        warningJob = coroutineScope.launch {
            try {
                delay(WARNING_DURATION_MS)
                restoreButton()
                LogUtils.i(TAG, "按钮已恢复原始状态")
            } catch (e: Exception) {
                LogUtils.e(TAG, "恢复按钮状态失败", e)
            }
        }
        LogUtils.d(TAG, "按钮警告恢复任务已启动")
    }

    /**
     * 恢复按钮原始状态
     */
    private fun restoreButton() {
        floatingButton?.let { button ->
            originalButtonText?.let { button.text = it }
            originalButtonBackground?.let { button.background = it }
            button.setTextColor(Color.WHITE) // 恢复文本颜色
        }
    }

    /**
     * 更新悬浮窗位置
     * @param x X坐标
     * @param y Y坐标
     */
    fun updatePosition(x: Int, y: Int) {
        windowParams?.let { params ->
            params.x = x
            params.y = y
            floatingView?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                    LogUtils.d(TAG, "悬浮窗位置更新: x=$x, y=$y")
                } catch (e: Exception) {
                    LogUtils.e(TAG, "更新悬浮窗位置失败", e)
                }
            }
        }
    }

    /**
     * 获取当前悬浮窗位置
     * @return Pair<X坐标, Y坐标>，如果没有显示则返回null
     */
    fun getCurrentPosition(): Pair<Int, Int>? {
        return windowParams?.let { Pair(it.x, it.y) }
    }

    /**
     * 显示中央通知（独立悬浮窗口）
     * @param message 通知消息内容
     */
    fun showCenteredNotification(message: String) {
        LogUtils.d(TAG, "showCenteredNotification被调用，消息: $message")

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)) {
            LogUtils.w(TAG, "没有悬浮窗权限，无法显示中央通知")
            return
        }

        // 取消之前的警告任务
        warningJob?.cancel()
        LogUtils.d(TAG, "已取消之前的警告任务")

        // 隐藏现有的警告窗口
        hideWarningWindow()

        try {
            // 创建警告文本视图
            val textView = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(40, 20, 40, 20)
                setBackgroundResource(android.R.color.holo_red_dark)
                alpha = 0.95f

                // 设置最大宽度和自动换行
                maxWidth = 600
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }

            // 创建窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = 0
                y = 0
                alpha = 0.9f // 窗口透明度
            }

            // 保存引用
            warningWindowView = textView
            warningWindowParams = params
            warningWindowTextView = textView

            // 添加到窗口
            windowManager.addView(textView, params)
            LogUtils.i(TAG, "中央通知已显示: $message")

            // 启动自动隐藏任务
            warningJob = coroutineScope.launch {
                try {
                    delay(WARNING_DURATION_MS)
                    hideWarningWindow()
                    LogUtils.i(TAG, "中央通知已自动隐藏")
                } catch (e: Exception) {
                    LogUtils.e(TAG, "隐藏中央通知失败", e)
                }
            }
            LogUtils.d(TAG, "中央通知隐藏任务已启动")

        } catch (e: Exception) {
            LogUtils.e(TAG, "显示中央通知失败", e)
        }
    }

    /**
     * 隐藏独立警告窗口
     */
    private fun hideWarningWindow() {
        warningWindowView?.let { view ->
            try {
                windowManager.removeView(view)
                LogUtils.d(TAG, "独立警告窗口已隐藏")
            } catch (e: Exception) {
                LogUtils.e(TAG, "隐藏独立警告窗口失败", e)
            }
            warningWindowView = null
        }
        warningWindowParams = null
        warningWindowTextView = null
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        hideFloatingWindow()
        hideWarningWindow()
    }

    /**
     * 处理触摸事件以实现拖拽
     */
    private fun handleTouchEvent(view: View, event: MotionEvent): Boolean {
        LogUtils.d(TAG, "handleTouchEvent: action=${event.action}, rawX=${event.rawX}, rawY=${event.rawY}")

        windowParams?.let { params ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录初始位置
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    LogUtils.d(TAG, "ACTION_DOWN: initialX=$initialX, initialY=$initialY, touchX=$initialTouchX, touchY=$initialTouchY")
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 计算移动距离并更新位置
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    var newX = initialX + deltaX
                    var newY = initialY + deltaY

                    // 获取屏幕尺寸和悬浮窗尺寸以进行边界限制
                    val screenWidth: Int
                    val screenHeight: Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val windowMetrics = windowManager.currentWindowMetrics
                        val bounds = windowMetrics.bounds
                        screenWidth = bounds.width()
                        screenHeight = bounds.height()
                    } else {
                        @Suppress("DEPRECATION")
                        val displayMetrics = DisplayMetrics()
                        windowManager.defaultDisplay.getMetrics(displayMetrics)
                        screenWidth = displayMetrics.widthPixels
                        screenHeight = displayMetrics.heightPixels
                    }

                    // 获取悬浮窗实际尺寸（如果视图已附加）
                    var floatingWidth = 120 // 默认估计值（60dp + 边距）
                    var floatingHeight = 120 // 默认估计值

                    floatingView?.let { view ->
                        if (view.width > 0 && view.height > 0) {
                            floatingWidth = view.width
                            floatingHeight = view.height
                        }
                    }

                    // 最小可见像素（确保至少露出一点点）
                    val minVisiblePixels = 20

                    // 限制X坐标：允许拖到侧边窗口外，但必须露出至少minVisiblePixels像素
                    // 左边限制：-floatingWidth + minVisiblePixels（允许左侧隐藏大部分，但露出一点点）
                    // 右边限制：screenWidth - minVisiblePixels（允许右侧隐藏大部分，但露出一点点）
                    val minX = -floatingWidth + minVisiblePixels
                    val maxX = screenWidth - minVisiblePixels
                    newX = newX.coerceIn(minX, maxX)

                    // 限制Y坐标：不允许超出屏幕上下边界
                    // 顶部限制：0（完全可见）或根据需要调整
                    // 底部限制：screenHeight - floatingHeight（完全可见）
                    val minY = 0
                    val maxY = screenHeight - floatingHeight
                    newY = newY.coerceIn(minY, maxY)

                    params.x = newX
                    params.y = newY

                    LogUtils.d(TAG, "ACTION_MOVE: deltaX=$deltaX, deltaY=$deltaY, newX=${params.x}, newY=${params.y}, screenWidth=$screenWidth, floatingWidth=$floatingWidth")

                    try {
                        windowManager.updateViewLayout(floatingView, params)
                        LogUtils.d(TAG, "视图布局更新成功")
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "更新视图布局失败", e)
                        return false
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // 拖拽结束，保存位置到持久化存储
                    val finalX = params.x
                    val finalY = params.y
                    LogUtils.d(TAG, "拖拽结束，最终位置: x=$finalX, y=$finalY")

                    // 通知位置变化回调
                    onPositionChanged?.invoke(finalX, finalY)
                    return true
                }
            }
        }
        LogUtils.w(TAG, "windowParams为空，无法处理触摸事件")
        return false
    }

    /**
     * 获取状态栏高度
     */
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        LogUtils.d(TAG, "状态栏高度: $result 像素")
        return result
    }
}
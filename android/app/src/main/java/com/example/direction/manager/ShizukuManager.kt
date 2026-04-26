package com.example.direction.manager

import android.os.ParcelFileDescriptor
import com.example.direction.utils.LogUtils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 状态枚举
 */
enum class ShizukuState {
    /** Shizuku 服务未运行 */
    NOT_RUNNING,
    /** Shizuku 服务运行中但命令执行不可用（未授权或其他问题） */
    NO_PERMISSION,
    /** Shizuku 服务运行中且可正常执行命令 */
    PERMISSION_GRANTED
}

/**
 * Shizuku 核心管理类
 * 负责管理 Shizuku 状态、执行强制停止前台应用等特权操作
 */
class ShizukuManager {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val OUR_PACKAGE = "com.example.direction"

        // 缓存命令执行能力，避免每次状态检查都执行命令
        private var commandWorkingCache: Boolean? = null
    }

    // 缓存前台应用包名（在检测周期开始时更新，避免 activity 跳转导致 null）
    @Volatile
    private var cachedForegroundPackage: String? = null

    // 缓存前台应用的 task ID（用于移除最近任务卡片）
    @Volatile
    private var cachedForegroundTaskId: Long? = null

    /**
     * 获取当前 Shizuku 状态
     * 通过 pingBinder 判断是否运行，通过执行测试命令判断是否可用
     * @return ShizukuState
     */
    fun getCurrentState(): ShizukuState {
        if (!Shizuku.pingBinder()) {
            commandWorkingCache = null
            return ShizukuState.NOT_RUNNING
        }

        // Shizuku 运行中，测试命令执行是否正常
        val cmdWorks = testCommandExecution()
        LogUtils.d(TAG, "getCurrentState: pingBinder=true, commandWorking=$cmdWorks")
        return if (cmdWorks) ShizukuState.PERMISSION_GRANTED else ShizukuState.NO_PERMISSION
    }

    /**
     * 检查 Shizuku 命令执行是否可用
     */
    fun isPermissionGranted(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return testCommandExecution()
    }

    /**
     * 请求 Shizuku 权限（弹出授权对话框）
     */
    fun requestPermission() {
        Shizuku.requestPermission(OUR_PACKAGE.hashCode())
    }

    /**
     * 执行测试命令验证 shell 是否可用
     */
    private fun testCommandExecution(): Boolean {
        // 使用缓存结果（避免频繁测试）
        commandWorkingCache?.let { return it }

        try {
            val output = executeShellCommand("echo ok")
            val result = output?.trim() == "ok"
            LogUtils.d(TAG, "testCommandExecution: result=$result, output='${output?.trim()}'")
            commandWorkingCache = result
            return result
        } catch (e: Exception) {
            LogUtils.e(TAG, "testCommandExecution: failed", e)
            commandWorkingCache = false
            return false
        }
    }

    /** 重置命令缓存（binder 断开/重连时调用） */
    fun resetCommandCache() {
        commandWorkingCache = null
    }

    /**
     * 通过 Shizuku 执行 shell 命令
     * 同时捕获 stdout 和 stderr
     */
    private fun executeShellCommand(command: String): String? {
        return try {
            val binder = Shizuku.getBinder() ?: run {
                LogUtils.e(TAG, "Shizuku binder is null")
                return null
            }
            val service = IShizukuService.Stub.asInterface(binder) ?: run {
                LogUtils.e(TAG, "IShizukuService is null")
                return null
            }

            LogUtils.d(TAG, "executeShellCommand: running '$command'")
            val remoteProcess = service.newProcess(
                arrayOf("sh", "-c", command),
                null,
                null
            )

            // 读取 stdout
            val stdoutFd = remoteProcess.inputStream
            val stdout = ParcelFileDescriptor.AutoCloseInputStream(stdoutFd)
            val stdoutReader = BufferedReader(InputStreamReader(stdout))
            val output = stdoutReader.readText()
            stdoutReader.close()

            // 读取 stderr
            val stderrFd = remoteProcess.errorStream
            val stderrStr = try {
                val stderr = ParcelFileDescriptor.AutoCloseInputStream(stderrFd)
                val errText = stderr.bufferedReader().readText()
                stderr.close()
                errText
            } catch (e: Exception) { "" }

            val exitCode = remoteProcess.waitFor()
            LogUtils.d(TAG, "executeShellCommand: exitCode=$exitCode")
            if (output.isNotEmpty()) LogUtils.d(TAG, "  stdout: ${output.take(500)}")
            if (stderrStr.isNotEmpty()) LogUtils.d(TAG, "  stderr: ${stderrStr.take(500)}")
            output
        } catch (e: Exception) {
            LogUtils.e(TAG, "Shell command execution failed: $command", e)
            null
        }
    }

    /**
     * 诊断辅助：执行命令并记录完整的原始输出（用于调试 Android 14+ 兼容性问题）
     */
    private fun diagnosticDump(cmd: String, label: String): String? {
        val output = executeShellCommand(cmd)
        LogUtils.d(TAG, "=== diag $label ===")
        LogUtils.d(TAG, output ?: "null")
        LogUtils.d(TAG, "=== end diag $label ===")
        return output
    }

    /**
     * 在检测周期开始时调用，缓存当前前台应用包名和 task ID
     * 这时用户正在使用目标应用，截图还未开始，DetectionResultActivity 尚未打开
     */
    fun updateCachedForegroundPackage() {
        cachedForegroundTaskId = null
        cachedForegroundPackage = getForegroundAppPackageName()
        LogUtils.d(TAG, "updateCachedForegroundPackage: cached=$cachedForegroundPackage, taskId=$cachedForegroundTaskId")
    }

    /** 获取缓存的包名（不执行新查询） */
    fun getCachedForegroundPackage(): String? = cachedForegroundPackage

    /** 获取缓存的 task ID（不执行新查询） */
    fun getCachedForegroundTaskId(): Long? = cachedForegroundTaskId

    /**
     * 获取前台应用的包名
     *
     * 采用多级检测策略：
     * 1. mCurrentFocus/mFocusedApp (dumpsys window) — 最准确，但 Android 14+ 可能受悬浮窗影响
     * 2. recents 带时间过滤 (dumpsys activity recents) — 只取 30 秒内活跃的应用，避免杀历史应用
     * 3. mResumedActivity (dumpsys activity activities) — 备选
     * 4. recents 不带时间过滤 — 最终兜底
     */
    fun getForegroundAppPackageName(): String? {
        cachedForegroundTaskId = null // 每次检测时重置 task ID

        // 提前收集 recents dump，后面多个方法都需要，也给方法1用来反查 task ID
        val recentsDump = executeShellCommand("dumpsys activity recents 2>&1")

        // 方法1: dumpsys window mCurrentFocus/mFocusedApp
        // 悬浮窗(TYPE_APPLICATION_OVERLAY)不获取焦点，mFocusedApp 应指向实际前台应用
        val windowDump = executeShellCommand("dumpsys window 2>&1")
        LogUtils.d(TAG, "--- dumpsys window (first 1k) ---")
        LogUtils.d(TAG, windowDump?.take(1000) ?: "null")
        LogUtils.d(TAG, "--- end ---")
        var packageName = parseFocusedApp(windowDump)
        if (packageName != null && isValidTargetPackage(packageName)) {
            // 从 recents 中反查 task ID
            cachedForegroundTaskId = findTaskIdInRecents(recentsDump, packageName)
            LogUtils.i(TAG, "getForegroundAppPackageName [window-focus]: $packageName, taskId=$cachedForegroundTaskId")
            return packageName
        }

        // 方法2: dumpsys activity recents 带时间过滤（只取 30 秒内活跃的，排除历史应用）
        LogUtils.d(TAG, "--- dumpsys activity recents (first 1k) ---")
        LogUtils.d(TAG, recentsDump?.take(1000) ?: "null")
        LogUtils.d(TAG, "--- end ---")
        val (recentPkg, recentTaskId) = parseRecentTaskWithTimeFilter(recentsDump)
        if (recentPkg != null) {
            cachedForegroundTaskId = recentTaskId
            LogUtils.i(TAG, "getForegroundAppPackageName [recents-time]: $recentPkg, taskId=$recentTaskId")
            return recentPkg
        }

        // 方法3: dumpsys activity activities mResumedActivity
        val activitiesDump = executeShellCommand("dumpsys activity activities 2>&1")
        packageName = parseResumedActivity(activitiesDump)
        if (packageName != null && isValidTargetPackage(packageName)) {
            cachedForegroundTaskId = findTaskIdInRecents(recentsDump, packageName)
            LogUtils.i(TAG, "getForegroundAppPackageName [activities]: $packageName, taskId=$cachedForegroundTaskId")
            return packageName
        }

        // 方法4: recents 不带时间过滤（兜底，兼容旧设备）
        packageName = parseRecentTaskPackage(recentsDump)
        if (packageName != null) {
            cachedForegroundTaskId = findTaskIdInRecents(recentsDump, packageName)
            LogUtils.i(TAG, "getForegroundAppPackageName [recents-fallback]: $packageName, taskId=$cachedForegroundTaskId")
            return packageName
        }

        // 备选回退: window dump + snapshot（保留原有方式）
        packageName = parseWindowDumpForPackage(windowDump)
        if (packageName != null && isValidTargetPackage(packageName)) {
            LogUtils.i(TAG, "getForegroundAppPackageName [window-raw]: $packageName")
            return packageName
        }
        packageName = parseTopAppFromSnapshots(windowDump)
        if (packageName != null && isValidTargetPackage(packageName)) {
            LogUtils.i(TAG, "getForegroundAppPackageName [snapshot]: $packageName")
            return packageName
        }

        LogUtils.w(TAG, "getForegroundAppPackageName: all methods failed")
        return null
    }

    /**
     * 从 dumpsys window 输出中解析 mCurrentFocus 或 mFocusedApp
     *
     * 格式: mCurrentFocus=Window{... u0 com.example.app/com.example.app.MainActivity}
     *       mFocusedApp=Window{... u0 com.example.app/com.example.app.MainActivity}
     */
    private fun parseFocusedApp(dump: String?): String? {
        if (dump.isNullOrBlank()) return null

        val patterns = listOf(
            Regex("mCurrentFocus=.*\\s([a-zA-Z0-9._]+)/"),
            Regex("mFocusedApp=.*\\s([a-zA-Z0-9._]+)/")
        )

        for (pattern in patterns) {
            val match = pattern.find(dump)
            if (match != null) {
                val pkg = match.groupValues[1]
                if (pkg.contains(".")) {
                    LogUtils.d(TAG, "parseFocusedApp: matched pkg=$pkg")
                    return pkg
                }
            }
        }

        LogUtils.d(TAG, "parseFocusedApp: no match")
        return null
    }

    /**
     * 解析 dumpsys activity recents 输出，只取指定秒数内活跃的任务
     * 避免杀到历史应用（如 Boss Zhipin 等几小时前打开的应用）
     *
     * 格式:
     *   * Recent #0: Task{... #56 A=10104:com.twitter.android}
     *     ...
     *     lastActiveTime=9946168 (inactive for 3s)
     */
    private fun parseRecentTaskWithTimeFilter(dump: String?, maxInactiveSeconds: Long = 30): Pair<String?, Long?> {
        if (dump.isNullOrBlank()) return Pair(null, null)

        // Triple<packageName, taskId, inactiveSeconds>
        val tasks = mutableListOf<Triple<String, Long, Long>>()
        val lines = dump.lines()
        var currentPkg: String? = null
        var currentTaskId: Long? = null

        for (line in lines) {
            val trimmed = line.trim()

            // * Recent #N: Task{... #56 type=standard A=10104:com.twitter.android}
            // group1 = taskId (#56), group2 = packageName
            val recentMatch = Regex("""\* Recent #\d+: Task\{[^}]+ #(\d+) type=\w+ A=\d+:([a-zA-Z0-9._]+)\}""").find(trimmed)
            if (recentMatch != null) {
                // 保存上一个任务（无 inactiveTime 的不计入）
                if (currentPkg != null && currentTaskId != null) {
                    tasks.add(Triple(currentPkg, currentTaskId, Long.MAX_VALUE))
                }
                currentPkg = recentMatch.groupValues[2]
                currentTaskId = recentMatch.groupValues[1].toLongOrNull()
                continue
            }

            // 解析 inactive for Xs — 直接得到秒级非活跃时间
            val inactiveMatch = Regex("inactive for (\\d+)s").find(trimmed)
            if (inactiveMatch != null && currentPkg != null && currentTaskId != null) {
                val seconds = inactiveMatch.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
                tasks.add(Triple(currentPkg, currentTaskId, seconds))
                currentPkg = null
                currentTaskId = null
            }
        }

        // 过滤：只保留非活跃时间 <= maxInactiveSeconds 的有效目标
        val valid = tasks
            .filter { (pkg, _, inactive) -> isValidTargetPackage(pkg) && inactive <= maxInactiveSeconds }
            .sortedBy { (_, _, inactive) -> inactive }

        LogUtils.d(TAG, "parseRecentTaskWithTimeFilter: ${valid.size} valid (inactive <= ${maxInactiveSeconds}s)")
        for (t in valid) {
            LogUtils.d(TAG, "  pkg=${t.first}, taskId=${t.second}, inactive=${t.third}s")
        }
        val total = tasks.size
        val skipped = tasks.count { !isValidTargetPackage(it.first) }
        LogUtils.d(TAG, "  total=$total, skipped(system/self)=$skipped, filtered(time)=${total - skipped - valid.size}")

        val best = valid.firstOrNull()
        return Pair(best?.first, best?.second)
    }

    /**
     * 在 recents dump 中查找指定包名的 task ID
     *
     * 格式: * Recent #0: Task{... #131 type=standard A=10171:com.twitter.android}
     */
    private fun findTaskIdInRecents(dump: String?, packageName: String): Long? {
        if (dump.isNullOrBlank()) return null
        val escapedPkg = Regex.escape(packageName)

        // 方式1: 标准格式匹配（更灵活 - 不要求 type=\w+）
        val pattern = Regex("""\* Recent #\d+: Task\{[^}]*? #(\d+)[^}]*?A=\d+:${escapedPkg}\}""")
        var match = pattern.find(dump)
        if (match != null) {
            val taskId = match.groupValues[1].toLongOrNull()
            LogUtils.d(TAG, "findTaskIdInRecents [regex]: pkg=$packageName, taskId=$taskId")
            return taskId
        }

        // 方式2: 行级搜索兜底（某些设备格式特殊）
        for (line in dump.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("* Recent #") && trimmed.contains(packageName)) {
                val idMatch = Regex("""\* Recent #\d+: Task\{[^}]*? #(\d+)""").find(trimmed)
                if (idMatch != null) {
                    val taskId = idMatch.groupValues[1].toLongOrNull()
                    LogUtils.d(TAG, "findTaskIdInRecents [line]: pkg=$packageName, taskId=$taskId")
                    return taskId
                }
            }
        }

        LogUtils.d(TAG, "findTaskIdInRecents: pkg=$packageName not found")
        return null
    }

    /**
     * 从 dumpsys activity activities 输出中解析前台 Activity
     * 作为获取前台应用的备选方案
     *
     * 格式:
     *   mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity t123}
     *   topResumedActivity=ActivityRecord{... u0 com.example.app/.MainActivity t123}
     */
    private fun parseResumedActivity(dump: String?): String? {
        if (dump.isNullOrBlank()) return null

        // Android 不同版本用不同字段名
        val patterns = listOf(
            Regex("mResumedActivity:.*\\s([a-zA-Z0-9._]+)/"),
            Regex("topResumedActivity=.*\\s([a-zA-Z0-9._]+)/")
        )

        for (pattern in patterns) {
            val match = pattern.find(dump)
            if (match != null) {
                val pkg = match.groupValues[1]
                if (pkg.contains(".")) {
                    LogUtils.d(TAG, "parseResumedActivity: matched pkg=$pkg")
                    return pkg
                }
            }
        }

        LogUtils.d(TAG, "parseResumedActivity: no match")
        return null
    }

    /**
     * 解析 dumpsys activity recents 输出，找到最近使用的非系统、非本应用
     *
     * 格式示例:
     *   * Recent #0: Task{... #56 type=standard A=10104:com.google.android.documentsui}
     *     ...
     *     lastActiveTime=9946168 (inactive for 65s)
     *   mHiddenTasks=[Task{... #103 type=standard A=10150:com.android.chrome}]
     */
    private fun parseRecentTaskPackage(dump: String?): String? {
        if (dump.isNullOrBlank()) return null

        // 解析: taskId -> (packageName, lastActiveTime)
        val tasks = mutableListOf<Triple<Long, String, Long>>()

        val lines = dump.lines()
        var currentTaskId: Long? = null
        var currentPackage: String? = null
        var currentLastActive: Long? = null

        for (line in lines) {
            val trimmed = line.trim()

            // 匹配 Recent #N 行: * Recent #0: Task{d4a11b6 #56 type=standard A=10104:com.google.android.documentsui}
            // Task{hexHash #taskId type=xxx A=uid:package}
            val recentMatch = Regex("""\* Recent #\d+: Task\{[^}]+ #(\d+) type=\w+ A=\d+:([a-zA-Z0-9._]+)\}""").find(trimmed)
            if (recentMatch != null) {
                // 保存上一个任务
                if (currentTaskId != null && currentPackage != null) {
                    tasks.add(Triple(currentTaskId, currentPackage, currentLastActive ?: 0L))
                }
                currentTaskId = recentMatch.groupValues[1].toLongOrNull()
                currentPackage = recentMatch.groupValues[2]
                currentLastActive = null
                LogUtils.d(TAG, "parseRecentTaskPackage: found recent pkg=$currentPackage, taskId=$currentTaskId")
                continue
            }

            // 匹配 lastActiveTime
            val timeMatch = Regex("lastActiveTime=(\\d+)").find(trimmed)
            if (timeMatch != null && currentTaskId != null) {
                currentLastActive = timeMatch.groupValues[1].toLongOrNull()
            }

            // 匹配 mHiddenTasks 行: mHiddenTasks=[Task{... #103 type=standard A=10150:com.android.chrome}]
            // 也可能是多任务格式: mHiddenTasks=[Task{... #101 ...}, Task{... #102 ...}]
            if (trimmed.startsWith("mHiddenTasks=")) {
                // 使用宽松匹配找出所有 A=uid:package 模式
                val pkgPattern = Regex("A=\\d+:([a-zA-Z0-9._]+)")
                val pkgs = pkgPattern.findAll(trimmed).map { it.groupValues[1] }.toList()
                // 也尝试从后续行找（跨行格式）
                if (pkgs.isNotEmpty()) {
                    for (pkg in pkgs) {
                        LogUtils.d(TAG, "parseRecentTaskPackage: hidden task pkg=$pkg")
                        if (isValidTargetPackage(pkg)) {
                            tasks.add(Triple(9999999L, pkg, 999999999999L))
                        }
                    }
                }
            }
        }
        // 保存最后一个任务
        if (currentTaskId != null && currentPackage != null) {
            tasks.add(Triple(currentTaskId, currentPackage, currentLastActive ?: 0L))
        }

        if (tasks.isEmpty()) {
            LogUtils.d(TAG, "parseRecentTaskPackage: no tasks found in recents")
            return null
        }

        // 过滤并排序: 排除系统和我们的应用，按 lastActiveTime 降序
        val validTasks = tasks
            .filter { (_, pkg, _) -> isValidTargetPackage(pkg) }
            .sortedByDescending { (_, _, time) -> time }

        LogUtils.d(TAG, "parseRecentTaskPackage: found ${validTasks.size} valid tasks")
        for (t in validTasks) {
            LogUtils.d(TAG, "  taskId=${t.first}, pkg=${t.second}, lastActive=${t.third}")
        }
        return validTasks.firstOrNull()?.second
    }

    /** 系统包前缀列表（不会被强杀） */
    private val systemPackagePrefixes = listOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.deskclock",
        "com.android.calendar",
        "com.android.contacts",
        "com.android.dialer",
        "com.android.messaging",
        "com.android.launcher",
        "com.android.phone",
        "com.android.providers",
        "com.android.server",
        "com.android.packageinstaller",
        "com.android.documentsui",
        "com.android.inputmethod",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.captiveportallogin",
        "com.android.carrierconfig",
        "com.android.wallpaper",
        "com.android.printspooler",
        "com.android.shell",
        "com.android.vending",
        "com.android.permissioncontroller",
        // 保留 YouTube Vanced/ReVanced（它们通常使用不同包名）
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.documentsui",
        "com.google.android.gms",
        "com.google.android.gsf"
    )

    /** 检查包名是否是有效的强杀目标（排除系统应用和我们自己） */
    private fun isValidTargetPackage(pkg: String): Boolean {
        if (pkg == OUR_PACKAGE) return false
        if (!pkg.contains(".")) return false
        // 检查是否匹配系统包前缀
        for (prefix in systemPackagePrefixes) {
            if (pkg == prefix || pkg.startsWith("$prefix.")) return false
        }
        // com.android.chrome 是用户浏览器应用，应该可以强杀
        return true
    }

    /**
     * 从 dumpsys window 原始输出中解析包名
     * 专门处理 Android 14+ 的格式，如 "package=com.example.app"
     * 注意：必须过滤掉 appop/权限相关行，这些行包含 package= 但不是前台应用
     */
    private fun parseWindowDumpForPackage(dump: String?): String? {
        if (dump.isNullOrBlank()) return null

        // 先尝试在整段输出中找常见的焦点字段
        val focusPatterns = listOf(
            Regex("mCurrentFocus=.*\\s([a-zA-Z0-9._]+)/"),
            Regex("mFocusedApp=.*\\s([a-zA-Z0-9._]+)/"),
            Regex("focusedApp[=:]\\s*([a-zA-Z0-9._]+)"),
            Regex("FocusedApplication[:=]\\s*([a-zA-Z0-9._]+)")
        )
        for (pattern in focusPatterns) {
            val match = pattern.find(dump)
            if (match != null) {
                val pkg = match.groupValues.lastOrNull { it.contains(".") && !it.contains("android") && !it.startsWith("com.android") }
                if (pkg != null && pkg.isNotEmpty()) {
                    LogUtils.d(TAG, "parseWindowDumpForPackage: focus pattern match: $pkg")
                    return pkg
                }
            }
        }

        val lines = dump.lines()
        var inDisplaySection = false
        for (line in lines) {
            val trimmed = line.trim()

            // 检测是否进入窗口/显示区域（跳过 policy 区域）
            if (trimmed.contains("WINDOW MANAGER DISPLAY", ignoreCase = true) ||
                trimmed.contains("Display ", ignoreCase = true) ||
                trimmed.contains("Focused display", ignoreCase = true) ||
                trimmed.startsWith("Windows:")) {
                inDisplaySection = true
            }

            // 跳过权限相关行（SYSTEM_ALERT_WINDOW 等）
            if (trimmed.contains("appop=", ignoreCase = true) ||
                trimmed.contains("mOwnerUid=") ||
                trimmed.contains("showForAllUsers=")) {
                continue
            }

            // 只在 display 区域或未明确标记区域中搜索
            // focusedApp=com.example.app (Android 14+ 格式)
            var pkgMatch = Regex("focusedApp[=:]\\s*([a-zA-Z0-9._]+)").find(trimmed)
            if (pkgMatch != null) {
                val pkg = pkgMatch.groupValues[1]
                if (pkg.contains(".") && !pkg.contains("android")) {
                    LogUtils.d(TAG, "parseWindowDumpForPackage: found focusedApp=$pkg from line='$trimmed'")
                    return pkg
                }
            }
            // Android 14+ 可能使用 "package=com.example.app" 格式（仅在进入 display 区域后）
            pkgMatch = Regex("package[=:]\\s*([a-zA-Z0-9._]+)").find(trimmed)
            if (pkgMatch != null) {
                val pkg = pkgMatch.groupValues[1]
                if (pkg.contains(".") && !pkg.contains("android")) {
                    LogUtils.d(TAG, "parseWindowDumpForPackage: found package=$pkg from line='$trimmed'")
                    // 只有在 display 区域才相信这个匹配
                    if (inDisplaySection) {
                        return pkg
                    }
                    LogUtils.d(TAG, "parseWindowDumpForPackage: skipping (not in display section)")
                }
            }
            // window identifier 格式：Window{... u0 com.example.app}
            val windowMatch = Regex("Window\\{[^}]+\\s+u\\d+\\s+([a-zA-Z0-9._]+)/").find(trimmed)
            if (windowMatch != null) {
                val pkg = windowMatch.groupValues[1]
                if (pkg.contains(".") && !pkg.contains("android")) {
                    LogUtils.d(TAG, "parseWindowDumpForPackage: found window pkg=$pkg from line='$trimmed'")
                    if (inDisplaySection) {
                        return pkg
                    }
                    LogUtils.d(TAG, "parseWindowDumpForPackage: skipping (not in display section)")
                }
            }
        }

        LogUtils.d(TAG, "parseWindowDumpForPackage: no package found in raw dump")
        return null
    }

    /**
     * 从 dumpsys window 输出的 Snapshot 区域解析 topApp
     * 这是 Android 14+ Shizuku 限制下能获得的唯一可用信息
     * 使用最高 token 的 topApp 作为最近使用的应用
     *
     * 格式: topApp=ActivityRecord{... u0 package.name/.Activity tTOKEN}
     */
    private fun parseTopAppFromSnapshots(dump: String?): String? {
        if (dump.isNullOrBlank()) return null

        val lines = dump.lines()
        var bestPackage: String? = null
        var highestToken = -1L

        for (line in lines) {
            val trimmed = line.trim()
            // 匹配: topApp=ActivityRecord{... u0 package.name/.Activity tTOKEN}
            val match = Regex("topApp=ActivityRecord\\{[^}]+\\s+u\\d+\\s+([a-zA-Z0-9._]+)/[^t]+t(\\d+)").find(trimmed)
            if (match != null) {
                val pkg = match.groupValues[1]
                val token = match.groupValues[2].toLongOrNull() ?: 0L
                if (pkg.contains(".") && !pkg.contains("android") && !pkg.startsWith("com.android")) {
                    LogUtils.d(TAG, "parseTopAppFromSnapshots: found pkg=$pkg, token=$token")
                    if (token > highestToken) {
                        highestToken = token
                        bestPackage = pkg
                    }
                }
            }
        }

        if (bestPackage != null) {
            LogUtils.d(TAG, "parseTopAppFromSnapshots: best=$bestPackage (token=$highestToken)")
            // 排除自己的包
            if (bestPackage == OUR_PACKAGE) {
                LogUtils.d(TAG, "parseTopAppFromSnapshots: skip self package")
                return null
            }
            return bestPackage
        }

        LogUtils.d(TAG, "parseTopAppFromSnapshots: no snapshot found")
        return null
    }

    /**
     * 从 dumpsys 输出中解析前台应用包名
     */
    private fun parsePackageNameFromDumpsys(output: String?): String? {
        if (output.isNullOrBlank()) return null

        val patterns = listOf(
            // mCurrentFocus=Window{... u0 com.example.app/com.example.app.MainActivity}
            // mFocusedApp=... Window{... u0 com.example.app/com.example.app.MainActivity}
            Regex("m(CurrentFocus|FocusedApp|FocusedWindow)=.*\\s([a-zA-Z0-9._]+)/"),
            // mResumedActivity: ActivityRecord{... u0 com.example.app/.MainActivity}
            Regex("mResumedActivity:.*\\s([a-zA-Z0-9._]+)/"),
            // mFocusedActivity: ActivityRecord{... u0 com.example.app/.MainActivity}
            Regex("mFocusedActivity:.*\\s([a-zA-Z0-9._]+)/"),
            // 回退：Window{... u0 com.example.app/com.example.app.MainActivity}
            Regex("Window\\{[^}]+\\s+([a-zA-Z0-9._]+)/"),
            // 进一步回退
            Regex("([a-zA-Z0-9]+\\.[a-zA-Z0-9.]+)/[a-zA-Z0-9.]+")
        )

        for (regex in patterns) {
            val match = regex.find(output)
            if (match != null) {
                // 捕获组可能是 group 1 或 2（取决于模式中是否有可选组）
                val pkg = match.groupValues.last { it.isNotEmpty() && it.contains(".") }
                LogUtils.d(TAG, "parsePackageNameFromDumpsys: matched with pkg=$pkg")
                return pkg
            }
        }

        LogUtils.w(TAG, "parsePackageNameFromDumpsys: no match. output=${output.take(300)}")
        return null
    }

    /**
     * 从最近任务中移除指定 task 的卡片
     * 按 Android 版本尝试多种方式
     */
    private fun removeTaskFromRecents(taskId: Long) {
        LogUtils.i(TAG, "removeTaskFromRecents: removing task #$taskId")
        val commands = listOf(
            "cmd activity task remove $taskId 2>&1",
            "cmd activity remove task $taskId 2>&1",
            "service call activity 101 i32 $taskId 2>&1",
            "service call activity 102 i32 $taskId 2>&1",
            "service call activity 103 i32 $taskId 2>&1",
            "service call activity 107 i32 $taskId 2>&1",
            "service call activity 108 i32 $taskId 2>&1",
            "am stack remove $taskId 2>&1",
            "service call activity 79 i32 $taskId 2>&1",
        )
        for (cmd in commands) {
            val out = (executeShellCommand(cmd)?.trim() ?: "").take(80)
            LogUtils.d(TAG, "removeTaskFromRecents: ${cmd.take(50)} → $out")
        }
        LogUtils.i(TAG, "removeTaskFromRecents: done")
    }

    /**
     * 强制停止前台应用
     * 优先使用缓存的包名（在检测开始时获取），避免 activity 跳转导致的 null
     * 如果缓存无效，尝试实时检测（带重试）
     */
    fun killForegroundApp(): Boolean {
        LogUtils.i(TAG, "=== killForegroundApp called ===")
        return try {
            // 优先使用缓存的包名
            var packageName = cachedForegroundPackage
            LogUtils.i(TAG, "killForegroundApp: cached foreground package=$packageName")

            // 如果缓存无效，实时检测（带重试）
            if (packageName == null) {
                LogUtils.w(TAG, "killForegroundApp: cache invalid, trying live detection with retry")
                for (attempt in 1..3) {
                    if (attempt > 1) {
                        Thread.sleep(300) // 每次重试间隔 300ms
                    }
                    LogUtils.d(TAG, "killForegroundApp: live detection attempt $attempt/3")
                    packageName = getForegroundAppPackageName()
                    if (packageName != null) {
                        LogUtils.i(TAG, "killForegroundApp: live detection succeeded on attempt $attempt: $packageName")
                        // 更新缓存供后续使用
                        cachedForegroundPackage = packageName
                        break
                    }
                }
            }

            LogUtils.i(TAG, "killForegroundApp: final package=$packageName")
            if (packageName == null) {
                LogUtils.w(TAG, "killForegroundApp: could not determine foreground app after all retries")
                return false
            }
            if (packageName == OUR_PACKAGE) {
                LogUtils.i(TAG, "killForegroundApp: skipping kill for our own app")
                return false
            }

            LogUtils.i(TAG, "killForegroundApp: executing am force-stop $packageName")
            val output = executeShellCommand("am force-stop $packageName")
            LogUtils.i(TAG, "killForegroundApp: result='${output?.trim()}'")

            // 尝试移除最近任务卡片
            var taskId = cachedForegroundTaskId
            if (taskId == null) {
                // 缓存中没有 task ID，从新 dump 中反查（可能检测时用的是方法1/focus）
                LogUtils.d(TAG, "killForegroundApp: taskId not cached, trying live lookup")
                val freshDump = executeShellCommand("dumpsys activity recents 2>&1")
                taskId = findTaskIdInRecents(freshDump, packageName)
                if (taskId != null) {
                    // 成功找到 task ID，但注意 package 已经被 force-stop 了
                    // 所以任务可能已经被系统自动从 recents 中移除
                    LogUtils.d(TAG, "killForegroundApp: found taskId=$taskId from live dump")
                } else {
                    LogUtils.d(TAG, "killForegroundApp: taskId still null after live lookup")
                }
            }
            if (taskId != null) {
                removeTaskFromRecents(taskId)
            }

            // 执行成功后重置命令缓存，下次检测会重新测试
            resetCommandCache()
            // 清除缓存的包名，下次检测周期会重新获取
            cachedForegroundPackage = null
            cachedForegroundTaskId = null
            LogUtils.i(TAG, "=== killForegroundApp completed ===")
            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "killForegroundApp: failed", e)
            false
        }
    }
}

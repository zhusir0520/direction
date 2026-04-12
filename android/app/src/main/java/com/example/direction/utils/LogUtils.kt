package com.example.direction.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 日志工具类
 * 支持将日志同时输出到Logcat和文件，方便在设备上定位问题
 */
object LogUtils {

    private const val TAG = "LogUtils"

    // 日志级别
    enum class Level(val value: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR)
    }

    // 默认配置
    private const val DEFAULT_MAX_FILE_SIZE = 2 * 1024 * 1024L // 2MB
    private const val DEFAULT_MAX_BACKUP_FILES = 3
    private const val LOG_FILE_NAME = "direction_log.txt"
    private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"

    // 状态变量
    private var isInitialized = false
    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var maxFileSize = DEFAULT_MAX_FILE_SIZE
    private var maxBackupFiles = DEFAULT_MAX_BACKUP_FILES
    private var minLogLevel = Level.DEBUG

    // 线程安全
    private val lock = ReentrantLock()

    /**
     * 初始化日志工具
     * @param context 应用上下文
     * @param maxFileSizeBytes 最大日志文件大小（字节），默认2MB
     * @param maxBackupFiles 最大备份文件数，默认3
     * @param minLevel 最小日志级别，低于此级别的日志不会被写入文件
     */
    fun initialize(
        context: Context,
        maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE,
        maxBackupFiles: Int = DEFAULT_MAX_BACKUP_FILES,
        minLevel: Level = Level.DEBUG
    ) {
        lock.withLock {
            if (isInitialized) {
                Log.w(TAG, "LogUtils已经初始化过")
                return
            }

            try {
                // 设置参数
                maxFileSize = maxFileSizeBytes
                this.maxBackupFiles = maxBackupFiles
                minLogLevel = minLevel

                // 获取日志目录（应用私有目录）
                logDir = context.filesDir
                currentLogFile = File(logDir, LOG_FILE_NAME)

                // 创建初始日志文件
                if (!currentLogFile!!.exists()) {
                    currentLogFile!!.createNewFile()
                    writeToFile("=== 日志文件创建于 ${getCurrentTime()} ===\n")
                    writeToFile("=== 应用: ${context.packageName} ===\n")
                    writeToFile("=== 设备: ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE}) ===\n\n")
                }

                isInitialized = true
                Log.i(TAG, "LogUtils初始化完成，日志文件: ${currentLogFile!!.absolutePath}")
                i(TAG, "LogUtils初始化完成，最小日志级别: $minLevel")
            } catch (e: Exception) {
                Log.e(TAG, "LogUtils初始化失败", e)
            }
        }
    }

    /**
     * 记录VERBOSE级别日志
     */
    fun v(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.VERBOSE, tag, message, throwable)
    }

    /**
     * 记录DEBUG级别日志
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG, tag, message, throwable)
    }

    /**
     * 记录INFO级别日志
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.INFO, tag, message, throwable)
    }

    /**
     * 记录WARN级别日志
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    /**
     * 记录ERROR级别日志
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * 核心日志方法
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        // 输出到Logcat
        when (level) {
            Level.VERBOSE -> if (throwable != null) Log.v(tag, message, throwable) else Log.v(tag, message)
            Level.DEBUG -> if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
            Level.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
            Level.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            Level.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }

        // 输出到文件（如果已初始化且级别足够）
        if (isInitialized && level.value >= minLogLevel.value) {
            writeLogToFile(level, tag, message, throwable)
        }
    }

    /**
     * 将日志写入文件
     */
    private fun writeLogToFile(level: Level, tag: String, message: String, throwable: Throwable?) {
        lock.withLock {
            try {
                val logEntry = buildLogEntry(level, tag, message, throwable)
                writeToFile(logEntry)

                // 检查文件大小，如果需要则滚动日志
                checkAndRotateLogFile()
            } catch (e: Exception) {
                // 文件写入失败时只记录到Logcat，避免循环
                Log.e(TAG, "写入日志文件失败", e)
            }
        }
    }

    /**
     * 构建日志条目
     */
    private fun buildLogEntry(level: Level, tag: String, message: String, throwable: Throwable?): String {
        val timestamp = getCurrentTime()
        val levelStr = level.name.padEnd(5)

        val entry = StringBuilder()
        entry.append("$timestamp $levelStr [$tag] $message")

        if (throwable != null) {
            entry.append("\n").append(Log.getStackTraceString(throwable))
        }
        entry.append("\n")

        return entry.toString()
    }

    /**
     * 写入文件（追加模式）
     */
    private fun writeToFile(content: String) {
        val file = currentLogFile ?: return

        FileOutputStream(file, true).use { fos ->
            PrintWriter(fos).use { writer ->
                writer.print(content)
                writer.flush()
            }
        }
    }

    /**
     * 检查并滚动日志文件
     */
    private fun checkAndRotateLogFile() {
        val file = currentLogFile ?: return

        if (file.length() >= maxFileSize) {
            try {
                rotateLogFiles()
            } catch (e: Exception) {
                Log.e(TAG, "滚动日志文件失败", e)
            }
        }
    }

    /**
     * 滚动日志文件
     */
    private fun rotateLogFiles() {
        val logDir = this.logDir ?: return
        val mainFile = currentLogFile ?: return

        // 删除最旧的备份文件
        val oldestBackup = File(logDir, "$LOG_FILE_NAME.$maxBackupFiles")
        if (oldestBackup.exists()) {
            oldestBackup.delete()
        }

        // 重命名现有的备份文件
        for (i in maxBackupFiles - 1 downTo 1) {
            val oldFile = File(logDir, "$LOG_FILE_NAME.$i")
            val newFile = File(logDir, "$LOG_FILE_NAME.${i + 1}")

            if (oldFile.exists()) {
                oldFile.renameTo(newFile)
            }
        }

        // 重命名当前日志文件为第一个备份
        val firstBackup = File(logDir, "$LOG_FILE_NAME.1")
        if (mainFile.exists()) {
            mainFile.renameTo(firstBackup)
        }

        // 创建新的日志文件
        mainFile.createNewFile()
        writeToFile("=== 日志文件滚动于 ${getCurrentTime()} ===\n\n")

        Log.i(TAG, "日志文件已滚动，新文件创建")
    }

    /**
     * 获取当前时间字符串
     */
    private fun getCurrentTime(): String {
        val formatter = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
        return formatter.format(Date())
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String? {
        return currentLogFile?.absolutePath
    }

    /**
     * 清空日志文件
     */
    fun clearLogs() {
        lock.withLock {
            try {
                val file = currentLogFile ?: return

                if (file.exists()) {
                    file.delete()
                    file.createNewFile()
                    writeToFile("=== 日志已清空于 ${getCurrentTime()} ===\n\n")
                    Log.i(TAG, "日志文件已清空")
                }
            } catch (e: Exception) {
                Log.e(TAG, "清空日志文件失败", e)
            }
        }
    }

    /**
     * 获取日志文件内容（用于调试）
     */
    fun getLogContent(maxLines: Int = 1000): String {
        lock.withLock {
            val file = currentLogFile ?: return "日志文件未初始化"

            return try {
                if (!file.exists()) {
                    "日志文件不存在"
                } else {
                    val lines = file.readLines()
                    if (lines.size <= maxLines) {
                        lines.joinToString("\n")
                    } else {
                        val start = lines.size - maxLines
                        "...（显示最近${maxLines}行，共${lines.size}行）...\n" +
                        lines.subList(start, lines.size).joinToString("\n")
                    }
                }
            } catch (e: Exception) {
                "读取日志文件失败: ${e.message}"
            }
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized
}
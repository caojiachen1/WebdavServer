package com.hqsrawmelon.webdavserver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogManager(private val context: Context) {
    private val logFile = File(context.filesDir, "webdav_server.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    suspend fun writeLog(level: String, tag: String, message: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $level/$tag: $message\n"
            logFile.appendText(logEntry)
            
            // Check file size and rotate if necessary
            checkAndRotateLog()
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to write log", e)
        }
    }
    
    suspend fun readLogs(): String = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "暂无日志记录"
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to read logs", e)
            "读取日志失败: ${e.message}"
        }
    }
    
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to clear logs", e)
            throw e
        }
    }
    
    suspend fun getLogSize(): String = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                val sizeBytes = logFile.length()
                when {
                    sizeBytes < 1024 -> "${sizeBytes}B"
                    sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
                    else -> "${sizeBytes / (1024 * 1024)}MB"
                }
            } else {
                "0B"
            }
        } catch (e: Exception) {
            "未知"
        }
    }
    
    private suspend fun checkAndRotateLog() = withContext(Dispatchers.IO) {
        try {
            val maxSizeBytes = 10 * 1024 * 1024 // 10MB default
            if (logFile.exists() && logFile.length() > maxSizeBytes) {
                val backupFile = File(context.filesDir, "webdav_server.log.old")
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                logFile.renameTo(backupFile)
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to rotate log", e)
        }
    }
    
    fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
        // Can be extended to write to file asynchronously
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        // Can be extended to write to file asynchronously
    }
    
    fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
        // Can be extended to write to file asynchronously
    }
    
    fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
        // Can be extended to write to file asynchronously
    }
}

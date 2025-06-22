package com.hqsrawmelon.webdavserver

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class LogManager(
    private val context: Context,
) {
    // Secondary constructor for SettingsManager integration
    constructor(settingsManager: SettingsManager) : this(settingsManager.context) {
        this.settingsManager = settingsManager
    }

    private var settingsManager: SettingsManager? = null
    private val logFile = File(context.getExternalFilesDir("logs"), "webdav.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    init {
        logFile.parentFile?.mkdirs()
    }

    private fun shouldLog(level: String): Boolean {
        val settingsManager = this.settingsManager ?: return true

        return runBlocking {
            val enableLogging = settingsManager.enableLogging.first()
            if (!enableLogging) return@runBlocking false

            val currentLogLevel = settingsManager.logLevel.first()
            val levelPriority =
                mapOf(
                    "DEBUG" to 0,
                    "INFO" to 1,
                    "WARN" to 2,
                    "ERROR" to 3,
                )

            (levelPriority[level] ?: 0) >= (levelPriority[currentLogLevel] ?: 1)
        }
    }

    private fun rotateLogIfNeeded() {
        val settingsManager = this.settingsManager ?: return

        runBlocking {
            val maxLogSize = settingsManager.maxLogSize.first() * 1024 * 1024 // Convert MB to bytes

            if (logFile.exists() && logFile.length() > maxLogSize) {
                val backupFile = File(logFile.parent, "webdav_backup.log")
                if (backupFile.exists()) backupFile.delete()
                logFile.renameTo(backupFile)
            }
        }
    }

    private fun writeLog(
        level: String,
        tag: String,
        message: String,
    ) {
        if (!shouldLog(level)) return

        try {
            rotateLogIfNeeded()

            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $level/$tag: $message\n"

            FileWriter(logFile, true).use { writer ->
                writer.write(logEntry)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logDebug(
        tag: String,
        message: String,
    ) = writeLog("DEBUG", tag, message)

    fun logInfo(
        tag: String,
        message: String,
    ) = writeLog("INFO", tag, message)

    fun logWarn(
        tag: String,
        message: String,
    ) = writeLog("WARN", tag, message)

    fun logError(
        tag: String,
        message: String,
    ) = writeLog("ERROR", tag, message)

    fun readLogs(): String =
        try {
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "No logs available"
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }

    fun clearLogs() {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
            val backupFile = File(logFile.parent, "webdav_backup.log")
            if (backupFile.exists()) {
                backupFile.delete()
            }
        } catch (e: Exception) {
            throw Exception("Failed to clear logs: ${e.message}")
        }
    }

    fun getLogSize(): String =
        try {
            val totalSize =
                (if (logFile.exists()) logFile.length() else 0) +
                    (
                        File(logFile.parent, "webdav_backup.log").let {
                            if (it.exists()) it.length() else 0
                        }
                    )

            when {
                totalSize > 1024 * 1024 -> "${totalSize / (1024 * 1024)}MB"
                totalSize > 1024 -> "${totalSize / 1024}KB"
                else -> "${totalSize}B"
            }
        } catch (e: Exception) {
            "0B"
        }
}

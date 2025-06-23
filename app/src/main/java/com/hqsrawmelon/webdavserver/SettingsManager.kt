package com.hqsrawmelon.webdavserver

import android.content.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import org.json.JSONObject
import com.hqsrawmelon.webdavserver.utils.PerformanceUtils
import com.hqsrawmelon.webdavserver.utils.ResourceManager

/**
 * 优化的设置管理器
 * 减少 SharedPreferences 访问次数，批量更新设置
 */
class SettingsManager(
    context: Context,
) {
    val context = context
    private val prefs: SharedPreferences = context.getSharedPreferences("webdav_settings", Context.MODE_PRIVATE)
    private val logManager = LogManager(context)
    
    // 批量更新状态管理
    private val pendingUpdates = mutableMapOf<String, Any>()
    private val updateDebouncer = PerformanceUtils.Debouncer(500L) // 500ms 延迟批量提交
    private val coroutineScope = ResourceManager.createScope("SettingsManager")

    init {
        // Register cleanup for SettingsManager
        ResourceManager.addDisposable("SettingsManager") {
            coroutineScope.cancel()
        }
    }

    // Authentication settings
    private val _username = MutableStateFlow(prefs.getString("username", "admin") ?: "admin")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(prefs.getString("password", "123456") ?: "123456")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _allowAnonymous = MutableStateFlow(prefs.getBoolean("allow_anonymous", false))
    val allowAnonymous: StateFlow<Boolean> = _allowAnonymous.asStateFlow()

    // Server configuration
    private val _serverPort = MutableStateFlow(prefs.getInt("server_port", 8080))
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _enableHttps = MutableStateFlow(prefs.getBoolean("enable_https", false))
    val enableHttps: StateFlow<Boolean> = _enableHttps.asStateFlow()

    // Advanced settings
    private val _connectionTimeout = MutableStateFlow(prefs.getInt("connection_timeout", 30))
    val connectionTimeout: StateFlow<Int> = _connectionTimeout.asStateFlow()

    private val _maxConnections = MutableStateFlow(prefs.getInt("max_connections", 10))
    val maxConnections: StateFlow<Int> = _maxConnections.asStateFlow()

    private val _bufferSize = MutableStateFlow(prefs.getInt("buffer_size", 8192))
    val bufferSize: StateFlow<Int> = _bufferSize.asStateFlow()

    private val _enableCors = MutableStateFlow(prefs.getBoolean("enable_cors", true))
    val enableCors: StateFlow<Boolean> = _enableCors.asStateFlow()

    private val _enableCompression = MutableStateFlow(prefs.getBoolean("enable_compression", false))
    val enableCompression: StateFlow<Boolean> = _enableCompression.asStateFlow()

    // Security settings
    private val _enableIpWhitelist = MutableStateFlow(prefs.getBoolean("enable_ip_whitelist", false))
    val enableIpWhitelist: StateFlow<Boolean> = _enableIpWhitelist.asStateFlow()

    private val _ipWhitelist = MutableStateFlow(prefs.getString("ip_whitelist", "192.168.1.0/24") ?: "192.168.1.0/24")
    val ipWhitelist: StateFlow<String> = _ipWhitelist.asStateFlow()

    private val _maxFailedAttempts = MutableStateFlow(prefs.getInt("max_failed_attempts", 5))
    val maxFailedAttempts: StateFlow<Int> = _maxFailedAttempts.asStateFlow()

    private val _blockDuration = MutableStateFlow(prefs.getInt("block_duration", 300))
    val blockDuration: StateFlow<Int> = _blockDuration.asStateFlow()

    // Logging settings
    private val _enableLogging = MutableStateFlow(prefs.getBoolean("enable_logging", true))
    val enableLogging: StateFlow<Boolean> = _enableLogging.asStateFlow()

    private val _logLevel = MutableStateFlow(prefs.getString("log_level", "INFO") ?: "INFO")
    val logLevel: StateFlow<String> = _logLevel.asStateFlow()

    private val _maxLogSize = MutableStateFlow(prefs.getInt("max_log_size", 10))
    val maxLogSize: StateFlow<Int> = _maxLogSize.asStateFlow()

    // Background service settings
    private val _enableBackgroundService = MutableStateFlow(prefs.getBoolean("enable_background_service", true))
    val enableBackgroundService: StateFlow<Boolean> = _enableBackgroundService.asStateFlow()

    private val _autoStartOnBoot = MutableStateFlow(prefs.getBoolean("auto_start_on_boot", false))
    val autoStartOnBoot: StateFlow<Boolean> = _autoStartOnBoot.asStateFlow()

    private val _showNotificationControls = MutableStateFlow(prefs.getBoolean("show_notification_controls", true))
    val showNotificationControls: StateFlow<Boolean> = _showNotificationControls.asStateFlow()

    /**
     * 批量更新方法 - 优化性能
     */
    private fun scheduleUpdate(key: String, value: Any) {
        pendingUpdates[key] = value
        updateDebouncer.debounce(coroutineScope) {
            commitPendingUpdates()
        }
    }
    
    private suspend fun commitPendingUpdates() {
        if (pendingUpdates.isEmpty()) return
        
        try {
            val editor = prefs.edit()
            pendingUpdates.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            
            // 使用 apply() 而不是 commit() 来异步保存
            editor.apply()
            
            // 记录批量更新日志
            if (_enableLogging.value) {
                logManager.logInfo("Settings", "Batch updated ${pendingUpdates.size} settings")
            }
            
            pendingUpdates.clear()
        } catch (e: Exception) {
            if (_enableLogging.value) {
                logManager.logError("Settings", "Failed to commit settings: ${e.message}")
            }
        }
    }

    // Optimized update methods
    fun updateUsername(value: String) {
        _username.value = value
        scheduleUpdate("username", value)
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Username updated")
        }
    }

    fun updatePassword(value: String) {
        _password.value = value
        scheduleUpdate("password", value)
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Password updated")
        }
    }

    fun updateAllowAnonymous(value: Boolean) {
        _allowAnonymous.value = value
        scheduleUpdate("allow_anonymous", value)
    }

    fun updateServerPort(value: Int) {
        _serverPort.value = value
        scheduleUpdate("server_port", value)
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Server port updated to $value")
        }
    }

    fun updateEnableHttps(value: Boolean) {
        _enableHttps.value = value
        scheduleUpdate("enable_https", value)
    }

    fun updateConnectionTimeout(value: Int) {
        _connectionTimeout.value = value
        scheduleUpdate("connection_timeout", value)
    }

    fun updateMaxConnections(value: Int) {
        _maxConnections.value = value
        scheduleUpdate("max_connections", value)
    }

    fun updateBufferSize(value: Int) {
        _bufferSize.value = value
        scheduleUpdate("buffer_size", value)
    }

    fun updateEnableCors(value: Boolean) {
        _enableCors.value = value
        scheduleUpdate("enable_cors", value)
    }

    fun updateEnableCompression(value: Boolean) {
        _enableCompression.value = value
        scheduleUpdate("enable_compression", value)
    }

    fun updateEnableIpWhitelist(value: Boolean) {
        _enableIpWhitelist.value = value
        scheduleUpdate("enable_ip_whitelist", value)
    }

    fun updateIpWhitelist(value: String) {
        _ipWhitelist.value = value
        scheduleUpdate("ip_whitelist", value)
    }

    fun updateMaxFailedAttempts(value: Int) {
        _maxFailedAttempts.value = value
        scheduleUpdate("max_failed_attempts", value)
    }

    fun updateBlockDuration(value: Int) {
        _blockDuration.value = value
        scheduleUpdate("block_duration", value)
    }

    fun updateEnableLogging(value: Boolean) {
        _enableLogging.value = value
        scheduleUpdate("enable_logging", value)
    }

    fun updateLogLevel(value: String) {
        _logLevel.value = value
        scheduleUpdate("log_level", value)
    }

    fun updateMaxLogSize(value: Int) {
        _maxLogSize.value = value
        scheduleUpdate("max_log_size", value)
    }

    // Background service settings update methods
    fun updateEnableBackgroundService(value: Boolean) {
        _enableBackgroundService.value = value
        scheduleUpdate("enable_background_service", value)
    }

    fun updateAutoStartOnBoot(value: Boolean) {
        _autoStartOnBoot.value = value
        scheduleUpdate("auto_start_on_boot", value)
    }

    fun updateShowNotificationControls(value: Boolean) {
        _showNotificationControls.value = value
        scheduleUpdate("show_notification_controls", value)
    }

    /**
     * 批量更新多个设置 - 性能优化
     */
    fun batchUpdate(updates: Map<String, Any>) {
        coroutineScope.launch {
            try {
                val editor = prefs.edit()
                
                updates.forEach { (key, value) ->
                    // 更新内存中的状态
                    updateInMemoryState(key, value)
                    
                    // 准备 SharedPreferences 更新
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Long -> editor.putLong(key, value)
                    }
                }
                
                editor.apply()
                
                if (_enableLogging.value) {
                    logManager.logInfo("Settings", "Batch updated ${updates.size} settings")
                }
            } catch (e: Exception) {
                if (_enableLogging.value) {
                    logManager.logError("Settings", "Batch update failed: ${e.message}")
                }
            }
        }
    }
    
    private fun updateInMemoryState(key: String, value: Any) {
        when (key) {
            "username" -> if (value is String) _username.value = value
            "password" -> if (value is String) _password.value = value
            "allow_anonymous" -> if (value is Boolean) _allowAnonymous.value = value
            "server_port" -> if (value is Int) _serverPort.value = value
            "enable_https" -> if (value is Boolean) _enableHttps.value = value
            "connection_timeout" -> if (value is Int) _connectionTimeout.value = value
            "max_connections" -> if (value is Int) _maxConnections.value = value
            "buffer_size" -> if (value is Int) _bufferSize.value = value
            "enable_cors" -> if (value is Boolean) _enableCors.value = value
            "enable_compression" -> if (value is Boolean) _enableCompression.value = value
            "enable_ip_whitelist" -> if (value is Boolean) _enableIpWhitelist.value = value
            "ip_whitelist" -> if (value is String) _ipWhitelist.value = value
            "max_failed_attempts" -> if (value is Int) _maxFailedAttempts.value = value
            "block_duration" -> if (value is Int) _blockDuration.value = value
            "enable_logging" -> if (value is Boolean) _enableLogging.value = value
            "log_level" -> if (value is String) _logLevel.value = value
            "max_log_size" -> if (value is Int) _maxLogSize.value = value
            "enable_background_service" -> if (value is Boolean) _enableBackgroundService.value = value
            "auto_start_on_boot" -> if (value is Boolean) _autoStartOnBoot.value = value
            "show_notification_controls" -> if (value is Boolean) _showNotificationControls.value = value
        }
    }

    /**
     * 导出设置 - 缓存优化
     */
    suspend fun exportSettings(): String = withContext(Dispatchers.IO) {
        val cacheKey = "export_settings_${System.currentTimeMillis() / 60000}" // 1分钟缓存
        PerformanceUtils.getCached(cacheKey) {
            try {
                val json = JSONObject().apply {
                    put("username", username.value)
                    put("password", password.value)
                    put("allow_anonymous", allowAnonymous.value)
                    put("server_port", serverPort.value)
                    put("enable_https", enableHttps.value)
                    put("connection_timeout", connectionTimeout.value)
                    put("max_connections", maxConnections.value)
                    put("buffer_size", bufferSize.value)
                    put("enable_cors", enableCors.value)
                    put("enable_compression", enableCompression.value)
                    put("enable_ip_whitelist", enableIpWhitelist.value)
                    put("ip_whitelist", ipWhitelist.value)
                    put("max_failed_attempts", maxFailedAttempts.value)
                    put("block_duration", blockDuration.value)
                    put("enable_logging", enableLogging.value)
                    put("log_level", logLevel.value)
                    put("max_log_size", maxLogSize.value)
                    put("export_timestamp", System.currentTimeMillis())
                    put("app_version", "1.0.0")
                }
                json.toString(2)
            } catch (e: Exception) {
                if (_enableLogging.value) {
                    logManager.logError("Settings", "Export failed: ${e.message}")
                }
                "{\"error\": \"导出失败: ${e.message}\"}"
            }
        }
    }

    /**
     * 导入设置 - 批量操作优化
     */
    suspend fun importSettings(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(jsonString)
            val updates = mutableMapOf<String, Any>()
            
            // 构建批量更新Map
            listOf(
                "username" to String::class,
                "password" to String::class,
                "allow_anonymous" to Boolean::class,
                "server_port" to Int::class,
                "enable_https" to Boolean::class,
                "connection_timeout" to Int::class,
                "max_connections" to Int::class,
                "buffer_size" to Int::class,
                "enable_cors" to Boolean::class,
                "enable_compression" to Boolean::class,
                "enable_ip_whitelist" to Boolean::class,
                "ip_whitelist" to String::class,
                "max_failed_attempts" to Int::class,
                "block_duration" to Int::class,
                "enable_logging" to Boolean::class,
                "log_level" to String::class,
                "max_log_size" to Int::class
            ).forEach { (key, type) ->
                if (json.has(key)) {
                    when (type) {
                        String::class -> updates[key] = json.getString(key)
                        Boolean::class -> updates[key] = json.getBoolean(key)
                        Int::class -> updates[key] = json.getInt(key)
                    }
                }
            }
            
            if (updates.isNotEmpty()) {
                batchUpdate(updates)
                if (_enableLogging.value) {
                    logManager.logInfo("Settings", "Imported ${updates.size} settings")
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            if (_enableLogging.value) {
                logManager.logError("Settings", "Import failed: ${e.message}")
            }
            false
        }
    }

    /**
     * 重置所有设置 - 批量操作（异步版本）
     */
    suspend fun resetAllSettings(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (_enableLogging.value) {
                logManager.logInfo("Settings", "Resetting all settings to defaults")
            }

            val defaultSettings = mapOf(
                "username" to "admin",
                "password" to "123456",
                "allow_anonymous" to false,
                "server_port" to 8080,
                "enable_https" to false,
                "connection_timeout" to 30,
                "max_connections" to 10,
                "buffer_size" to 8192,
                "enable_cors" to true,
                "enable_compression" to false,
                "enable_ip_whitelist" to false,
                "ip_whitelist" to "192.168.1.0/24",
                "max_failed_attempts" to 5,
                "block_duration" to 300,
                "enable_logging" to true,
                "log_level" to "INFO",
                "max_log_size" to 10
            )
            
            batchUpdate(defaultSettings)
            
            if (_enableLogging.value) {
                logManager.logInfo("Settings", "All settings reset to default")
            }
            true
        } catch (e: Exception) {
            if (_enableLogging.value) {
                logManager.logError("Settings", "Reset failed: ${e.message}")
            }
            false
        }
    }
    
    /**
     * 向后兼容的同步版本
     */
    fun resetToDefaults() {
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Resetting all settings to defaults")
        }

        prefs.edit().clear().apply()

        _username.value = "admin"
        _password.value = "123456"
        _allowAnonymous.value = false
        _serverPort.value = 8080
        _enableHttps.value = false
        _connectionTimeout.value = 30
        _maxConnections.value = 10
        _bufferSize.value = 8192
        _enableCors.value = true
        _enableCompression.value = false
        _enableIpWhitelist.value = false
        _ipWhitelist.value = "192.168.1.0/24"
        _maxFailedAttempts.value = 5
        _blockDuration.value = 300
        _enableLogging.value = true
        _logLevel.value = "INFO"
        _maxLogSize.value = 10
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        ResourceManager.cleanupScope("SettingsManager")
        PerformanceUtils.clearAllCache()
    }

    /**
     * 获取系统配置信息
     */
    fun getSystemConfig(): ResourceManager.SystemConfig {
        return ResourceManager.getSystemConfig(context)
    }

    /**
     * 获取内存使用情况
     */
    fun getMemoryInfo(): ResourceManager.MemoryInfo {
        return ResourceManager.checkMemoryUsage()
    }
}

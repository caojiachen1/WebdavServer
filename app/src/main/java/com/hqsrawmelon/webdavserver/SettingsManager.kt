package com.hqsrawmelon.webdavserver

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("webdav_settings", Context.MODE_PRIVATE)
    
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
    
    private val logManager = LogManager(context)
    
    // Update methods
    fun updateUsername(value: String) {
        _username.value = value
        prefs.edit().putString("username", value).apply()
        // Log settings change if logging is enabled
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Username updated")
        }
    }
    
    fun updatePassword(value: String) {
        _password.value = value
        prefs.edit().putString("password", value).apply()
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Password updated")
        }
    }
    
    fun updateAllowAnonymous(value: Boolean) {
        _allowAnonymous.value = value
        prefs.edit().putBoolean("allow_anonymous", value).apply()
    }
    
    fun updateServerPort(value: Int) {
        _serverPort.value = value
        prefs.edit().putInt("server_port", value).apply()
        if (_enableLogging.value) {
            logManager.logInfo("Settings", "Server port updated to $value")
        }
    }
    
    fun updateEnableHttps(value: Boolean) {
        _enableHttps.value = value
        prefs.edit().putBoolean("enable_https", value).apply()
    }
    
    fun updateConnectionTimeout(value: Int) {
        _connectionTimeout.value = value
        prefs.edit().putInt("connection_timeout", value).apply()
    }
    
    fun updateMaxConnections(value: Int) {
        _maxConnections.value = value
        prefs.edit().putInt("max_connections", value).apply()
    }
    
    fun updateBufferSize(value: Int) {
        _bufferSize.value = value
        prefs.edit().putInt("buffer_size", value).apply()
    }
    
    fun updateEnableCors(value: Boolean) {
        _enableCors.value = value
        prefs.edit().putBoolean("enable_cors", value).apply()
    }
    
    fun updateEnableCompression(value: Boolean) {
        _enableCompression.value = value
        prefs.edit().putBoolean("enable_compression", value).apply()
    }
    
    fun updateEnableIpWhitelist(value: Boolean) {
        _enableIpWhitelist.value = value
        prefs.edit().putBoolean("enable_ip_whitelist", value).apply()
    }
    
    fun updateIpWhitelist(value: String) {
        _ipWhitelist.value = value
        prefs.edit().putString("ip_whitelist", value).apply()
    }
    
    fun updateMaxFailedAttempts(value: Int) {
        _maxFailedAttempts.value = value
        prefs.edit().putInt("max_failed_attempts", value).apply()
    }
    
    fun updateBlockDuration(value: Int) {
        _blockDuration.value = value
        prefs.edit().putInt("block_duration", value).apply()
    }
    
    fun updateEnableLogging(value: Boolean) {
        _enableLogging.value = value
        prefs.edit().putBoolean("enable_logging", value).apply()
    }
    
    fun updateLogLevel(value: String) {
        _logLevel.value = value
        prefs.edit().putString("log_level", value).apply()
    }
    
    fun updateMaxLogSize(value: Int) {
        _maxLogSize.value = value
        prefs.edit().putInt("max_log_size", value).apply()
    }
    
    // Backup and restore functionality
    fun exportSettings(): String {
        val settings = JSONObject().apply {
            put("username", _username.value)
            put("password", _password.value)
            put("allow_anonymous", _allowAnonymous.value)
            put("server_port", _serverPort.value)
            put("enable_https", _enableHttps.value)
            put("connection_timeout", _connectionTimeout.value)
            put("max_connections", _maxConnections.value)
            put("buffer_size", _bufferSize.value)
            put("enable_cors", _enableCors.value)
            put("enable_compression", _enableCompression.value)
            put("enable_ip_whitelist", _enableIpWhitelist.value)
            put("ip_whitelist", _ipWhitelist.value)
            put("max_failed_attempts", _maxFailedAttempts.value)
            put("block_duration", _blockDuration.value)
            put("enable_logging", _enableLogging.value)
            put("log_level", _logLevel.value)
            put("max_log_size", _maxLogSize.value)
        }
        return settings.toString(2)
    }
    
    fun importSettings(jsonString: String): Boolean {
        try {
            val settings = JSONObject(jsonString)
            val editor = prefs.edit()
            
            settings.optString("username")?.let { if (it.isNotEmpty()) { _username.value = it; editor.putString("username", it) } }
            settings.optString("password")?.let { if (it.isNotEmpty()) { _password.value = it; editor.putString("password", it) } }
            if (settings.has("allow_anonymous")) { val value = settings.getBoolean("allow_anonymous"); _allowAnonymous.value = value; editor.putBoolean("allow_anonymous", value) }
            if (settings.has("server_port")) { val value = settings.getInt("server_port"); _serverPort.value = value; editor.putInt("server_port", value) }
            if (settings.has("enable_https")) { val value = settings.getBoolean("enable_https"); _enableHttps.value = value; editor.putBoolean("enable_https", value) }
            if (settings.has("connection_timeout")) { val value = settings.getInt("connection_timeout"); _connectionTimeout.value = value; editor.putInt("connection_timeout", value) }
            if (settings.has("max_connections")) { val value = settings.getInt("max_connections"); _maxConnections.value = value; editor.putInt("max_connections", value) }
            if (settings.has("buffer_size")) { val value = settings.getInt("buffer_size"); _bufferSize.value = value; editor.putInt("buffer_size", value) }
            if (settings.has("enable_cors")) { val value = settings.getBoolean("enable_cors"); _enableCors.value = value; editor.putBoolean("enable_cors", value) }
            if (settings.has("enable_compression")) { val value = settings.getBoolean("enable_compression"); _enableCompression.value = value; editor.putBoolean("enable_compression", value) }
            if (settings.has("enable_ip_whitelist")) { val value = settings.getBoolean("enable_ip_whitelist"); _enableIpWhitelist.value = value; editor.putBoolean("enable_ip_whitelist", value) }
            settings.optString("ip_whitelist")?.let { if (it.isNotEmpty()) { _ipWhitelist.value = it; editor.putString("ip_whitelist", it) } }
            if (settings.has("max_failed_attempts")) { val value = settings.getInt("max_failed_attempts"); _maxFailedAttempts.value = value; editor.putInt("max_failed_attempts", value) }
            if (settings.has("block_duration")) { val value = settings.getInt("block_duration"); _blockDuration.value = value; editor.putInt("block_duration", value) }
            if (settings.has("enable_logging")) { val value = settings.getBoolean("enable_logging"); _enableLogging.value = value; editor.putBoolean("enable_logging", value) }
            settings.optString("log_level")?.let { if (it.isNotEmpty()) { _logLevel.value = it; editor.putString("log_level", it) } }
            if (settings.has("max_log_size")) { val value = settings.getInt("max_log_size"); _maxLogSize.value = value; editor.putInt("max_log_size", value) }
            
            editor.apply()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
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
}

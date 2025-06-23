package com.hqsrawmelon.webdavserver.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hqsrawmelon.webdavserver.*
import com.hqsrawmelon.webdavserver.server.CustomWebDAVServer
import com.hqsrawmelon.webdavserver.utils.getLocalIpAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * WebDAV后台服务
 * 在前台运行WebDAV服务器，并显示持久通知
 */
class WebDAVService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "webdav_service_channel"
        
        // 服务动作
        const val ACTION_START_SERVER = "ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"
        const val ACTION_TOGGLE_SERVER = "ACTION_TOGGLE_SERVER"
        
        // 服务参数
        const val EXTRA_USERNAME = "EXTRA_USERNAME"
        const val EXTRA_PASSWORD = "EXTRA_PASSWORD"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_ALLOW_ANONYMOUS = "EXTRA_ALLOW_ANONYMOUS"
        
        // 服务状态
        var isServiceRunning = false
            private set
    }
    
    private var webServer: CustomWebDAVServer? = null
    private lateinit var settingsManager: SettingsManager
    private lateinit var logManager: LogManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 服务器配置
    private var serverUsername: String = ""
    private var serverPassword: String = ""
    private var serverPort: Int = 8080
    private var allowAnonymous: Boolean = false
    
    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        logManager = LogManager(settingsManager)
        createNotificationChannel()
        
        // 标记服务已启动
        isServiceRunning = true
        
        logManager.logInfo("Service", "WebDAV后台服务已创建")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                serverUsername = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                serverPassword = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                serverPort = intent.getIntExtra(EXTRA_PORT, 8080)
                allowAnonymous = intent.getBooleanExtra(EXTRA_ALLOW_ANONYMOUS, false)
                
                startWebDAVServer()
            }
            ACTION_STOP_SERVER -> {
                stopWebDAVServer()
                stopSelf()
            }
            ACTION_TOGGLE_SERVER -> {
                if (webServer != null) {
                    stopWebDAVServer()
                    stopSelf()
                } else {
                    startWebDAVServer()
                }
            }
        }
        
        // 返回START_STICKY以在系统杀死服务后重新启动
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // 不需要绑定
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopWebDAVServer()
        serviceScope.cancel()
        isServiceRunning = false
        logManager.logInfo("Service", "WebDAV后台服务已销毁")
    }
    
    /**
     * 创建通知渠道（Android 8.0+需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WebDAV服务器",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebDAV服务器后台运行通知"
                setShowBadge(false)
                // 禁用声音和振动
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 启动WebDAV服务器
     */
    private fun startWebDAVServer() {
        serviceScope.launch {
            try {
                val rootDir = File(getExternalFilesDir(null), "webdav")
                if (!rootDir.exists()) {
                    rootDir.mkdirs()
                }
                
                // 停止现有服务器
                webServer?.stop()
                
                // 创建新的服务器实例
                webServer = CustomWebDAVServer(
                    port = serverPort,
                    rootDir = rootDir,
                    username = serverUsername,
                    password = serverPassword,
                    allowAnonymous = allowAnonymous,
                    settingsManager = settingsManager
                )
                
                // 启动服务器
                val connectionTimeout = settingsManager.connectionTimeout.first() * 1000
                webServer?.start(connectionTimeout, false)
                
                // 更新通知为运行状态
                updateNotification(true)
                
                logManager.logInfo("Service", "WebDAV服务器已在后台启动，端口: $serverPort")
                
            } catch (e: Exception) {
                logManager.logError("Service", "启动WebDAV服务器失败: ${e.message}")
                
                // 更新通知为错误状态
                updateNotification(false, "启动失败: ${e.message}")
            }
        }
    }
    
    /**
     * 停止WebDAV服务器
     */
    private fun stopWebDAVServer() {
        webServer?.stop()
        webServer = null
        
        // 更新通知为停止状态
        updateNotification(false)
        
        logManager.logInfo("Service", "WebDAV服务器已停止")
    }
    
    /**
     * 更新前台通知
     */
    private fun updateNotification(isRunning: Boolean, errorMessage: String? = null) {
        serviceScope.launch {
            try {
                val ipAddress = getLocalIpAddress(this@WebDAVService)
                
                val title = if (isRunning) {
                    "WebDAV服务器运行中"
                } else if (errorMessage != null) {
                    "WebDAV服务器启动失败"
                } else {
                    "WebDAV服务器已停止"
                }
                
                val content = when {
                    isRunning && ipAddress.isNotEmpty() -> {
                        "访问地址: http://$ipAddress:$serverPort"
                    }
                    isRunning -> {
                        "正在运行，端口: $serverPort"
                    }
                    errorMessage != null -> {
                        errorMessage
                    }
                    else -> {
                        "点击启动服务器"
                    }
                }
                
                // 创建主Activity的Intent
                val openAppIntent = Intent(this@WebDAVService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openAppPendingIntent = PendingIntent.getActivity(
                    this@WebDAVService, 0, openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val notificationBuilder = NotificationCompat.Builder(this@WebDAVService, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(isRunning)
                    .setContentIntent(openAppPendingIntent)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                
                // 根据设置决定是否显示控制按钮
                try {
                    val showControls = settingsManager.showNotificationControls.first()
                    if (showControls) {
                        val toggleAction = if (isRunning) "停止服务器" else "启动服务器"
                        val toggleIntent = Intent(this@WebDAVService, WebDAVService::class.java).apply {
                            action = ACTION_TOGGLE_SERVER
                        }
                        val togglePendingIntent = PendingIntent.getService(
                            this@WebDAVService, 1, toggleIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        
                        notificationBuilder.addAction(
                            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                            toggleAction,
                            togglePendingIntent
                        )
                    }
                } catch (e: Exception) {
                    // 如果设置读取失败，默认不显示控制按钮
                }
                
                val notification = notificationBuilder.build()
                
                if (isRunning) {
                    // 启动前台服务
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    // 更新通知但不作为前台服务
                    if (hasNotificationPermission()) {
                        val notificationManager = NotificationManagerCompat.from(this@WebDAVService)
                        try {
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        } catch (e: SecurityException) {
                            // 如果没有通知权限，忽略通知更新
                            logManager.logWarn("Service", "No notification permission, skipping notification update")
                        }
                    }
                }
            } catch (e: Exception) {
                logManager.logError("Service", "Failed to update notification: ${e.message}")
            }
        }
    }
    
    /**
     * 检查是否有通知权限（Android 13+）
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13以下不需要运行时权限
        }
    }
}
package com.hqsrawmelon.webdavserver.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hqsrawmelon.webdavserver.service.WebDAVService

/**
 * 服务状态管理工具类
 */
object ServiceUtils {
    
    /**
     * 检查WebDAV服务是否正在运行
     */
    fun isWebDAVServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        @Suppress("DEPRECATION")
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        for (service in runningServices) {
            if (WebDAVService::class.java.name == service.service.className) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 启动WebDAV服务
     */
    fun startWebDAVService(
        context: Context,
        username: String,
        password: String,
        port: Int,
        allowAnonymous: Boolean
    ) {
        val intent = Intent(context, WebDAVService::class.java).apply {
            action = WebDAVService.ACTION_START_SERVER
            putExtra(WebDAVService.EXTRA_USERNAME, username)
            putExtra(WebDAVService.EXTRA_PASSWORD, password)
            putExtra(WebDAVService.EXTRA_PORT, port)
            putExtra(WebDAVService.EXTRA_ALLOW_ANONYMOUS, allowAnonymous)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    /**
     * 停止WebDAV服务
     */
    fun stopWebDAVService(context: Context) {
        val intent = Intent(context, WebDAVService::class.java).apply {
            action = WebDAVService.ACTION_STOP_SERVER
        }
        context.startService(intent)
    }
    
    /**
     * 切换WebDAV服务状态
     */
    fun toggleWebDAVService(context: Context) {
        val intent = Intent(context, WebDAVService::class.java).apply {
            action = WebDAVService.ACTION_TOGGLE_SERVER
        }
        context.startService(intent)
    }
}

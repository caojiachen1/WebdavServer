package com.hqsrawmelon.webdavserver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设置项数据类
 */
data class SettingsItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val hasDetails: Boolean = true
)

/**
 * 设置项列表定义
 */
object SettingsItems {
    val AUTHENTICATION = SettingsItem(
        id = "authentication",
        title = "认证设置",
        description = "配置用户名、密码和匿名访问",
        icon = Icons.Default.Security
    )
    
    val SERVER_CONFIG = SettingsItem(
        id = "server_config",
        title = "服务器配置",
        description = "端口、HTTPS和基本服务器设置",
        icon = Icons.Default.Settings
    )
    
    val ADVANCED = SettingsItem(
        id = "advanced",
        title = "高级设置",
        description = "性能优化和高级功能配置",
        icon = Icons.Default.Tune
    )
    
    val SECURITY = SettingsItem(
        id = "security",
        title = "安全设置",
        description = "IP白名单和访问控制",
        icon = Icons.Default.Shield
    )
    
    val LOGGING = SettingsItem(
        id = "logging",
        title = "日志设置",
        description = "日志级别和日志文件管理",
        icon = Icons.AutoMirrored.Filled.Article
    )
    
    val NETWORK_DIAGNOSTICS = SettingsItem(
        id = "network_diagnostics",
        title = "网络诊断",
        description = "网络状态检查和端口测试",
        icon = Icons.Default.NetworkCheck
    )
    
    val BACKUP_TOOLS = SettingsItem(
        id = "backup_tools",
        title = "备份与工具",
        description = "导入导出设置和重置选项",
        icon = Icons.Default.Build
    )
    
    val ABOUT = SettingsItem(
        id = "about",
        title = "关于",
        description = "应用信息和版本详情",
        icon = Icons.Default.Info,
        hasDetails = false
    )
    
    /**
     * 获取所有设置项
     */
    fun getAllItems(): List<SettingsItem> = listOf(
        AUTHENTICATION,
        SERVER_CONFIG,
        ADVANCED,
        SECURITY,
        LOGGING,
        NETWORK_DIAGNOSTICS,
        BACKUP_TOOLS,
        ABOUT
    )
}

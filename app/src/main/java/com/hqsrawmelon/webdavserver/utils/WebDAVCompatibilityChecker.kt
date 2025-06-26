package com.hqsrawmelon.webdavserver.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hqsrawmelon.webdavserver.LogManager
import com.hqsrawmelon.webdavserver.SettingsManager
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebDAV多平台兼容性检测工具
 * 支持Windows、macOS、Linux、iOS、Android等平台的WebDAV客户端兼容性检测
 */
class WebDAVCompatibilityChecker {
    
    /**
     * 兼容性级别枚举
     */
    enum class CompatibilityLevel {
        PERFECT,    // 完美兼容
        GOOD,       // 良好兼容
        PARTIAL,    // 部分兼容
        POOR        // 兼容性差
    }
    
    /**
     * 兼容性检测结果
     */
    data class CompatibilityResult(
        val level: CompatibilityLevel,
        val message: String,
        val details: List<String> = emptyList()
    )
    
    /**
     * 完整的兼容性检测结果
     */
    data class FullCompatibilityResult(
        val platform: String,
        val compatibilityResult: CompatibilityResult,
        val suggestion: String,
        val setupGuide: String,
        val recommendedClients: List<String> = emptyList()
    )
    
    /**
     * 服务器配置
     */
    data class ServerConfig(
        val port: Int,
        val useHttps: Boolean,
        val username: String?,
        val password: String?,
        val allowAnonymous: Boolean
    )
    
    /**
     * 检测所有平台的兼容性
     */
    fun checkAllPlatforms(serverConfig: ServerConfig): List<FullCompatibilityResult> {
        return listOf(
            checkWindowsCompatibility(serverConfig),
            checkMacOSCompatibility(serverConfig),
            checkLinuxCompatibility(serverConfig),
            checkIOSCompatibility(serverConfig),
            checkAndroidCompatibility(serverConfig),
            checkWebBrowserCompatibility(serverConfig),
            checkThirdPartyClientsCompatibility(serverConfig)
        )
    }
    
    /**
     * Windows兼容性检测
     */
    private fun checkWindowsCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val issues = mutableListOf<String>()
        val level = when {
            !serverConfig.useHttps && !serverConfig.allowAnonymous -> {
                issues.add("Windows需要HTTPS或特殊注册表配置才能使用HTTP Basic认证")
                CompatibilityLevel.PARTIAL
            }
            serverConfig.useHttps && !serverConfig.allowAnonymous -> {
                CompatibilityLevel.PERFECT
            }
            serverConfig.allowAnonymous -> {
                CompatibilityLevel.GOOD
            }
            else -> CompatibilityLevel.PARTIAL
        }
        
        val message = when (level) {
            CompatibilityLevel.PERFECT -> "完美兼容Windows WebDAV客户端"
            CompatibilityLevel.GOOD -> "与Windows WebDAV客户端兼容良好"
            CompatibilityLevel.PARTIAL -> "需要额外配置才能在Windows上正常工作"
            CompatibilityLevel.POOR -> "在Windows上可能遇到连接问题"
        }
        
        val suggestion = when {
            !serverConfig.useHttps && !serverConfig.allowAnonymous -> 
                "建议启用HTTPS或修改Windows注册表以支持HTTP Basic认证"
            else -> "配置良好，Windows用户可以直接连接"
        }
        
        val setupGuide = generateWindowsSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "Windows",
            compatibilityResult = CompatibilityResult(level, message, issues),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("Windows资源管理器", "WinSCP", "CyberDuck", "CarotDAV")
        )
    }
    
    /**
     * macOS兼容性检测
     */
    private fun checkMacOSCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = when {
            serverConfig.useHttps -> CompatibilityLevel.PERFECT
            !serverConfig.useHttps -> CompatibilityLevel.GOOD
            else -> CompatibilityLevel.GOOD
        }
        
        val message = when (level) {
            CompatibilityLevel.PERFECT -> "与macOS Finder完美兼容"
            CompatibilityLevel.GOOD -> "与macOS Finder兼容良好"
            else -> "基本兼容macOS系统"
        }
        
        val suggestion = if (serverConfig.useHttps) {
            "配置完美，macOS用户可以直接使用Finder连接"
        } else {
            "建议启用HTTPS以获得最佳安全性"
        }
        
        val setupGuide = generateMacOSSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "macOS",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("Finder", "Transmit", "ForkLift", "Commander One")
        )
    }
    
    /**
     * Linux兼容性检测
     */
    private fun checkLinuxCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = CompatibilityLevel.PERFECT // Linux对WebDAV支持很好
        
        val message = "Linux系统对WebDAV协议支持完善"
        val suggestion = "Linux用户可以使用多种方式连接WebDAV服务器"
        val setupGuide = generateLinuxSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "Linux",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("davfs2", "Nautilus", "Dolphin", "Thunar", "curl", "cadaver")
        )
    }
    
    /**
     * iOS兼容性检测
     */
    private fun checkIOSCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = when {
            serverConfig.useHttps -> CompatibilityLevel.PERFECT
            !serverConfig.useHttps -> CompatibilityLevel.GOOD
            else -> CompatibilityLevel.GOOD
        }
        
        val message = when (level) {
            CompatibilityLevel.PERFECT -> "与iOS Files应用完美兼容"
            CompatibilityLevel.GOOD -> "与iOS Files应用兼容良好"
            else -> "基本兼容iOS系统"
        }
        
        val suggestion = if (serverConfig.useHttps) {
            "配置完美，iOS用户可以直接使用Files应用连接"
        } else {
            "HTTP连接可能需要在设置中允许不安全连接"
        }
        
        val setupGuide = generateIOSSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "iOS",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("Files", "Documents by Readdle", "FileBrowser", "FE File Explorer")
        )
    }
    
    /**
     * Android兼容性检测
     */
    private fun checkAndroidCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = CompatibilityLevel.PERFECT // Android对WebDAV支持很好
        
        val message = "Android系统对WebDAV协议支持完善"
        val suggestion = "Android用户可以使用多种WebDAV客户端应用"
        val setupGuide = generateAndroidSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "Android",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("Solid Explorer", "FX File Explorer", "Total Commander", "ES File Explorer", "X-plore")
        )
    }
    
    /**
     * Web浏览器兼容性检测
     */
    private fun checkWebBrowserCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = CompatibilityLevel.PARTIAL
        
        val message = "Web浏览器对WebDAV支持有限"
        val suggestion = "建议使用专门的WebDAV客户端而非浏览器"
        val setupGuide = generateWebBrowserSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "Web浏览器",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("WebDAV在线工具", "CloudMounter Online", "网页版文件管理器")
        )
    }
    
    /**
     * 第三方客户端兼容性检测
     */
    private fun checkThirdPartyClientsCompatibility(serverConfig: ServerConfig): FullCompatibilityResult {
        val level = CompatibilityLevel.PERFECT
        
        val message = "第三方WebDAV客户端通常有完善的协议支持"
        val suggestion = "推荐使用专业的WebDAV客户端以获得最佳体验"
        val setupGuide = generateThirdPartyClientsSetupGuide(serverConfig)
        
        return FullCompatibilityResult(
            platform = "第三方客户端",
            compatibilityResult = CompatibilityResult(level, message),
            suggestion = suggestion,
            setupGuide = setupGuide,
            recommendedClients = listOf("CyberDuck", "FileZilla", "WinSCP", "Transmit", "CloudMounter", "NetDrive", "WebDrive")
        )
    }
    
    // 设置指南生成方法
    private fun generateWindowsSetupGuide(serverConfig: ServerConfig): String {
        val serverUrl = "http://[手机IP]:${serverConfig.port}"
        val httpsNote = if (!serverConfig.useHttps) {
            "\n注意：由于使用HTTP连接，可能需要修改注册表：\n" +
            "1. 打开注册表编辑器(regedit)\n" +
            "2. 导航到 HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters\n" +
            "3. 修改 BasicAuthLevel 为 2\n" +
            "4. 重启计算机\n"
        } else ""
        
        return "Windows设置步骤：\n" +
                "1. 打开文件资源管理器\n" +
                "2. 右键点击\"此电脑\"，选择\"映射网络驱动器\"\n" +
                "3. 选择驱动器号（如Z:）\n" +
                "4. 输入地址：$serverUrl\n" +
                "5. ${if (serverConfig.allowAnonymous) "保持匿名连接" else "输入用户名和密码"}\n" +
                "6. 点击\"完成\"" + httpsNote
    }
    
    private fun generateMacOSSetupGuide(serverConfig: ServerConfig): String {
        val serverUrl = "${if (serverConfig.useHttps) "https" else "http"}://[手机IP]:${serverConfig.port}"
        
        return "macOS设置步骤：\n" +
                "1. 打开Finder\n" +
                "2. 按 Cmd+K 或选择菜单\"前往\" > \"连接服务器\"\n" +
                "3. 输入服务器地址：$serverUrl\n" +
                "4. 点击\"连接\"\n" +
                "5. ${if (serverConfig.allowAnonymous) "选择\"客人\"" else "输入用户名和密码"}\n" +
                "6. 服务器将出现在边栏中"
    }
    
    private fun generateLinuxSetupGuide(serverConfig: ServerConfig): String {
        val serverUrl = "${if (serverConfig.useHttps) "https" else "http"}://[手机IP]:${serverConfig.port}"
        
        return "Linux设置步骤：\n" +
                "方法1 - 使用davfs2：\n" +
                "1. 安装davfs2：sudo apt install davfs2\n" +
                "2. 创建挂载点：sudo mkdir /mnt/webdav\n" +
                "3. 挂载：sudo mount -t davfs $serverUrl /mnt/webdav\n" +
                "4. ${if (!serverConfig.allowAnonymous) "输入用户名和密码" else "直接回车使用匿名连接"}\n\n" +
                "方法2 - 使用文件管理器：\n" +
                "1. 打开Nautilus/Dolphin等文件管理器\n" +
                "2. 在地址栏输入：$serverUrl\n" +
                "3. ${if (!serverConfig.allowAnonymous) "输入认证信息" else "直接连接"}"
    }
    
    private fun generateIOSSetupGuide(serverConfig: ServerConfig): String {
        val serverUrl = "${if (serverConfig.useHttps) "https" else "http"}://[手机IP]:${serverConfig.port}"
        
        return "iOS设置步骤：\n" +
                "1. 打开\"文件\"应用\n" +
                "2. 点击右上角\"...\"\n" +
                "3. 选择\"连接服务器\"\n" +
                "4. 输入服务器地址：$serverUrl\n" +
                "5. 点击\"连接\"\n" +
                "6. ${if (serverConfig.allowAnonymous) "选择\"客人\"连接" else "输入用户名和密码"}\n" +
                "7. 服务器将显示在\"位置\"中"
    }
    
    private fun generateAndroidSetupGuide(serverConfig: ServerConfig): String {
        return "Android设置步骤（以Solid Explorer为例）：\n" +
                "1. 下载并安装Solid Explorer\n" +
                "2. 点击\"+\"添加云存储\n" +
                "3. 选择\"WebDAV\"\n" +
                "4. 输入以下信息：\n" +
                "   - 主机：[手机IP]\n" +
                "   - 端口：${serverConfig.port}\n" +
                "   - ${if (serverConfig.useHttps) "启用HTTPS" else "使用HTTP"}\n" +
                "   - ${if (!serverConfig.allowAnonymous) "用户名：${serverConfig.username}\n   - 密码：[输入密码]" else "启用匿名连接"}\n" +
                "5. 点击\"测试\"验证连接\n" +
                "6. 保存配置"
    }
    
    private fun generateWebBrowserSetupGuide(serverConfig: ServerConfig): String {
        val serverUrl = "${if (serverConfig.useHttps) "https" else "http"}://[手机IP]:${serverConfig.port}"
        
        return "Web浏览器访问限制：\n" +
                "1. 现代浏览器对WebDAV支持有限\n" +
                "2. 可以尝试访问：$serverUrl\n" +
                "3. 某些浏览器可能支持基本的文件浏览\n" +
                "4. 建议使用专门的WebDAV客户端或应用\n\n" +
                "替代方案：\n" +
                "- 使用在线WebDAV工具\n" +
                "- 安装浏览器扩展\n" +
                "- 使用支持WebDAV的在线文件管理器"
    }
    
    private fun generateThirdPartyClientsSetupGuide(serverConfig: ServerConfig): String {
        return "第三方客户端通用设置：\n" +
                "连接信息：\n" +
                "- 协议：WebDAV\n" +
                "- 服务器：[手机IP]\n" +
                "- 端口：${serverConfig.port}\n" +
                "- ${if (serverConfig.useHttps) "启用SSL/TLS" else "使用HTTP协议"}\n" +
                "- ${if (!serverConfig.allowAnonymous) "认证方式：Basic Auth\n- 用户名：${serverConfig.username}\n- 密码：[输入密码]" else "认证方式：匿名访问"}\n\n" +
                "推荐客户端：\n" +
                "- CyberDuck（跨平台，免费）\n" +
                "- Transmit（macOS，付费）\n" +
                "- WinSCP（Windows，免费）\n" +
                "- FileZilla（跨平台，免费，需Pro版本支持WebDAV）\n" +
                "- CloudMounter（跨平台，付费）"
    }
}



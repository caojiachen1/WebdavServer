package com.hqsrawmelon.webdavserver.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hqsrawmelon.webdavserver.LogManager
import com.hqsrawmelon.webdavserver.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 多平台WebDAV兼容性工具
 * 支持Windows、macOS、Linux、iOS、Android等设备
 */
class MultiPlatformWebDAVCompatibilityChecker(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val logManager: LogManager
) {
    
    /**
     * 全面的兼容性检测
     */
    suspend fun performFullCompatibilityCheck(serverUrl: String): FullCompatibilityResult {
        val results = mutableMapOf<String, CompatibilityResult>()
        
        // Windows兼容性检测
        results["Windows"] = checkWindowsCompatibility(serverUrl)
        
        // macOS兼容性检测
        results["macOS"] = checkMacOSCompatibility(serverUrl)
        
        // Linux兼容性检测
        results["Linux"] = checkLinuxCompatibility(serverUrl)
        
        // iOS兼容性检测
        results["iOS"] = checkIOSCompatibility(serverUrl)
        
        // Android兼容性检测
        results["Android"] = checkAndroidCompatibility(serverUrl)
        
        // 通用WebDAV客户端兼容性
        results["通用客户端"] = checkGenericWebDAVCompatibility(serverUrl)
        
        return FullCompatibilityResult(
            serverUrl = serverUrl,
            overallCompatible = results.values.all { it.isCompatible },
            platformResults = results,
            recommendations = generateRecommendations(results)
        )
    }
    
    /**
     * 检查macOS兼容性
     */
    private suspend fun checkMacOSCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("服务器未响应OPTIONS请求")
                suggestions.add("确保WebDAV服务器正在运行并可访问")
            } else {
                val headers = optionsResult.headers
                
                // macOS Finder对DAV头的要求
                if (!headers.containsKey("DAV") || !headers["DAV"]!!.contains("1")) {
                    issues.add("缺少DAV 1.0支持")
                    suggestions.add("添加DAV: 1,2响应头以支持macOS Finder")
                }
                
                // 检查OPTIONS响应中的Allow头
                val allowHeader = headers["Allow"]
                if (allowHeader.isNullOrEmpty() || !allowHeader.contains("PROPFIND")) {
                    issues.add("Allow头中缺少必要的WebDAV方法")
                    suggestions.add("确保Allow头包含PROPFIND, PROPPATCH, MKCOL等方法")
                }
            }
            
            // 检查根目录PROPFIND
            val propfindResult = checkPropfindRequest(serverUrl)
            if (!propfindResult.success) {
                issues.add("根目录PROPFIND请求失败")
                suggestions.add("确保根目录支持PROPFIND方法并返回正确的XML响应")
            }
            
        } catch (e: Exception) {
            issues.add("macOS兼容性检测失败: ${e.message}")
            suggestions.add("检查网络连接")
        }
        
        return CompatibilityResult(
            platform = "macOS",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getMacOSSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 检查Linux兼容性
     */
    private suspend fun checkLinuxCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("服务器连接失败")
                suggestions.add("检查防火墙和网络设置")
            } else {
                val headers = optionsResult.headers
                
                // Linux davfs2客户端要求
                if (!headers.containsKey("DAV")) {
                    issues.add("缺少DAV响应头")
                    suggestions.add("添加DAV响应头以支持davfs2")
                }
                
                // 检查基本认证支持
                val authHeader = headers["WWW-Authenticate"]
                if (!settingsManager.allowAnonymous.first() && authHeader.isNullOrEmpty()) {
                    suggestions.add("确保服务器正确处理基本认证")
                }
            }
            
        } catch (e: Exception) {
            issues.add("Linux兼容性检测失败: ${e.message}")
            suggestions.add("检查网络连接和davfs2安装")
        }
        
        return CompatibilityResult(
            platform = "Linux",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getLinuxSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 检查iOS兼容性
     */
    private suspend fun checkIOSCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("iOS设备无法连接服务器")
                suggestions.add("确保iOS设备与服务器在同一网络")
            } else {
                val headers = optionsResult.headers
                
                // iOS Files应用要求
                if (!headers.containsKey("DAV") || !headers["DAV"]!!.contains("1,2")) {
                    issues.add("DAV版本支持不完整")
                    suggestions.add("iOS Files应用需要DAV 1,2支持")
                }
                
                // 检查HTTPS建议（iOS偏好HTTPS）
                if (serverUrl.startsWith("http://")) {
                    suggestions.add("建议使用HTTPS以获得更好的iOS兼容性")
                }
            }
            
        } catch (e: Exception) {
            issues.add("iOS兼容性检测失败: ${e.message}")
            suggestions.add("检查网络连接")
        }
        
        return CompatibilityResult(
            platform = "iOS",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getIOSSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 检查Android兼容性
     */
    private suspend fun checkAndroidCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("Android设备连接失败")
                suggestions.add("检查网络权限和连接")
            } else {
                val headers = optionsResult.headers
                
                // Android WebDAV客户端要求
                if (!headers.containsKey("Allow") || !headers["Allow"]!!.contains("PUT")) {
                    issues.add("缺少PUT方法支持")
                    suggestions.add("Android WebDAV客户端需要完整的HTTP方法支持")
                }
                
                // CORS支持检查（如果通过浏览器访问）
                if (!headers.containsKey("Access-Control-Allow-Origin")) {
                    suggestions.add("考虑启用CORS以支持基于浏览器的Android应用")
                }
            }
            
        } catch (e: Exception) {
            issues.add("Android兼容性检测失败: ${e.message}")
            suggestions.add("检查网络权限")
        }
        
        return CompatibilityResult(
            platform = "Android",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getAndroidSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 检查通用WebDAV客户端兼容性
     */
    private suspend fun checkGenericWebDAVCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            // 基础连接测试
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("基础连接失败")
                suggestions.add("检查服务器是否运行和网络连接")
                return CompatibilityResult(
                    platform = "通用客户端",
                    isCompatible = false,
                    issues = issues,
                    suggestions = suggestions,
                    setupInstructions = emptyList()
                )
            }
            
            val headers = optionsResult.headers
            
            // 必需的WebDAV头检查
            val requiredHeaders = listOf("DAV", "Allow")
            requiredHeaders.forEach { header ->
                if (!headers.containsKey(header)) {
                    issues.add("缺少必需的响应头: $header")
                    suggestions.add("添加${header}响应头以提高兼容性")
                }
            }
            
            // WebDAV方法支持检查
            val allowHeader = headers["Allow"] ?: ""
            val requiredMethods = listOf("OPTIONS", "GET", "PUT", "DELETE", "PROPFIND", "MKCOL")
            requiredMethods.forEach { method ->
                if (!allowHeader.contains(method)) {
                    issues.add("缺少WebDAV方法支持: $method")
                    suggestions.add("确保支持${method}方法")
                }
            }
            
            // PROPFIND深度测试
            val propfindResult = checkPropfindRequest(serverUrl)
            if (!propfindResult.success) {
                issues.add("PROPFIND请求失败")
                suggestions.add("检查PROPFIND方法实现")
            }
            
            // PUT测试（如果可能）
            val putTestResult = testPutMethod(serverUrl)
            if (!putTestResult.success) {
                issues.add("PUT方法测试失败")
                suggestions.add("检查PUT方法实现和文件写入权限")
            }
            
        } catch (e: Exception) {
            issues.add("通用兼容性检测失败: ${e.message}")
            suggestions.add("检查服务器配置")
        }
        
        return CompatibilityResult(
            platform = "通用客户端",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getGenericSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 测试PUT方法
     */
    private suspend fun testPutMethod(serverUrl: String): RequestResult {
        return try {
            val testUrl = URL("$serverUrl/test_file_${System.currentTimeMillis()}.txt")
            val connection = testUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            // 添加认证头如果需要
            if (!settingsManager.allowAnonymous.first()) {
                val username = settingsManager.username.first()
                val password = settingsManager.password.first()
                val auth = "$username:$password"
                val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $encodedAuth")
            }
            
            connection.outputStream.use { 
                it.write("WebDAV compatibility test".toByteArray())
            }
            
            val responseCode = connection.responseCode
            
            // 清理测试文件
            if (responseCode in 200..299) {
                cleanupTestFile("$serverUrl/test_file_${System.currentTimeMillis()}.txt")
            }
            
            RequestResult(
                success = responseCode in 200..299,
                responseCode = responseCode,
                headers = connection.headerFields.mapValues { it.value.joinToString(", ") }
            )
        } catch (e: Exception) {
            RequestResult(success = false, responseCode = -1, headers = emptyMap())
        }
    }
    
    /**
     * 清理测试文件
     */
    private suspend fun cleanupTestFile(fileUrl: String) {
        try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (!settingsManager.allowAnonymous.first()) {
                val username = settingsManager.username.first()
                val password = settingsManager.password.first()
                val auth = "$username:$password"
                val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $encodedAuth")
            }
            
            connection.responseCode // 执行请求
        } catch (e: Exception) {
            // 忽略清理失败
        }
    }
    
    /**
     * 生成针对性建议
     */
    private fun generateRecommendations(results: Map<String, CompatibilityResult>): List<String> {
        val recommendations = mutableListOf<String>()
        
        // 分析常见问题
        val allIssues = results.values.flatMap { it.issues }
        val allSuggestions = results.values.flatMap { it.suggestions }
        
        if (allIssues.any { it.contains("DAV") }) {
            recommendations.add("建议确保所有WebDAV响应都包含正确的DAV头部")
        }
        
        if (allIssues.any { it.contains("认证") || it.contains("Authentication") }) {
            recommendations.add("检查认证配置，确保用户名密码正确")
        }
        
        // 根据建议生成推荐
        if (allSuggestions.any { it.contains("HTTPS") }) {
            recommendations.add("强烈建议启用HTTPS以提高安全性和兼容性")
        }
        
        if (allIssues.any { it.contains("网络") || it.contains("连接") }) {
            recommendations.add("检查网络连接和防火墙设置")
        }
        
        // 添加安全建议
        if (results.any { it.value.setupInstructions.any { instruction -> instruction.contains("http://") } }) {
            recommendations.add("考虑使用HTTPS以提高安全性和兼容性")
        }
        
        // 添加性能建议
        recommendations.add("建议增加服务器缓冲区大小以提高大文件传输性能")
        recommendations.add("启用Keep-Alive连接以提高多请求场景的性能")
        
        return recommendations.distinct()
    }
    
    /**
     * 检查WebDAV服务器的Windows兼容性
     */
    suspend fun checkWindowsCompatibility(serverUrl: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        try {
            // 检查服务器是否响应OPTIONS请求
            val optionsResult = checkOptionsRequest(serverUrl)
            if (!optionsResult.success) {
                issues.add("服务器未正确响应OPTIONS请求")
                suggestions.add("确保WebDAV服务器正在运行")
            } else {
                // 检查必要的响应头
                val headers = optionsResult.headers
                if (!headers.contains("MS-Author-Via")) {
                    issues.add("缺少MS-Author-Via响应头")
                    suggestions.add("服务器需要添加MS-Author-Via: DAV响应头")
                }
                
                if (!headers.contains("DAV")) {
                    issues.add("缺少DAV响应头")
                    suggestions.add("服务器需要添加DAV: 1,2响应头")
                }
                
                val davHeader = headers["DAV"]
                if (davHeader != null && !davHeader.contains("1,2")) {
                    issues.add("DAV响应头版本不完整")
                    suggestions.add("DAV响应头应包含版本1,2")
                }
            }
            
            // 检查PROPFIND请求
            val propfindResult = checkPropfindRequest(serverUrl)
            if (!propfindResult.success) {
                issues.add("PROPFIND请求失败")
                suggestions.add("确保服务器支持WebDAV PROPFIND方法")
            }
            
        } catch (e: Exception) {
            issues.add("连接服务器失败: ${e.message}")
            suggestions.add("检查网络连接和服务器地址")
        }
        
        return CompatibilityResult(
            platform = "通用客户端",
            isCompatible = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions,
            setupInstructions = getGenericSetupInstructions(serverUrl)
        )
    }
    
    /**
     * 生成Windows网络驱动器映射命令
     */
    suspend fun generateWindowsMappingCommands(serverUrl: String): WindowsMappingCommands {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        val cmdCommand = if (allowAnonymous) {
            "net use Z: $serverUrl"
        } else {
            "net use Z: $serverUrl /user:$username"
        }
        
        val powershellCommand = if (allowAnonymous) {
            "New-PSDrive -Name \"Z\" -PSProvider \"FileSystem\" -Root \"$serverUrl\""
        } else {
            "New-PSDrive -Name \"Z\" -PSProvider \"FileSystem\" -Root \"$serverUrl\" -Credential (Get-Credential)"
        }
        
        return WindowsMappingCommands(
            cmdCommand = cmdCommand,
            powershellCommand = powershellCommand,
            explorerSteps = generateExplorerSteps(serverUrl, username, allowAnonymous)
        )
    }
    
    /**
     * 生成Windows注册表修改说明（用于HTTP连接）
     */
    fun generateRegistryFix(): RegistryFix {
        return RegistryFix(
            warning = "警告：以下注册表修改仅适用于测试环境，生产环境请使用HTTPS",
            registryPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters",
            registryKey = "BasicAuthLevel",
            registryValue = "2",
            registryType = "DWORD",
            description = "允许HTTP基本认证连接到WebDAV服务器",
            rebootRequired = false
        )
    }
    
    /**
     * 生成故障排除指南
     */
    fun generateTroubleshootingGuide(): TroubleshootingGuide {
        return TroubleshootingGuide(
            commonIssues = listOf(
                TroubleshootingItem(
                    error = "错误代码 0x80070043",
                    cause = "网络路径未找到",
                    solutions = listOf(
                        "检查手机IP地址是否正确",
                        "确保手机和电脑在同一WiFi网络",
                        "检查Windows防火墙设置",
                        "尝试ping手机IP地址测试连通性"
                    )
                ),
                TroubleshootingItem(
                    error = "错误代码 0x800700035",
                    cause = "WebDAV客户端服务未运行",
                    solutions = listOf(
                        "在服务管理器中启动WebClient服务",
                        "将WebClient服务设置为自动启动",
                        "重启计算机"
                    )
                ),
                TroubleshootingItem(
                    error = "认证失败",
                    cause = "用户名或密码错误",
                    solutions = listOf(
                        "检查用户名和密码是否正确",
                        "确认是否启用了匿名访问",
                        "清除Windows凭据管理器中的缓存",
                        "尝试使用不同的URL格式"
                    )
                )
            ),
            serviceChecks = listOf(
                "确保WebClient服务正在运行",
                "检查网络连接",
                "验证防火墙设置",
                "确认服务器端口未被占用"
            )
        )
    }
    
    /**
     * 打开Windows WebDAV故障排除页面
     */
    fun openWindowsTroubleshootingPage() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("ms-settings:troubleshoot")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logManager.logError("WindowsCompat", "无法打开Windows故障排除页面: ${e.message}")
        }
    }
    
    private suspend fun checkOptionsRequest(serverUrl: String): RequestResult {
        return try {
            val url = URL(serverUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val headers = connection.headerFields.mapValues { it.value.joinToString(", ") }
            
            RequestResult(
                success = responseCode == 200,
                responseCode = responseCode,
                headers = headers
            )
        } catch (e: IOException) {
            RequestResult(success = false, responseCode = -1, headers = emptyMap())
        }
    }
    
    private suspend fun checkPropfindRequest(serverUrl: String): RequestResult {
        return try {
            val url = URL(serverUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "1")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val headers = connection.headerFields.mapValues { it.value.joinToString(", ") }
            
            RequestResult(
                success = responseCode in 200..299,
                responseCode = responseCode,
                headers = headers
            )
        } catch (e: IOException) {
            RequestResult(success = false, responseCode = -1, headers = emptyMap())
        }
    }
    
    private fun generateExplorerSteps(serverUrl: String, username: String, allowAnonymous: Boolean): List<String> {
        return listOf(
            "打开文件资源管理器",
            "右键点击\"此电脑\"",
            "选择\"映射网络驱动器\"",
            "选择一个驱动器号（如Z:）",
            "在\"文件夹\"中输入：$serverUrl",
            if (allowAnonymous) "保持\"使用其他凭据连接\"未勾选" else "勾选\"使用其他凭据连接\"",
            "点击\"完成\"",
            if (!allowAnonymous) "输入用户名：$username 和对应密码" else "",
            "等待连接完成"
        ).filter { it.isNotEmpty() }
    }
    
    /**
     * 获取macOS设置指南
     */
    private suspend fun getMacOSSetupInstructions(serverUrl: String): List<String> {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        return listOf(
            "=== macOS Finder 连接方法 ===",
            "1. 打开 Finder",
            "2. 按 Cmd+K 或选择菜单 前往 > 连接服务器",
            "3. 输入服务器地址: $serverUrl",
            "4. 点击 连接",
            if (!allowAnonymous) "5. 输入用户名: $username 和密码" else "5. 选择客人身份连接",
            "",
            "=== macOS 终端命令行方法 ===",
            "mkdir ~/webdav_mount",
            if (allowAnonymous) {
                "mount_webdav $serverUrl ~/webdav_mount"
            } else {
                "mount_webdav -u $username $serverUrl ~/webdav_mount"
            },
            "",
            "=== 第三方客户端推荐 ===",
            "• Cyberduck (免费)",
            "• Transmit (付费)",
            "• ForkLift (付费)"
        )
    }
    
    /**
     * 获取Linux设置指南
     */
    private suspend fun getLinuxSetupInstructions(serverUrl: String): List<String> {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        return listOf(
            "=== Ubuntu/Debian 安装davfs2 ===",
            "sudo apt update",
            "sudo apt install davfs2",
            "",
            "=== 挂载WebDAV ===",
            "mkdir ~/webdav_mount",
            if (allowAnonymous) {
                "sudo mount -t davfs $serverUrl ~/webdav_mount"
            } else {
                listOf(
                    "sudo mount -t davfs $serverUrl ~/webdav_mount",
                    "# 输入用户名: $username",
                    "# 输入密码: [您的密码]"
                ).joinToString("\n")
            },
            "",
            "=== CentOS/RHEL/Fedora ===",
            "sudo dnf install davfs2  # Fedora",
            "sudo yum install davfs2  # CentOS/RHEL",
            "",
            "=== 图形界面客户端 ===",
            "• Nautilus (GNOME)",
            "• Dolphin (KDE)",
            "• Thunar (XFCE)",
            "",
            "在文件管理器中输入: $serverUrl"
        )
    }
    
    /**
     * 获取iOS设置指南
     */
    private suspend fun getIOSSetupInstructions(serverUrl: String): List<String> {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        val instructions = mutableListOf<String>()
        instructions.addAll(listOf(
            "=== iOS Files 应用连接 ===",
            "1. 打开 Files 应用",
            "2. 点击右上角的 ... 菜单",
            "3. 选择 连接服务器",
            "4. 输入服务器地址: $serverUrl"
        ))
        
        if (!allowAnonymous) {
            instructions.add("5. 输入用户名: $username")
            instructions.add("6. 输入密码")
        } else {
            instructions.add("5. 选择匿名连接或留空用户名")
        }
        
        instructions.add("7. 点击 连接")
        instructions.add("")
        instructions.addAll(listOf(
            "=== 推荐的第三方应用 ===",
            "• FE File Explorer (免费)",
            "• Documents by Readdle (免费)",
            "• FileBrowser (付费)",
            "• WebDAV Navigator (付费)",
            "",
            "=== 注意事项 ===",
            "• iOS偏好HTTPS连接",
            "• 某些应用可能需要在URL末尾添加 /"
        ))
        
        if (!serverUrl.startsWith("https://")) {
            instructions.add("• 建议配置HTTPS以获得最佳兼容性")
        }
        
        return instructions
    }
    
    /**
     * 获取Android设置指南
     */
    private suspend fun getAndroidSetupInstructions(serverUrl: String): List<String> {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        return listOf(
            "=== 推荐Android应用 ===",
            "",
            "1. ES File Explorer (免费)",
            "   • 打开应用 → 网络 → WebDAV",
            "   • 服务器: $serverUrl",
            if (!allowAnonymous) "   • 用户名: $username, 密码: [您的密码]" else "   • 匿名连接",
            "",
            "2. Solid Explorer (付费)",
            "   • 添加连接 → WebDAV",
            "   • 主机: $serverUrl",
            if (!allowAnonymous) "   • 用户名: $username" else "   • 留空用户名密码",
            "",
            "3. FX File Explorer (免费/付费)",
            "   • 网络 → 添加连接 → WebDAV",
            "   • URL: $serverUrl",
            if (!allowAnonymous) "   • 认证信息: $username" else "   • 匿名访问",
            "",
            "4. Total Commander (免费插件)",
            "   • 安装WebDAV插件",
            "   • 网络邻居 → WebDAV",
            "",
            "=== 浏览器访问 ===",
            "在Chrome/Firefox中直接访问: $serverUrl",
            if (!allowAnonymous) "输入用户名: $username 和密码" else "直接浏览文件",
            "",
            "=== 开发者选项 ===",
            "使用curl命令测试:",
            if (allowAnonymous) {
                "curl -X PROPFIND $serverUrl"
            } else {
                "curl -X PROPFIND -u $username:[password] $serverUrl"
            }
        )
    }
    
    /**
     * 获取通用设置指南
     */
    private suspend fun getGenericSetupInstructions(serverUrl: String): List<String> {
        val username = settingsManager.username.first()
        val allowAnonymous = settingsManager.allowAnonymous.first()
        
        val configDetails = mutableListOf<String>().apply {
            add("=== 通用WebDAV客户端配置 ===")
            add("")
            add("服务器地址: $serverUrl")
            if (!allowAnonymous) {
                add("用户名: $username")
                add("密码: [在应用设置中查看]")
                add("认证方式: Basic Authentication")
            } else {
                add("认证: 匿名访问/无需认证")
            }
            add("协议: WebDAV")
            add("端口: ${serverUrl.substringAfterLast(":").substringBefore("/").ifEmpty { "8080" }}")
            add("")
            add("=== 支持的WebDAV方法 ===")
            add("• OPTIONS - 查询服务器能力")
            add("• GET - 下载文件")
            add("• PUT - 上传文件")
            add("• DELETE - 删除文件/文件夹")
            add("• PROPFIND - 浏览目录")
            add("• MKCOL - 创建文件夹")
            add("• COPY - 复制文件")
            add("• MOVE - 移动/重命名文件")
            add("• LOCK/UNLOCK - 文件锁定")
            add("")
            add("=== 故障排除 ===")
            add("1. 确保设备与服务器在同一WiFi网络")
            add("2. 检查防火墙和端口开放")
            add("3. 验证用户名密码正确")
            add("4. 尝试在URL末尾添加或移除 /")
            add("5. 检查服务器日志获取详细错误信息")
        }
        
        return configDetails
    }
}

data class CompatibilityResult(
    val platform: String,
    val isCompatible: Boolean,
    val issues: List<String>,
    val suggestions: List<String>,
    val setupInstructions: List<String>
)

data class FullCompatibilityResult(
    val serverUrl: String,
    val overallCompatible: Boolean,
    val platformResults: Map<String, CompatibilityResult>,
    val recommendations: List<String>
)

data class RequestResult(
    val success: Boolean,
    val responseCode: Int,
    val headers: Map<String, String>
)

data class WindowsMappingCommands(
    val cmdCommand: String,
    val powershellCommand: String,
    val explorerSteps: List<String>
)

data class RegistryFix(
    val warning: String,
    val registryPath: String,
    val registryKey: String,
    val registryValue: String,
    val registryType: String,
    val description: String,
    val rebootRequired: Boolean
)

data class TroubleshootingGuide(
    val commonIssues: List<TroubleshootingItem>,
    val serviceChecks: List<String>
)

data class TroubleshootingItem(
    val error: String,
    val cause: String,
    val solutions: List<String>
)

data class DeviceCompatibilityInfo(
    val deviceType: String,
    val osVersion: String,
    val recommendedClients: List<String>,
    val knownIssues: List<String>,
    val optimizations: List<String>
)

data class ClientAppInfo(
    val name: String,
    val platform: String,
    val free: Boolean,
    val rating: Float,
    val description: String,
    val downloadUrl: String
)

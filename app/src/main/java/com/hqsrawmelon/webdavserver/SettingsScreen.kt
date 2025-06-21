package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    networkDiagnostics: NetworkDiagnostics,
    isServerRunning: Boolean,
    onRequestLocationPermission: () -> Unit = {}
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var showPortError by remember { mutableStateOf(false) }
    
    // Collect settings from SettingsManager
    val username by settingsManager.username.collectAsState()
    val password by settingsManager.password.collectAsState()
    val serverPort by settingsManager.serverPort.collectAsState()
    val allowAnonymous by settingsManager.allowAnonymous.collectAsState()
    val enableHttps by settingsManager.enableHttps.collectAsState()
    val connectionTimeout by settingsManager.connectionTimeout.collectAsState()
    val maxConnections by settingsManager.maxConnections.collectAsState()
    val bufferSize by settingsManager.bufferSize.collectAsState()
    val enableCors by settingsManager.enableCors.collectAsState()
    val enableCompression by settingsManager.enableCompression.collectAsState()
    val enableIpWhitelist by settingsManager.enableIpWhitelist.collectAsState()
    val ipWhitelist by settingsManager.ipWhitelist.collectAsState()
    val maxFailedAttempts by settingsManager.maxFailedAttempts.collectAsState()
    val blockDuration by settingsManager.blockDuration.collectAsState()
    val enableLogging by settingsManager.enableLogging.collectAsState()
    val logLevel by settingsManager.logLevel.collectAsState()
    val maxLogSize by settingsManager.maxLogSize.collectAsState()
    
    // Advanced settings state
    var advancedExpanded by remember { mutableStateOf(false) }
    var securityExpanded by remember { mutableStateOf(false) }
    var loggingExpanded by remember { mutableStateOf(false) }
    var networkExpanded by remember { mutableStateOf(false) }
    
    // Network diagnostics state
    var networkStatus by remember { mutableStateOf("未检查") }
    var portTestResult by remember { mutableStateOf("") }
    var isTestingNetwork by remember { mutableStateOf(false) }
    var isTestingPort by remember { mutableStateOf(false) }
    
    // Backup/restore state
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }
    
    // Add LogManager and snackbar state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logManager = remember { LogManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Log viewing state
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }
    var isLoadingLogs by remember { mutableStateOf(false) }
    var logSize by remember { mutableStateOf("0B") }
    
    // Load log size on composition
    LaunchedEffect(Unit) {
        logSize = logManager.getLogSize()
    }
    
    // File picker for import/export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(settingsManager.exportSettings().toByteArray())
                    }
                    // Show success message
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Show error message
                }
            }
        }
    }
    
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val content = inputStream.bufferedReader().readText()
                        val success = settingsManager.importSettings(content)
                        if (success) {
                            // Show success message
                        } else {
                            // Show error message
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Show error message
                }
            }
        }
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Authentication Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "认证设置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isServerRunning) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "服务器运行时部分设置无法修改，需重启服务器生效",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Anonymous Access Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许匿名访问",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "启用后无需用户名密码即可访问",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowAnonymous,
                            onCheckedChange = settingsManager::updateAllowAnonymous,
                            enabled = !isServerRunning
                        )
                    }
                    
                    if (!allowAnonymous) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedTextField(
                            value = username,
                            onValueChange = settingsManager::updateUsername,
                            label = { Text("用户名") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = password,
                            onValueChange = settingsManager::updatePassword,
                            label = { Text("密码") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            },
                            trailingIcon = {
                                val image = if (passwordVisible)
                                    Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff

                                IconButton(onClick = {passwordVisible = !passwordVisible}){
                                    Icon(imageVector = image, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }
        }
        
        // Server Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "服务器配置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Port setting
                    OutlinedTextField(
                        value = serverPort.toString(),
                        onValueChange = { newValue ->
                            val port = newValue.toIntOrNull()
                            if (port != null && port in 1024..65535) {
                                settingsManager.updateServerPort(port)
                                showPortError = false
                            } else {
                                showPortError = newValue.isNotEmpty()
                            }
                        },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isServerRunning,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = {
                            Icon(Icons.Default.Router, contentDescription = null)
                        },
                        isError = showPortError,
                        supportingText = if (showPortError) {
                            { Text("端口号必须在 1024-65535 范围内") }
                        } else {
                            { Text("推荐使用 8080 或其他未被占用的端口") }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // HTTPS Switch (placeholder for future implementation)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用 HTTPS",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "安全连接（暂未实现）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableHttps,
                            onCheckedChange = settingsManager::updateEnableHttps,
                            enabled = false // Disabled for now
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Additional server info
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = "服务器信息",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• 协议: WebDAV (HTTP/1.1)\n• 认证: Basic Authentication\n• 文件存储: 应用私有目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        
        // Advanced Settings Card with actual functionality
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "高级设置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                            Icon(
                                if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    
                    if (advancedExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Performance Settings with actual state management
                        Text(
                            text = "性能配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = connectionTimeout.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { timeout ->
                                    if (timeout in 5..300) settingsManager.updateConnectionTimeout(timeout)
                                }
                            },
                            label = { Text("连接超时 (秒)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("5-300 秒，推荐 30 秒") }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = maxConnections.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { max ->
                                    if (max in 1..100) settingsManager.updateMaxConnections(max)
                                }
                            },
                            label = { Text("最大连接数") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("1-100 个连接，推荐 10 个") }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = bufferSize.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { size ->
                                    if (size in 1024..65536) settingsManager.updateBufferSize(size)
                                }
                            },
                            label = { Text("缓冲区大小 (字节)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("1024-65536 字节，推荐 8192") }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Additional Features
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用 CORS",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "允许跨域请求",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableCors,
                                onCheckedChange = settingsManager::updateEnableCors,
                                enabled = !isServerRunning
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用压缩",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "自动压缩响应内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableCompression,
                                onCheckedChange = settingsManager::updateEnableCompression,
                                enabled = !isServerRunning
                            )
                        }
                    }
                }
            }
        }
        
        // Security Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "安全设置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { securityExpanded = !securityExpanded }) {
                            Icon(
                                if (securityExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    
                    if (securityExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // IP Whitelist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "IP 白名单",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "只允许指定IP范围访问",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableIpWhitelist,
                                onCheckedChange = settingsManager::updateEnableIpWhitelist,
                                enabled = !isServerRunning
                            )
                        }
                        
                        if (enableIpWhitelist) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = ipWhitelist,
                                onValueChange = settingsManager::updateIpWhitelist,
                                label = { Text("IP 地址范围") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isServerRunning,
                                supportingText = { Text("支持 CIDR 格式，如 192.168.1.0/24") }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Failed Attempts Protection
                        OutlinedTextField(
                            value = maxFailedAttempts.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { attempts ->
                                    if (attempts in 1..20) settingsManager.updateMaxFailedAttempts(attempts)
                                }
                            },
                            label = { Text("最大失败尝试次数") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("1-20 次，达到后临时封禁 IP") }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = blockDuration.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { duration ->
                                    if (duration in 60..3600) settingsManager.updateBlockDuration(duration)
                                }
                            },
                            label = { Text("封禁时长 (秒)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("60-3600 秒，推荐 300 秒") }
                        )
                    }
                }
            }
        }
        
        // Logging Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "日志设置",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { loggingExpanded = !loggingExpanded }) {
                            Icon(
                                if (loggingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    
                    if (loggingExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用日志记录",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "记录服务器访问和错误信息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableLogging,
                                onCheckedChange = settingsManager::updateEnableLogging
                            )
                        }
                        
                        if (enableLogging) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            var logLevelExpanded by remember { mutableStateOf(false) }
                            
                            ExposedDropdownMenuBox(
                                expanded = logLevelExpanded,
                                onExpandedChange = { logLevelExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = logLevel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("日志级别") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logLevelExpanded) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = logLevelExpanded,
                                    onDismissRequest = { logLevelExpanded = false }
                                ) {
                                    listOf("ERROR", "WARN", "INFO", "DEBUG").forEach { levelOption ->
                                        DropdownMenuItem(
                                            text = { Text(levelOption) },
                                            onClick = {
                                                settingsManager.updateLogLevel(levelOption)
                                                logLevelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = maxLogSize.toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let { size ->
                                        if (size in 1..100) settingsManager.updateMaxLogSize(size)
                                    }
                                },
                                label = { Text("最大日志文件大小 (MB)") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = { Text("1-100 MB，超过后自动轮转") }
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { 
                                        showLogDialog = true
                                        isLoadingLogs = true
                                        scope.launch {
                                            logContent = logManager.readLogs()
                                            logSize = logManager.getLogSize()
                                            isLoadingLogs = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("查看日志")
                                }
                                OutlinedButton(
                                    onClick = { 
                                        scope.launch {
                                            try {
                                                logManager.clearLogs()
                                                logSize = logManager.getLogSize()
                                                snackbarHostState.showSnackbar("日志已清空")
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar("清空日志失败: ${e.message}")
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("清空日志")
                                }
                            }
                            
                            // Add log size info
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "当前日志大小: $logSize",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Network Diagnostics Card with functionality
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "网络诊断",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { networkExpanded = !networkExpanded }) {
                            Icon(
                                if (networkExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    
                    if (networkExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Permission status check with real-time updates
                        var hasLocationPermission by remember { mutableStateOf(false) }
                        
                        LaunchedEffect(Unit) {
                            hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED ||
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }
                        }
                        
                        if (!hasLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "需要位置权限以显示WiFi详细信息",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Text(
                                        text = "Android系统要求应用获得位置权限才能访问WiFi网络名称(SSID)。这有助于更准确地显示网络连接状态。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Button(
                                        onClick = {
                                            onRequestLocationPermission()
                                            // Refresh permission status after request
                                            scope.launch {
                                                kotlinx.coroutines.delay(500)
                                                hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_FINE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED ||
                                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                } else {
                                                    true
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("授予位置权限")
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "网络状态: $networkStatus",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (portTestResult.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "端口测试: $portTestResult",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                // Show permission status
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "位置权限: ${if (hasLocationPermission) "已授权" else "未授权"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasLocationPermission) 
                                            MaterialTheme.colorScheme.primary 
                                        else 
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!isTestingNetwork) {
                                        isTestingNetwork = true
                                        scope.launch {
                                            try {
                                                val status = networkDiagnostics.checkNetworkStatus()
                                                networkStatus = status.message
                                                
                                                // Update permission status after network check
                                                hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_FINE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED ||
                                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                } else {
                                                    true
                                                }
                                            } catch (e: Exception) {
                                                networkStatus = "网络检查失败: ${e.message}"
                                            } finally {
                                                isTestingNetwork = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTestingNetwork
                            ) {
                                if (isTestingNetwork) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("检查网络")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (!isTestingPort) {
                                        isTestingPort = true
                                        scope.launch {
                                            try {
                                                val result = networkDiagnostics.testPort(serverPort)
                                                portTestResult = result.message
                                            } catch (e: Exception) {
                                                portTestResult = "端口测试失败: ${e.message}"
                                            } finally {
                                                isTestingPort = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTestingPort
                            ) {
                                if (isTestingPort) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("测试端口")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "网络访问提示",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "• 确保设备连接到 WiFi 网络\n• 客户端需与服务器在同一网络\n• 防火墙可能阻止连接\n• 某些路由器可能限制设备间通信\n• 尝试禁用设备的省电模式\n• 检查路由器的 AP 隔离设置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Backup and Tools Card with functionality
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "备份与工具",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                exportLauncher.launch("webdav_settings.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出设置")
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch("application/json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入设置")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重置为默认设置")
                    }
                }
            }
        }
        
        // About Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "关于",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "WebDAV 服务器\n版本 1.0.0\n\n一个功能丰富的 WebDAV 文件服务器应用，支持高级配置、安全设置和网络诊断。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Add bottom spacing for better scrolling experience
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置设置") },
            text = { Text("确定要重置所有设置为默认值吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsManager.resetToDefaults()
                        showResetDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    // Log viewing dialog
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { 
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("服务器日志")
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = logSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                if (isLoadingLogs) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        item {
                            Text(
                                text = logContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    // Share log content
                                    val intent = android.content.Intent().apply {
                                        action = android.content.Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, logContent)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "WebDAV服务器日志")
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(intent, "分享日志")
                                    )
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("分享失败: ${e.message}")
                                }
                            }
                        }
                    ) {
                        Text("分享")
                    }
                    TextButton(
                        onClick = {
                            isLoadingLogs = true
                            scope.launch {
                                logContent = logManager.readLogs()
                                logSize = logManager.getLogSize()
                                isLoadingLogs = false
                            }
                        }
                    ) {
                        Text("刷新")
                    }
                    TextButton(onClick = { showLogDialog = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }
    
    // Add SnackbarHost
    Box(modifier = Modifier.fillMaxSize()) {
        // Your existing LazyColumn content here
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}


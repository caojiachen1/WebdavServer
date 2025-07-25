package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 安全设置详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsDetail(
    settingsManager: SettingsManager,
    isServerRunning: Boolean,
) {
    val enableIpWhitelist by settingsManager.enableIpWhitelist.collectAsState()
    val ipWhitelist by settingsManager.ipWhitelist.collectAsState()
    val maxFailedAttempts by settingsManager.maxFailedAttempts.collectAsState()
    val blockDuration by settingsManager.blockDuration.collectAsState()

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (isServerRunning) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "服务器运行时部分设置无法修改，需重启服务器生效",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "IP 白名单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用 IP 白名单",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "只允许指定IP范围访问",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enableIpWhitelist,
                            onCheckedChange = settingsManager::updateEnableIpWhitelist,
                            enabled = !isServerRunning,
                        )
                    }

                    if (enableIpWhitelist) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = ipWhitelist,
                            onValueChange = settingsManager::updateIpWhitelist,
                            label = { Text("IP 地址范围") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning,
                            supportingText = { Text("支持 CIDR 格式，如 192.168.1.0/24，多个范围用逗号分隔") },
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "访问控制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                        supportingText = { Text("1-20 次，达到后临时封禁 IP") },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                        supportingText = { Text("60-3600 秒，推荐 300 秒") },
                    )
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "安全说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• IP白名单可以限制访问来源，提高安全性\n• 失败尝试保护可以防止暴力破解\n• 封禁时间过长可能影响正常用户访问\n• 建议根据网络环境调整安全策略",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * 日志设置详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggingSettingsDetail(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logManager = remember { LogManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val enableLogging by settingsManager.enableLogging.collectAsState()
    val logLevel by settingsManager.logLevel.collectAsState()
    val maxLogSize by settingsManager.maxLogSize.collectAsState()

    // Log viewing state
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }
    var isLoadingLogs by remember { mutableStateOf(false) }
    var logSize by remember { mutableStateOf("0B") }

    // Load log size on composition
    LaunchedEffect(Unit) {
        logSize = logManager.getLogSize()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "日志记录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用日志记录",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "记录服务器访问和错误信息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enableLogging,
                                onCheckedChange = settingsManager::updateEnableLogging,
                            )
                        }
                    }
                }
            }

            if (enableLogging) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "日志配置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            var logLevelExpanded by remember { mutableStateOf(false) }

                            ExposedDropdownMenuBox(
                                expanded = logLevelExpanded,
                                onExpandedChange = { logLevelExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = logLevel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("日志级别") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logLevelExpanded) },
                                    modifier =
                                        Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = logLevelExpanded,
                                    onDismissRequest = { logLevelExpanded = false },
                                ) {
                                    listOf("ERROR", "WARN", "INFO", "DEBUG").forEach { levelOption ->
                                        DropdownMenuItem(
                                            text = { Text(levelOption) },
                                            onClick = {
                                                settingsManager.updateLogLevel(levelOption)
                                                logLevelExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

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
                                supportingText = { Text("1-100 MB，超过后自动轮转") },
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "日志管理",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "当前日志大小: $logSize",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    modifier = Modifier.weight(1f),
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
                                    colors =
                                        ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("清空日志")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "日志说明",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• ERROR: 只记录错误信息\n• WARN: 记录警告和错误\n• INFO: 记录一般信息、警告和错误\n• DEBUG: 记录所有调试信息（详细）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Log viewing dialog
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("服务器日志")
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = logSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                if (isLoadingLogs) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 支持下滑功能的日志查看区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        if (logContent.isEmpty()) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text(
                                    text = "暂无日志内容",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(4.dp), 
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    // 将日志按行分割并显示
                                    val logLines = logContent.split('\n').filter { it.isNotBlank() }
                                    items(logLines) { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 1.dp),
                                            color = when {
                                                line.contains("ERROR") -> MaterialTheme.colorScheme.error
                                                line.contains("WARN") -> Color(0xFFFF9800)
                                                line.contains("INFO") -> MaterialTheme.colorScheme.primary
                                                line.contains("DEBUG") -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { showLogDialog = false }) {
                        Text("关闭")
                    }
                    TextButton(
                        onClick = {
                            isLoadingLogs = true
                            scope.launch {
                                logContent = logManager.readLogs()
                                logSize = logManager.getLogSize()
                                isLoadingLogs = false
                            }
                        },
                    ) {
                        Text("刷新")
                    }
                }
            },
        )
    }
}

/**
 * 后台服务设置详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundServiceSettingsDetail(settingsManager: SettingsManager) {
    val enableBackgroundService by settingsManager.enableBackgroundService.collectAsState()
    val autoStartOnBoot by settingsManager.autoStartOnBoot.collectAsState()
    val showNotificationControls by settingsManager.showNotificationControls.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "后台运行设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用后台服务",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "允许服务器在后台持续运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enableBackgroundService,
                            onCheckedChange = settingsManager::updateEnableBackgroundService,
                        )
                    }

                    if (enableBackgroundService) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "开机自启动",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "设备重启后自动启动服务器（即将支持）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoStartOnBoot,
                                onCheckedChange = settingsManager::updateAutoStartOnBoot,
                                enabled = false, // 暂时禁用，因为还未实现
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "显示通知控制",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "在通知中显示启动/停止按钮",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = showNotificationControls,
                                onCheckedChange = settingsManager::updateShowNotificationControls,
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "后台服务说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text =
                            "• 启用后台服务后，WebDAV服务器将在前台服务中运行\n" +
                                "• 服务器会在通知栏显示运行状态\n" +
                                "• 关闭应用不会停止服务器运行\n" +
                                "• 可通过通知栏的按钮控制服务器启停\n" +
                                "• 在设置中关闭后台服务可以节省电量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // 通知权限提示 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                val hasNotificationPermission =
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                if (!hasNotificationPermission) {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "需要通知权限",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "为了显示后台服务状态通知，请在系统设置中允许此应用发送通知。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网络诊断详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsDetail(
    networkDiagnostics: NetworkDiagnostics,
    onRequestLocationPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Network diagnostics state
    var networkStatus by remember { mutableStateOf("未检查") }
    var portTestResult by remember { mutableStateOf("") }
    var isTestingNetwork by remember { mutableStateOf(false) }
    var isTestingPort by remember { mutableStateOf(false) }

    // Permission status check with real-time updates
    var hasLocationPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasLocationPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "需要位置权限以显示WiFi详细信息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Android系统要求应用获得位置权限才能访问WiFi网络名称(SSID)。这有助于更准确地显示网络连接状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                onRequestLocationPermission()
                                // Refresh permission status after request
                                scope.launch {
                                    kotlinx.coroutines.delay(500)
                                    hasLocationPermission =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            androidx.core.content.ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                            ) == PackageManager.PERMISSION_GRANTED
                                        } else {
                                            true
                                        }
                                }
                            },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                    contentColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("授予位置权限")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "网络状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "网络状态: $networkStatus",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (portTestResult.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "端口测试: $portTestResult",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Show permission status
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "位置权限: ${if (hasLocationPermission) "已授权" else "未授权"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (hasLocationPermission) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!isTestingNetwork) {
                                    isTestingNetwork = true
                                    scope.launch {
                                        try {
                                            val status = networkDiagnostics.checkNetworkStatus()
                                            networkStatus = status.message
                                        } catch (e: Exception) {
                                            networkStatus = "检查失败: ${e.message}"
                                        } finally {
                                            isTestingNetwork = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingNetwork,
                        ) {
                            if (isTestingNetwork) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
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
                                            val result = networkDiagnostics.testPort(8080)
                                            portTestResult = result.message
                                        } catch (e: Exception) {
                                            portTestResult = "测试失败: ${e.message}"
                                        } finally {
                                            isTestingPort = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingPort,
                        ) {
                            if (isTestingPort) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.NetworkPing, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("测试端口")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "网络访问提示",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• 确保设备连接到 WiFi 网络\n• 客户端需与服务器在同一网络\n• 防火墙可能阻止连接\n• 某些路由器可能限制设备间通信\n• 尝试禁用设备的省电模式\n• 检查路由器的 AP 隔离设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * WebDAV兼容性检测页面
 */


@Composable
fun CompatibilityConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SimpleCompatibilityResultCard(platform: String, status: String, serverPort: Int, allowAnonymous: Boolean, username: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                "完美兼容" -> MaterialTheme.colorScheme.surfaceVariant
                "良好兼容" -> MaterialTheme.colorScheme.surfaceVariant
                "部分兼容" -> Color(0xFFFFF3E0) // 浅橙色
                else -> Color(0xFFFFEBEE) // 浅红色
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 平台名称和兼容性级别
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = platform,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                SimpleCompatibilityChip(status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 主要结果描述
            Text(
                text = "当前配置${status}$platform 平台",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 设置指南
            Spacer(modifier = Modifier.height(12.dp))
            
            var showGuide by remember { mutableStateOf(false) }
            
            TextButton(
                onClick = { showGuide = !showGuide },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showGuide) "收起设置指南" else "查看设置指南")
            }

            if (showGuide) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "设置指南",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = generateSetupGuide(platform, serverPort, allowAnonymous, username),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleCompatibilityChip(status: String) {
    val color = when (status) {
        "完美兼容" -> MaterialTheme.colorScheme.primary
        "良好兼容" -> Color(0xFF4CAF50)
        "部分兼容" -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun generateSetupGuide(platform: String, serverPort: Int, allowAnonymous: Boolean, username: String): String {
    val serverUrl = "http://[手机IP]:$serverPort"
    
    return when (platform) {
        "Windows" -> """
            Windows设置步骤：
            1. 打开文件资源管理器
            2. 右键点击"此电脑"，选择"映射网络驱动器"
            3. 选择驱动器号（如Z:）
            4. 输入地址：$serverUrl
            5. ${if (allowAnonymous) "保持匿名连接" else "输入用户名和密码"}
            6. 点击"完成"
            
            注意：HTTP连接可能需要修改注册表
        """.trimIndent()
        
        "macOS" -> """
            macOS设置步骤：
            1. 打开Finder
            2. 按 Cmd+K 或选择菜单"前往" > "连接服务器"
            3. 输入服务器地址：$serverUrl
            4. 点击"连接"
            5. ${if (allowAnonymous) "选择'客人'" else "输入用户名和密码"}
            6. 服务器将出现在边栏中
        """.trimIndent()
        
        "Linux" -> """
            Linux设置步骤：
            方法1 - 使用davfs2：
            1. 安装davfs2：sudo apt install davfs2
            2. 创建挂载点：sudo mkdir /mnt/webdav
            3. 挂载：sudo mount -t davfs $serverUrl /mnt/webdav
            4. ${if (!allowAnonymous) "输入用户名和密码" else "直接回车使用匿名连接"}
            
            方法2 - 使用文件管理器：
            1. 打开Nautilus/Dolphin等文件管理器
            2. 在地址栏输入：$serverUrl
            3. ${if (!allowAnonymous) "输入认证信息" else "直接连接"}
        """.trimIndent()
        
        "iOS" -> """
            iOS设置步骤：
            1. 打开"文件"应用
            2. 点击右上角"..."
            3. 选择"连接服务器"
            4. 输入服务器地址：$serverUrl
            5. 点击"连接"
            6. ${if (allowAnonymous) "选择'客人'连接" else "输入用户名和密码"}
            7. 服务器将显示在"位置"中
        """.trimIndent()
        
        "Android" -> """
            Android设置步骤（以Solid Explorer为例）：
            1. 下载并安装Solid Explorer
            2. 点击"+"添加云存储
            3. 选择"WebDAV"
            4. 输入以下信息：
               - 主机：[手机IP]
               - 端口：$serverPort
               - 使用HTTP
               - ${if (!allowAnonymous) "用户名：$username\n   - 密码：[输入密码]" else "启用匿名连接"}
            5. 点击"测试"验证连接
            6. 保存配置
        """.trimIndent()
        
        "Web浏览器" -> """
            Web浏览器访问限制：
            1. 现代浏览器对WebDAV支持有限
            2. 可以尝试访问：$serverUrl
            3. 某些浏览器可能支持基本的文件浏览
            4. 建议使用专门的WebDAV客户端或应用
            
            替代方案：
            - 使用在线WebDAV工具
            - 安装浏览器扩展
            - 使用支持WebDAV的在线文件管理器
        """.trimIndent()
        
        "第三方客户端" -> """
            第三方客户端通用设置：
            连接信息：
            - 协议：WebDAV
            - 服务器：[手机IP]
            - 端口：$serverPort
            - 使用HTTP协议
            - ${if (!allowAnonymous) "认证方式：Basic Auth\n- 用户名：$username\n- 密码：[输入密码]" else "认证方式：匿名访问"}
            
            推荐客户端：
            - CyberDuck（跨平台，免费）
            - Transmit（macOS，付费）
            - WinSCP（Windows，免费）
            - FileZilla（跨平台，免费，需Pro版本支持WebDAV）
            - CloudMounter（跨平台，付费）
        """.trimIndent()
        
        else -> "暂无设置指南"
    }
}

package com.hqsrawmelon.webdavserver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hqsrawmelon.webdavserver.utils.WebDAVCompatibilityChecker
import kotlinx.coroutines.launch

/**
 * WebDAV兼容性检测页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityCheckDetail(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 状态管理
    var isChecking by remember { mutableStateOf(false) }
    var checkResults by remember { mutableStateOf<List<WebDAVCompatibilityChecker.FullCompatibilityResult>>(emptyList()) }
    var serverConfig by remember { mutableStateOf<WebDAVCompatibilityChecker.ServerConfig?>(null) }

    // 收集当前服务器设置
    val serverPort by settingsManager.serverPort.collectAsState()
    val username by settingsManager.username.collectAsState()
    val password by settingsManager.password.collectAsState()
    val allowAnonymous by settingsManager.allowAnonymous.collectAsState()

    // 创建兼容性检测器
    val compatibilityChecker = remember { WebDAVCompatibilityChecker() }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 介绍卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DevicesOther,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "WebDAV兼容性检测",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "检测当前服务器配置与各平台WebDAV客户端的兼容性，并提供详细的设置指南",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 当前服务器配置
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "当前服务器配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ConfigRow("端口", serverPort.toString())
                        ConfigRow("认证方式", if (allowAnonymous) "匿名访问" else "用户名/密码认证")
                        if (!allowAnonymous) {
                            ConfigRow("用户名", username)
                            ConfigRow("密码", "●".repeat(password.length))
                        }
                    }
                }
            }

            // 检测按钮
            item {
                Button(
                    onClick = {
                        isChecking = true
                        scope.launch {
                            try {
                                // 构建服务器配置
                                serverConfig = WebDAVCompatibilityChecker.ServerConfig(
                                    port = serverPort,
                                    useHttps = false, // 目前不支持HTTPS
                                    username = if (allowAnonymous) null else username,
                                    password = if (allowAnonymous) null else password,
                                    allowAnonymous = allowAnonymous
                                )

                                // 执行兼容性检测
                                val results = compatibilityChecker.checkAllPlatforms(serverConfig!!)
                                checkResults = results
                                
                                snackbarHostState.showSnackbar("兼容性检测完成")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("检测失败: ${e.message}")
                            } finally {
                                isChecking = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isChecking
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("检测中...")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始兼容性检测")
                    }
                }
            }

            // 检测结果
            if (checkResults.isNotEmpty()) {
                item {
                    Text(
                        text = "检测结果",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(checkResults) { result ->
                    CompatibilityResultCard(result = result)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun ConfigRow(label: String, value: String) {
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
fun CompatibilityResultCard(result: WebDAVCompatibilityChecker.FullCompatibilityResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (result.compatibilityResult.level) {
                WebDAVCompatibilityChecker.CompatibilityLevel.PERFECT -> MaterialTheme.colorScheme.surfaceVariant
                WebDAVCompatibilityChecker.CompatibilityLevel.GOOD -> MaterialTheme.colorScheme.surfaceVariant
                WebDAVCompatibilityChecker.CompatibilityLevel.PARTIAL -> Color(0xFFFFF3E0) // 浅橙色
                WebDAVCompatibilityChecker.CompatibilityLevel.POOR -> Color(0xFFFFEBEE) // 浅红色
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
                    text = result.platform,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                CompatibilityLevelChip(level = result.compatibilityResult.level)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 主要结果描述
            Text(
                text = result.compatibilityResult.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 建议
            if (result.suggestion.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "建议",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 设置指南
            if (result.setupGuide.isNotEmpty()) {
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
                                text = result.setupGuide,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompatibilityLevelChip(level: WebDAVCompatibilityChecker.CompatibilityLevel) {
    val (color, text) = when (level) {
        WebDAVCompatibilityChecker.CompatibilityLevel.PERFECT -> 
            MaterialTheme.colorScheme.primary to "完美兼容"
        WebDAVCompatibilityChecker.CompatibilityLevel.GOOD -> 
            Color(0xFF4CAF50) to "良好兼容"
        WebDAVCompatibilityChecker.CompatibilityLevel.PARTIAL -> 
            Color(0xFFFF9800) to "部分兼容"
        WebDAVCompatibilityChecker.CompatibilityLevel.POOR -> 
            Color(0xFFF44336) to "兼容性差"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

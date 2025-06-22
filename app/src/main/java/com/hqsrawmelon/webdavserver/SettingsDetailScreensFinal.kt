package com.hqsrawmelon.webdavserver

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 备份与工具详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupToolsDetail(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Backup/restore state
    var showResetDialog by remember { mutableStateOf(false) }

    // File picker for import/export
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(settingsManager.exportSettings().toByteArray())
                        }
                        snackbarHostState.showSnackbar("设置导出成功")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        snackbarHostState.showSnackbar("导出失败: ${e.message}")
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                scope.launch {
                    try {
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            val content = inputStream.bufferedReader().readText()
                            val success = settingsManager.importSettings(content)
                            if (success) {
                                snackbarHostState.showSnackbar("设置导入成功")
                            } else {
                                snackbarHostState.showSnackbar("导入失败：文件格式错误")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        snackbarHostState.showSnackbar("导入失败: ${e.message}")
                    }
                }
            }
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
                            text = "设置备份",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "导出当前设置为 JSON 文件，可在其他设备或重置后导入使用",
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
                                    exportLauncher.launch("webdav_settings_${System.currentTimeMillis()}.json")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("导出设置")
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch("application/json")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("导入设置")
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
                            text = "重置选项",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "将所有设置恢复为默认值。此操作无法撤销，建议先导出当前设置作为备份。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("重置为默认设置")
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
                            text = "备份说明",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• 导出的JSON文件包含所有设置参数\n• 导入时会覆盖当前所有设置\n• 文件名包含时间戳便于管理\n• 建议定期备份重要配置",
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

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置设置") },
            text = { Text("确定要重置所有设置为默认值吗？此操作无法撤销。建议先导出当前设置作为备份。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                settingsManager.resetToDefaults()
                                showResetDialog = false
                                snackbarHostState.showSnackbar("设置已重置为默认值")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("重置失败: ${e.message}")
                            }
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("确定重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 关于页面详情
 */
@Composable
fun AboutDetail() {
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
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "WebDAV 服务器",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "版本 1.0.0",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "一个简单易用的移动端 WebDAV 文件服务器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = "功能特点",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "• 支持 WebDAV 协议文件访问\n• 用户名密码认证保护\n• 灵活的网络配置选项\n• 完整的日志记录功能\n• 安全的访问控制机制\n• 简洁直观的用户界面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = "技术信息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "• 基于 Android Jetpack Compose\n• 使用 NanoHTTPD 网络框架\n• Material Design 3 设计语言\n• 支持 Android 7.0+ (API 24+)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = "使用提示",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "启动服务器后，其他设备可通过 WebDAV 客户端连接到显示的地址。推荐的客户端包括：Windows 资源管理器、macOS Finder、ES文件浏览器等。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

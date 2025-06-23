package com.hqsrawmelon.webdavserver

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import com.hqsrawmelon.webdavserver.utils.PerformanceUtils

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isDirectory) 0 else file.length(),
    val lastModified: Long = file.lastModified(),
)

/**
 * 文件管理器状态管理
 */
@Stable
class FileManagerState(
    private val rootDir: File,
    private val coroutineScope: CoroutineScope
) {
    private val _currentDirectory = mutableStateOf(rootDir)
    val currentDirectory: State<File> = _currentDirectory
    
    private val _files = mutableStateOf<List<FileItem>>(emptyList())
    val files: State<List<FileItem>> = _files
    
    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading
    
    private val debouncer = PerformanceUtils.Debouncer(150L)
    
    init {
        loadFiles()
    }
    
    fun navigateToDirectory(directory: File) {
        if (directory.exists() && directory.isDirectory) {
            _currentDirectory.value = directory
            loadFiles()
        }
    }
    
    fun navigateUp() {
        val parent = currentDirectory.value.parentFile
        if (parent != null && parent.path.startsWith(rootDir.path)) {
            navigateToDirectory(parent)
        }
    }
    
    fun refresh() {
        debouncer.debounce(coroutineScope) {
            loadFiles()
        }
    }
    
    private fun loadFiles() {
        coroutineScope.launch {
            _isLoading.value = true
            try {
                val cacheKey = "files_${currentDirectory.value.absolutePath}"
                val loadedFiles = PerformanceUtils.getCached(cacheKey) {
                    loadFilesInternal(currentDirectory.value)
                }
                _files.value = loadedFiles
            } catch (e: Exception) {
                e.printStackTrace()
                _files.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun loadFilesInternal(directory: File): List<FileItem> = 
        withContext(Dispatchers.IO) {
            try {
                directory.listFiles()
                    ?.map { file -> FileItem(file) }
                    ?.sortedWith(
                        compareBy<FileItem> { !it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    ) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    
    suspend fun createFolder(folderName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val newFolder = File(currentDirectory.value, folderName)
                val success = newFolder.mkdirs()
                if (success) {
                    // 清除缓存以强制刷新
                    PerformanceUtils.clearCache("files_${currentDirectory.value.absolutePath}")
                    loadFiles()
                }
                success
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun renameFile(file: File, newName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val newFile = File(file.parent, newName)
                val success = file.renameTo(newFile)
                if (success) {
                    PerformanceUtils.clearCache("files_${currentDirectory.value.absolutePath}")
                    loadFiles()
                }
                success
            } catch (e: Exception) {
                false
            }
        }
    }
    
    suspend fun deleteFile(file: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val success = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                if (success) {
                    PerformanceUtils.clearCache("files_${currentDirectory.value.absolutePath}")
                    loadFiles()
                }
                success
            } catch (e: Exception) {
                false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalAnimationApi::class)
@Composable
fun FileManagerScreen(
    rootDir: File,
    currentDirectory: File,
    onDirectoryChange: (File) -> Unit,
    refreshTrigger: Int,
    onRefreshTrigger: () -> Unit,
    onUploadFile: () -> Unit = {},
) {
    var files by remember { mutableStateOf(listOf<FileItem>()) }
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadFileName by remember { mutableStateOf("") }
    // Use key with currentDirectory to reset refresh state for each directory
    var isRefreshing by remember(currentDirectory) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Pull refresh state - recreate for each directory using the correct API
    val pullRefreshState =
        rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    try {
                        // Add a small delay to make the refresh animation visible
                        kotlinx.coroutines.delay(200)
                        // Reload files for current directory
                        files = loadFiles(currentDirectory)
                        // Trigger global refresh
                        onRefreshTrigger()
                        // Additional delay to show completion
                        kotlinx.coroutines.delay(300)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isRefreshing = false
                    }
                }
            },
        )

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let { selectedUri ->
                scope.launch {
                    try {
                        isUploading = true
                        showUploadDialog = true
                        uploadProgress = 0f

                        // Get file name from URI
                        val fileName =
                            context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                cursor.moveToFirst()
                                cursor.getString(nameIndex)
                            } ?: "uploaded_file"

                        uploadFileName = fileName

                        // Copy file to current directory
                        val success =
                            withContext(Dispatchers.IO) {
                                copyFileFromUri(context, selectedUri, currentDirectory, fileName) { progress ->
                                    uploadProgress = progress
                                }
                            }

                        if (success) {
                            onRefreshTrigger()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isUploading = false
                        showUploadDialog = false
                        uploadProgress = 0f
                        uploadFileName = ""
                    }
                }
            }
        }
    LaunchedEffect(currentDirectory, refreshTrigger) {
        // Always reload files when directory changes or refresh is triggered
        files = loadFiles(currentDirectory)
    }
    
    // 添加返回键处理，当不在根目录时返回上级目录
    BackHandler(enabled = currentDirectory != rootDir) {
        onDirectoryChange(currentDirectory.parentFile ?: rootDir)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Top bar - always present
        TopAppBar(
            title = {
                AnimatedContent(
                    targetState = currentDirectory.name,
                    transitionSpec = {
                        slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                        ) togetherWith
                            slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                            )
                    },
                    label = "directory_title",
                ) { directoryName ->
                    Text(
                        text = "文件管理 - $directoryName",
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            navigationIcon = {
                AnimatedVisibility(
                    visible = currentDirectory != rootDir,
                    enter =
                        slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(250)),
                    exit =
                        slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(250)),
                ) {
                    IconButton(onClick = {
                        onDirectoryChange(currentDirectory.parentFile ?: rootDir)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                }
            },
            actions = {
                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "上传文件")
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
        )

        // Main content with pull-to-refresh
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState),
        ) {
            if (files.isEmpty() && !isRefreshing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "文件夹为空",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showCreateDialog = true },
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("创建文件夹")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files) { fileItem ->
                        FileItemCard(
                            fileItem = fileItem,
                            onFileClick = { file ->
                                if (file.isDirectory) {
                                    onDirectoryChange(file.file)
                                } else {
                                    selectedFile = file
                                    showDetailsDialog = true
                                }
                            },
                            onFileMenuClick = { file ->
                                selectedFile = file
                            },
                            onRename = {
                                showRenameDialog = true
                            },
                            onDelete = {
                                showDeleteDialog = true
                            },
                            onDetails = {
                                showDetailsDialog = true
                            },
                        )
                    }
                }
            }

            // Pull refresh indicator - ensure it's always visible
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                scale = true,
            )

            // Floating Action Button for Create Folder
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "创建文件夹")
            }
        }

        // Dialogs
        if (showCreateDialog) {
            CreateFolderDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { folderName ->
                    scope.launch {
                        createFolder(currentDirectory, folderName)
                        onRefreshTrigger()
                        showCreateDialog = false
                    }
                },
            )
        }

        if (showRenameDialog && selectedFile != null) {
            RenameDialog(
                currentName = selectedFile!!.name,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    scope.launch {
                        renameFile(selectedFile!!.file, newName)
                        onRefreshTrigger()
                        showRenameDialog = false
                        selectedFile = null
                    }
                },
            )
        }

        if (showDeleteDialog && selectedFile != null) {
            DeleteConfirmDialog(
                fileName = selectedFile!!.name,
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        deleteFile(selectedFile!!.file)
                        onRefreshTrigger()
                        showDeleteDialog = false
                        selectedFile = null
                    }
                },
            )
        }

        if (showDetailsDialog && selectedFile != null) {
            FileDetailsDialog(
                fileItem = selectedFile!!,
                onDismiss = {
                    showDetailsDialog = false
                    selectedFile = null
                },
            )
        }

        // Upload progress dialog
        if (showUploadDialog && isUploading) {
            UploadProgressDialog(
                fileName = uploadFileName,
                progress = uploadProgress,
                onCancel = {
                    // TODO: Implement upload cancellation if needed
                    showUploadDialog = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileItemCard(
    fileItem: FileItem,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onFileClick(fileItem) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (fileItem.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (fileItem.isDirectory) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (fileItem.isDirectory) "文件夹" else formatFileSize(fileItem.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDate(fileItem.lastModified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = {
                    onFileMenuClick(fileItem)
                    showMenu = true
                }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("详细信息") },
                        onClick = {
                            showMenu = false
                            onDetails()
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onConfirm(folderName.trim())
                    }
                },
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank() && newName != currentName) {
                        onConfirm(newName.trim())
                    }
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun DeleteConfirmDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = { Text("确定要删除 \"$fileName\" 吗？此操作无法撤销。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun FileDetailsDialog(
    fileItem: FileItem,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "文件详情",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(16.dp))

                DetailRow("名称", fileItem.name)
                DetailRow("类型", if (fileItem.isDirectory) "文件夹" else "文件")
                if (!fileItem.isDirectory) {
                    DetailRow("大小", formatFileSize(fileItem.size))
                }
                DetailRow("修改时间", formatDate(fileItem.lastModified))
                DetailRow("路径", fileItem.file.absolutePath)

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
fun UploadProgressDialog(
    fileName: String,
    progress: Float,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = { }) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
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
                    text = "上传文件",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (progress < 1.0f) {
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

private fun loadFiles(directory: File): List<FileItem> =
    try {
        directory
            .listFiles()
            ?.map { file ->
                FileItem(file)
            }?.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

private suspend fun createFolder(
    parentDir: File,
    folderName: String,
): Boolean =
    try {
        val newFolder = File(parentDir, folderName)
        newFolder.mkdirs()
    } catch (e: Exception) {
        false
    }

private suspend fun renameFile(
    file: File,
    newName: String,
): Boolean =
    try {
        val newFile = File(file.parent, newName)
        file.renameTo(newFile)
    } catch (e: Exception) {
        false
    }

private suspend fun deleteFile(file: File): Boolean =
    try {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    } catch (e: Exception) {
        false
    }

private fun formatFileSize(bytes: Long): String {
    val kb = 1024
    val mb = kb * 1024
    val gb = mb * 1024

    return when {
        bytes >= gb -> String.format("%.2f GB", bytes.toDouble() / gb)
        bytes >= mb -> String.format("%.2f MB", bytes.toDouble() / mb)
        bytes >= kb -> String.format("%.2f KB", bytes.toDouble() / kb)
        else -> "$bytes B"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private suspend fun copyFileFromUri(
    context: android.content.Context,
    uri: android.net.Uri,
    targetDirectory: File,
    fileName: String,
    onProgress: (Float) -> Unit,
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val targetFile = File(targetDirectory, fileName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Get file size for progress calculation
                val fileSize =
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            cursor.moveToFirst()
                            cursor.getLong(sizeIndex)
                        }
                    } catch (e: Exception) {
                        -1L
                    } ?: -1L

                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // Update progress if file size is known
                        if (fileSize > 0) {
                            val progress = (totalBytesRead.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }

                    outputStream.flush()
                }
            }

            // Final progress update
            withContext(Dispatchers.Main) {
                onProgress(1.0f)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

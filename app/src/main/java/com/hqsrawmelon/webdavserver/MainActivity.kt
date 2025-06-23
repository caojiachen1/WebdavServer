package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.compose.*
import com.hqsrawmelon.webdavserver.server.CustomWebDAVServer
import com.hqsrawmelon.webdavserver.service.WebDAVService
import com.hqsrawmelon.webdavserver.ui.theme.WebdavServerTheme
import com.hqsrawmelon.webdavserver.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * WebDAV 服务器状态管理 ViewModel
 */
class WebDAVServerViewModel(
    private val settingsManager: SettingsManager,
    private val context: android.content.Context
) : ViewModel() {
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()
    
    private val _serverStatus = MutableStateFlow("服务器已停止")
    val serverStatus: StateFlow<String> = _serverStatus.asStateFlow()
    
    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()
    
    // Use ResourceManager for better memory management
    private val _memoryInfo = MutableStateFlow(ResourceManager.checkMemoryUsage())
    val memoryInfo: StateFlow<ResourceManager.MemoryInfo> = _memoryInfo.asStateFlow()
    
    init {
        updateIpAddress()
        // Add periodic memory monitoring
        ResourceManager.addDisposable("WebDAVServerViewModel") {
            stopServer()
        }
        startMemoryMonitoring()
        
        // 监听服务状态
        startServiceStatusMonitoring()
    }
    
    private fun startServiceStatusMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // 每秒检查一次
                val serviceRunning = WebDAVService.isServiceRunning
                if (_isServerRunning.value != serviceRunning) {
                    _isServerRunning.value = serviceRunning
                    _serverStatus.value = if (serviceRunning) "服务器运行中" else "服务器已停止"
                }
            }
        }
    }
    
    private fun startMemoryMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(30000) // Update every 30 seconds
                _memoryInfo.value = ResourceManager.checkMemoryUsage()
                // Force GC if memory usage is high
                if (_memoryInfo.value.usagePercentage > 80) {
                    ResourceManager.forceGarbageCollection()
                }
            }
        }
    }
    
    private fun updateIpAddress() {
        viewModelScope.launch {
            _ipAddress.value = getLocalIpAddress(context)
        }
    }
    
    suspend fun startServer(username: String, password: String, port: Int, allowAnonymous: Boolean): Boolean {
        return try {
            // 请求通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                )
                if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // 权限未授予，但我们仍然尝试启动服务
                    // 用户可以在设置中手动启用通知
                }
            }
            
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
            
            // 立即更新状态，不等待监听器
            _isServerRunning.value = true
            _serverStatus.value = "正在启动服务器..."
            
            true
        } catch (e: Exception) {
            _serverStatus.value = "启动失败: ${e.message}"
            if (settingsManager.enableLogging.first()) {
                val logManager = LogManager(context)
                logManager.logError("ViewModel", "Failed to start background service: ${e.message}")
            }
            false
        }
    }
    
    fun stopServer() {
        try {
            val intent = Intent(context, WebDAVService::class.java).apply {
                action = WebDAVService.ACTION_STOP_SERVER
            }
            context.startService(intent)
            
            // 立即更新状态
            _isServerRunning.value = false
            _serverStatus.value = "正在停止服务器..."
            
        } catch (e: Exception) {
            viewModelScope.launch {
                if (settingsManager.enableLogging.first()) {
                    val logManager = LogManager(context)
                    logManager.logError("ViewModel", "Failed to stop background service: ${e.message}")
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // 不在这里停止服务，让服务独立运行
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var networkDiagnostics: NetworkDiagnostics
    
    // 用于控制返回行为的状态
    private var backPressedTime = 0L
    private val backPressedToast by lazy {
        android.widget.Toast.makeText(this, "再按一次退出应用", android.widget.Toast.LENGTH_SHORT)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            // Handle permission results
            val locationPermissionGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (locationPermissionGranted) {
                // Refresh network status if location permission was granted
                // This will be handled in the UI
            }
            
            // 检查通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val notificationPermissionGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
                if (!notificationPermissionGranted) {
                    // 可以在这里显示一个说明，告诉用户为什么需要通知权限
                }
            }
        }

    // Add location permission launcher
    private val requestLocationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val locationPermissionGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (!locationPermissionGranted) {
                // Show explanation why location permission is needed
                // This will be handled in the UI with a snackbar or dialog
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register for resource management
        this.registerForResourceManagement()

        // Initialize managers
        settingsManager = SettingsManager(this)
        networkDiagnostics = NetworkDiagnostics(this)

        // Configure status bar to follow system theme with MIUI support
        configureStatusBar()

        // Request necessary permissions
        requestPermissions()

        setContent {
            WebdavServerTheme {
                MainApp()
            }
        }
    }

    private fun configureStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Check if system is in dark mode
        val isDarkMode =
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> true
                else -> false
            }

        // Configure status bar based on theme with reduced padding
        if (isDarkMode) {
            // Dark mode - status bar with light content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            } else {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        } else {
            // Light mode - status bar with dark content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        }
    }

    private fun requestPermissions() {
        val permissions =
            mutableListOf<String>()
                .apply {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    add(Manifest.permission.INTERNET)
                    add(Manifest.permission.ACCESS_NETWORK_STATE)
                    add(Manifest.permission.ACCESS_WIFI_STATE)

                    // Add location permissions for WiFi SSID access on Android 6+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    
                    // Add notification permission for Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()

        requestPermissionLauncher.launch(permissions)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
    @Composable
    fun MainApp() {
        val navController = rememberNavController()
        
        // 设置返回键处理 - 只处理子页面的返回
        BackHandler(enabled = navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }

        NavHost(
            navController = navController,
            startDestination = "main_screen",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("main_screen") {
                MainScreen()
            }
            composable("details_screen") {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Details Screen")
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Go Back")
                    }
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        val pagerState = rememberPagerState(pageCount = { 3 })
        val webdavRootDir = remember { File(getExternalFilesDir(null), "webdav") }
        val scope = rememberCoroutineScope()

        // 使用 ViewModel 管理服务器状态
        val viewModel = remember { 
            WebDAVServerViewModel(settingsManager, this@MainActivity) 
        }
        
        // Collect states from ViewModel and SettingsManager
        val isServerRunning by viewModel.isServerRunning.collectAsState()
        val serverStatus by viewModel.serverStatus.collectAsState()
        val ipAddress by viewModel.ipAddress.collectAsState()
        val memoryInfo by viewModel.memoryInfo.collectAsState()
        
        val username by settingsManager.username.collectAsState()
        val password by settingsManager.password.collectAsState()
        val serverPort by settingsManager.serverPort.collectAsState()
        val allowAnonymous by settingsManager.allowAnonymous.collectAsState()
        
        // File manager state for navigation
        var currentDirectory by remember { mutableStateOf(webdavRootDir) }
        var refreshTrigger by remember { mutableStateOf(0) }
        val context = LocalContext.current
        
        // 添加主页面返回逻辑 - 双击退出应用
        BackHandler {
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                backPressedToast.cancel()
                finish()
            } else {
                backPressedTime = System.currentTimeMillis()
                backPressedToast.show()
            }
        }

        // File picker launcher for file manager
        val filePickerLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
            ) { uri ->
                uri?.let { selectedUri ->
                    scope.launch {
                        try {
                            // Copy file to current directory
                            val fileName =
                                context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    cursor.moveToFirst()
                                    cursor.getString(nameIndex)
                                } ?: "uploaded_file"

                            val success =
                                withContext(Dispatchers.IO) {
                                    copyFileFromUri(context, selectedUri, currentDirectory, fileName) { _ -> }
                                }

                            if (success) {
                                refreshTrigger++
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

        LaunchedEffect(Unit) {
            if (!webdavRootDir.exists()) {
                webdavRootDir.mkdirs()
            }
        }
        Scaffold(modifier = Modifier.fillMaxSize(), bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    icon = {
                        AnimatedContent(
                            targetState = pagerState.currentPage == 0,
                            transitionSpec = {
                                scaleIn(animationSpec = tween(200)) togetherWith scaleOut(animationSpec = tween(200))
                            },
                            label = "server_icon",
                        ) { isSelected ->
                            Icon(
                                if (isSelected) Icons.Default.PlayArrow else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(if (isSelected) 28.dp else 24.dp),
                            )
                        }
                    },
                    label = {
                        Text(
                            "服务器",
                            style =
                                if (pagerState.currentPage == 0) {
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                        )
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    icon = {
                        AnimatedContent(
                            targetState = pagerState.currentPage == 1,
                            transitionSpec = {
                                scaleIn(animationSpec = tween(200)) togetherWith scaleOut(animationSpec = tween(200))
                            },
                            label = "file_icon",
                        ) { isSelected ->
                            Icon(
                                if (isSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(if (isSelected) 28.dp else 24.dp),
                            )
                        }
                    },
                    label = {
                        Text(
                            "文件管理",
                            style =
                                if (pagerState.currentPage == 1) {
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                        )
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(2)
                        }
                    },
                    icon = {
                        AnimatedContent(
                            targetState = pagerState.currentPage == 2,
                            transitionSpec = {
                                scaleIn(animationSpec = tween(200)) togetherWith scaleOut(animationSpec = tween(200))
                            },
                            label = "settings_icon",
                        ) { isSelected ->
                            Icon(
                                if (isSelected) Icons.Default.Settings else Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(if (isSelected) 28.dp else 24.dp),
                            )
                        }
                    },
                    label = {
                        Text(
                            "设置",
                            style =
                                if (pagerState.currentPage == 2) {
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                        )
                    },
                )
            }
        }) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                userScrollEnabled = true,
            ) { page ->
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        slideInHorizontally(
                            initialOffsetX = { if (targetState > initialState) it else -it },
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                        ) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { if (targetState > initialState) -it else it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                            )
                    },
                    label = "page_transition",
                ) { currentPage ->
                    when (currentPage) {
                        0 ->
                            WebDAVServerApp(
                                viewModel = viewModel,
                                username = username,
                                password = password,
                                serverPort = serverPort,
                                allowAnonymous = allowAnonymous,
                                ipAddress = ipAddress,
                                isServerRunning = isServerRunning,
                                serverStatus = serverStatus,
                                memoryInfo = memoryInfo,
                            )
                        1 ->
                            FileManagerScreen(
                                rootDir = webdavRootDir,
                                currentDirectory = currentDirectory,
                                onDirectoryChange = { currentDirectory = it },
                                refreshTrigger = refreshTrigger,
                                onRefreshTrigger = { refreshTrigger++ },
                                onUploadFile = { filePickerLauncher.launch("*/*") },
                            )
                        2 ->
                            SettingsScreen(
                                settingsManager = settingsManager,
                                networkDiagnostics = networkDiagnostics,
                                isServerRunning = isServerRunning,
                                onRequestLocationPermission = {
                                    requestLocationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                },
                            )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
    @Composable
    fun WebDAVServerApp(
        viewModel: WebDAVServerViewModel,
        username: String,
        password: String,
        serverPort: Int,
        allowAnonymous: Boolean,
        ipAddress: String,
        isServerRunning: Boolean,
        serverStatus: String,
        memoryInfo: ResourceManager.MemoryInfo,
    ) {
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            if (!File(getExternalFilesDir(null), "webdav").exists()) {
                File(getExternalFilesDir(null), "webdav").mkdirs()
            }
        }

        ServerStatusCard(
            isServerRunning = isServerRunning,
            serverStatus = serverStatus,
            ipAddress = ipAddress,
            serverPort = serverPort,
            allowAnonymous = allowAnonymous,
            username = username,
            memoryInfo = memoryInfo,
            onServerToggle = {
                scope.launch {
                    if (isServerRunning) {
                        viewModel.stopServer()
                    } else {
                        viewModel.startServer(username, password, serverPort, allowAnonymous)
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ServerStatusCard(
        isServerRunning: Boolean,
        serverStatus: String,
        ipAddress: String,
        serverPort: Int,
        allowAnonymous: Boolean,
        username: String,
        memoryInfo: ResourceManager.MemoryInfo,
        onServerToggle: () -> Unit
    ) {

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top bar - always present
            TopAppBar(
                title = {
                    Text(
                        text = "WebDAV 服务器",
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Server Status Card
                ServerInfoCard(
                    isServerRunning = isServerRunning,
                    serverStatus = serverStatus,
                    ipAddress = ipAddress,
                    serverPort = serverPort,
                    allowAnonymous = allowAnonymous,
                    username = username,
                    memoryInfo = memoryInfo
                )

                // Control Button
                Button(
                    onClick = onServerToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServerRunning) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Icon(
                        imageVector = if (isServerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isServerRunning) "停止服务器" else "启动服务器",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Quick Access Info
                if (!isServerRunning) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "后台运行",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "• 服务器将在后台持续运行\n• 可通过通知栏控制启动停止\n• 关闭应用不会停止服务器\n• 重启手机后需要重新启动服务器",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ServerInfoCard(
        isServerRunning: Boolean,
        serverStatus: String,
        ipAddress: String,
        serverPort: Int,
        allowAnonymous: Boolean,
        username: String,
        memoryInfo: ResourceManager.MemoryInfo
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Status Icon
                Icon(
                    imageVector = if (isServerRunning) Icons.Default.CheckCircleOutline else Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = if (isServerRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "服务器状态",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = serverStatus,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isServerRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                
                // 添加后台服务状态指示
                if (isServerRunning) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "后台运行中",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                if (isServerRunning && ipAddress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "连接信息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "访问地址",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "http://$ipAddress:$serverPort",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = if (allowAnonymous) {
                                    "访问模式: 匿名访问"
                                } else {
                                    "用户名: $username"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    
                    // Memory usage information
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "系统状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (memoryInfo.usagePercentage > 80) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "内存使用:",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${memoryInfo.usagePercentage}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "已用:",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = memoryInfo.formatUsedMemory(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "可用:",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = memoryInfo.formatAvailableMemory(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Clean up resources before calling super
        this.unregisterFromResourceManagement()
        super.onDestroy()
        // ViewModel 会自动处理清理
        // 注意：不在这里停止服务，让服务独立运行
    }
    
    override fun onPause() {
        super.onPause()
        // 当应用进入后台时，服务继续运行
    }
    
    override fun onResume() {
        super.onResume()
        // 当应用恢复时，刷新服务状态
    }
}

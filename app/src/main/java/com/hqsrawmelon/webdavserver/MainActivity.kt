package com.hqsrawmelon.webdavserver

import android.Manifest
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
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.compose.*
import com.hqsrawmelon.webdavserver.server.CustomWebDAVServer
import com.hqsrawmelon.webdavserver.ui.theme.WebdavServerTheme
import com.hqsrawmelon.webdavserver.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

class MainActivity : ComponentActivity() {
    private var webServer: CustomWebDAVServer? = null
    private lateinit var settingsManager: SettingsManager
    private lateinit var networkDiagnostics: NetworkDiagnostics

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
                }.toTypedArray()

        requestPermissionLauncher.launch(permissions)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
    @Composable
    fun MainApp() {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = "main_screen",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("main_screen") {
                MainScreen()
            }
            composable("details_screen") {
                DetailsScreen(navController)
            }
        }
    }

    @Composable
    fun MainScreen() {
        val pagerState = rememberPagerState(pageCount = { 3 })
        val webdavRootDir = remember { File(getExternalFilesDir(null), "webdav") }
        val scope = rememberCoroutineScope()

        // Move server state to this level to persist across tab switches
        var isServerRunning by remember { mutableStateOf(false) }
        var serverStatus by remember { mutableStateOf("服务器已停止") }

        // Collect settings from SettingsManager
        val username by settingsManager.username.collectAsState()
        val password by settingsManager.password.collectAsState()
        val serverPort by settingsManager.serverPort.collectAsState()
        val allowAnonymous by settingsManager.allowAnonymous.collectAsState()
        
        // File manager state for navigation
        var currentDirectory by remember { mutableStateOf(webdavRootDir) }
        var refreshTrigger by remember { mutableStateOf(0) }
        val context = LocalContext.current

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
                                isServerRunning = isServerRunning,
                                onServerRunningChange = { isServerRunning = it },
                                serverStatus = serverStatus,
                                onServerStatusChange = { serverStatus = it },
                                username = username,
                                password = password,
                                serverPort = serverPort,
                                allowAnonymous = allowAnonymous,
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
        isServerRunning: Boolean,
        onServerRunningChange: (Boolean) -> Unit,
        serverStatus: String,
        onServerStatusChange: (String) -> Unit,
        username: String,
        password: String,
        serverPort: Int,
        allowAnonymous: Boolean,
    ) {
        var ipAddress by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            ipAddress = getLocalIpAddress(this@MainActivity)
        }

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
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Server Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
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
                                colors =
                                    CardDefaults.cardColors(
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
                                        text =
                                            if (allowAnonymous) {
                                                "访问模式: 匿名访问"
                                            } else {
                                                "用户名: $username"
                                            },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Control Button
                Button(
                    onClick = {
                        scope.launch {
                            if (isServerRunning) {
                                stopServer()
                                onServerRunningChange(false)
                                onServerStatusChange("服务器已停止")
                            } else {
                                val success = startServer(username, password, serverPort, allowAnonymous)
                                if (success) {
                                    onServerRunningChange(true)
                                    onServerStatusChange("服务器运行中")
                                } else {
                                    onServerStatusChange("启动失败")
                                }
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                if (isServerRunning) {
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
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "快速开始",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text =
                                    "1. 确保设备连接到WiFi网络\n" +
                                        "2. 在设置中配置用户名和密码\n" +
                                        "3. 点击启动服务器按钮\n" +
                                        "4. 使用WebDAV客户端连接",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun startServer(
        username: String,
        password: String,
        port: Int,
        allowAnonymous: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopServer() // Stop any existing server

                val rootDir = File(getExternalFilesDir(null), "webdav")
                if (!rootDir.exists()) {
                    rootDir.mkdirs()
                }

                webServer =
                    CustomWebDAVServer(
                        port = port,
                        rootDir = rootDir,
                        username = username,
                        password = password,
                        allowAnonymous = allowAnonymous,
                        settingsManager = settingsManager,
                    )

                // Apply connection timeout and max connections from settings
                val connectionTimeout = settingsManager.connectionTimeout.first() * 1000 // Convert to milliseconds
                val maxConnections = settingsManager.maxConnections.first()

                // Start server with custom timeout
                webServer?.start(connectionTimeout, false)

                // Log server start
                if (settingsManager.enableLogging.first()) {
                    val logManager = LogManager(this@MainActivity)
                    logManager.logInfo(
                        "Server",
                        "WebDAV server started on port $port with timeout ${connectionTimeout}ms and max $maxConnections connections",
                    )
                }

                true
            } catch (e: IOException) {
                e.printStackTrace()

                // Log error
                if (settingsManager.enableLogging.first()) {
                    val logManager = LogManager(this@MainActivity)
                    logManager.logError("Server", "Failed to start server: ${e.message}")
                }

                false
            }
        }

    private fun stopServer() {
        webServer?.stop()
        webServer = null

        // Log server stop
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (settingsManager.enableLogging.first()) {
                    val logManager = LogManager(this@MainActivity)
                    logManager.logInfo("Server", "WebDAV server stopped")
                }
            }
        } catch (e: Exception) {
            // Ignore logging errors during shutdown
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    @Composable
    fun DetailsScreen(navController: NavController) {
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

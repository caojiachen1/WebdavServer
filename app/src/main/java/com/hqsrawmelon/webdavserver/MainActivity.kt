package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.hqsrawmelon.webdavserver.ui.theme.WebdavServerTheme
import com.hqsrawmelon.webdavserver.FileManagerScreen
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import android.content.res.Configuration
import android.os.Build
import java.lang.reflect.Method

class MainActivity : ComponentActivity() {
    private var webServer: CustomWebDAVServer? = null
    private val serverPort = 8080
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Handle permission results if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
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
        val isDarkMode = when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
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
                window.insetsController?.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            } else {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        }
    }
    
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        
        requestPermissionLauncher.launch(permissions)
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var selectedTab by remember { mutableStateOf(0) }
        val webdavRootDir = remember { File(getExternalFilesDir(null), "webdav") }
        
        // Move server state to this level to persist across tab switches
        var isServerRunning by remember { mutableStateOf(false) }
        var serverStatus by remember { mutableStateOf("服务器已停止") }
        var username by remember { mutableStateOf("admin") }
        var password by remember { mutableStateOf("123456") }
        
        // File manager state for navigation
        var currentDirectory by remember { mutableStateOf(webdavRootDir) }
        var refreshTrigger by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        
        // File picker launcher for file manager
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { selectedUri ->
                scope.launch {
                    try {
                        // Copy file to current directory
                        val fileName = context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            cursor.moveToFirst()
                            cursor.getString(nameIndex)
                        } ?: "uploaded_file"
                        
                        val success = withContext(Dispatchers.IO) {
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
        
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                when (selectedTab) {
                    0 -> TopAppBar(
                        title = { 
                            Text(
                                "WebDAV 服务器",
                                fontWeight = FontWeight.Bold
                            ) 
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    1 -> TopAppBar(
                        title = { 
                            Text(
                                "文件管理 - ${currentDirectory.name}",
                                fontWeight = FontWeight.Bold
                            ) 
                        },
                        navigationIcon = {
                            if (currentDirectory != webdavRootDir) {
                                IconButton(onClick = { 
                                    currentDirectory = currentDirectory.parentFile ?: webdavRootDir
                                }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回上级")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.CloudUpload, contentDescription = "上传文件")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    2 -> TopAppBar(
                        title = { 
                            Text(
                                "设置",
                                fontWeight = FontWeight.Bold
                            ) 
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        label = { Text("服务器") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("文件管理") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> WebDAVServerApp(
                        isServerRunning = isServerRunning,
                        onServerRunningChange = { isServerRunning = it },
                        serverStatus = serverStatus,
                        onServerStatusChange = { serverStatus = it },
                        username = username,
                        password = password
                    )
                    1 -> FileManagerScreen(
                        rootDir = webdavRootDir,
                        currentDirectory = currentDirectory,
                        onDirectoryChange = { currentDirectory = it },
                        refreshTrigger = refreshTrigger,
                        onRefreshTrigger = { refreshTrigger++ },
                        onUploadFile = { filePickerLauncher.launch("*/*") }
                    )
                    2 -> SettingsScreen(
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        isServerRunning = isServerRunning
                    )
                }
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun WebDAVServerApp(
        isServerRunning: Boolean,
        onServerRunningChange: (Boolean) -> Unit,
        serverStatus: String,
        onServerStatusChange: (String) -> Unit,
        username: String,
        password: String
    ) {
        var ipAddress by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        
        LaunchedEffect(Unit) {
            ipAddress = getLocalIpAddress()
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Server Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status Icon
                    Icon(
                        imageVector = if (isServerRunning) Icons.Default.CheckCircleOutline else Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = if (isServerRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "服务器状态",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = serverStatus,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isServerRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    if (isServerRunning && ipAddress.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        HorizontalDivider()
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "连接信息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "访问地址",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "http://$ipAddress:$serverPort",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = "用户名: $username",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                            val success = startServer(username, password)
                            if (success) {
                                onServerRunningChange(true)
                                onServerStatusChange("服务器运行中")
                            } else {
                                onServerStatusChange("启动失败")
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServerRunning) 
                        MaterialTheme.colorScheme.error 
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isServerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isServerRunning) "停止服务器" else "启动服务器",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Quick Access Info
            if (!isServerRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "快速开始",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. 确保设备连接到WiFi网络\n" +
                                  "2. 在设置中配置用户名和密码\n" +
                                  "3. 点击启动服务器按钮\n" +
                                  "4. 使用WebDAV客户端连接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    private suspend fun startServer(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            stopServer() // Stop any existing server
            
            val rootDir = File(getExternalFilesDir(null), "webdav")
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            
            webServer = CustomWebDAVServer(serverPort, rootDir, username, password)
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
    
    private fun stopServer() {
        webServer?.stop()
        webServer = null
    }
    
    private fun getLocalIpAddress(): String {
        try {
            // Try modern approach first (API 23+)
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork: Network? = connectivityManager.activeNetwork
            
            if (activeNetwork != null) {
                val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(activeNetwork)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: ""
                    }
                }
            }
            
            // Fallback to NetworkInterface approach
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return "无法获取IP地址"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}

class CustomWebDAVServer(port: Int, private val rootDir: File, private val username: String, private val password: String) : NanoHTTPD(port) {
    
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        val headers = session.headers
        
        // Check authentication for all methods except OPTIONS
        if (method != Method.OPTIONS && !isAuthenticated(headers)) {
            val response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Authentication required")
            response.addHeader("WWW-Authenticate", "Basic realm=\"WebDAV Server\"")
            return response
        }
        
        // Add CORS headers
        val response = when (method) {
            Method.OPTIONS -> handleOptions()
            Method.GET -> handleGet(uri)
            Method.PUT -> handlePut(session, uri)
            Method.DELETE -> handleDelete(uri)
            Method.MKCOL -> handleMkCol(uri)
            Method.PROPFIND -> handlePropFind(uri, headers)
            Method.PROPPATCH -> handlePropPatch()
            Method.MOVE -> handleMove(session, uri)
            Method.COPY -> handleCopy(session, uri)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
        }
        
        // Add WebDAV headers
        response.addHeader("DAV", "1,2")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Depth, Authorization, Date, User-Agent, X-File-Size, X-Requested-With, If-Modified-Since, X-File-Name, Cache-Control")
        response.addHeader("Access-Control-Expose-Headers", "DAV")
        
        return response
    }
    
    private fun isAuthenticated(headers: Map<String, String>): Boolean {
        val authHeader = headers["authorization"] ?: return false
        
        if (!authHeader.startsWith("Basic ")) {
            return false
        }
        
        try {
            val encoded = authHeader.substring(6)
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
            val parts = decoded.split(":", limit = 2)
            
            if (parts.size != 2) {
                return false
            }
            
            return parts[0] == username && parts[1] == password
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        response.addHeader("Allow", "GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, OPTIONS")
        return response
    }
    
    private fun handleGet(uri: String): Response {
        val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        if (file.isDirectory) {
            return generateDirectoryListing(file, uri)
        }
        
        try {
            val fis = FileInputStream(file)
            val mimeType = getCustomMimeType(file.name)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
            response.addHeader("Content-Length", file.length().toString())
            response.addHeader("Accept-Ranges", "bytes")
            return response
        } catch (e: IOException) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file")
        }
    }
    
    private fun handlePut(session: IHTTPSession, uri: String): Response {
        val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        val fileExists = file.exists()
        
        try {
            file.parentFile?.mkdirs()
            
            // Get content length from headers
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            
            // Write file directly from input stream without parsing body
            FileOutputStream(file).use { fos ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var bytesRead: Int
                
                while (session.inputStream.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    // Break if we've read all expected content
                    if (contentLength > 0 && totalBytesRead >= contentLength) {
                        break
                    }
                }
                fos.flush()
            }
            
            // Return appropriate status code
            val status = if (fileExists) Response.Status.NO_CONTENT else Response.Status.CREATED
            return newFixedLengthResponse(status, "text/plain", "")
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error creating file: ${e.message}")
        }
    }
    
    private fun handleDelete(uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val deleted = if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        
        return if (deleted) {
            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error deleting file")
        }
    }
    
    private fun handleMkCol(uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (file.exists()) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Directory already exists")
        }
        
        val created = file.mkdirs()
        return if (created) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Directory created")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error creating directory")
        }
    }
    
    private fun handlePropFind(uri: String, headers: Map<String, String>): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val depth = headers["depth"] ?: "1"
        val xml = generatePropFindResponse(file, uri, depth)
        
        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
    }
    
    private fun handlePropPatch(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:propstat>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent())
    }
    
    private fun handleMove(session: IHTTPSession, uri: String): Response {
        val destination = session.headers["destination"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))
        
        // Parse destination URL more robustly
        val destUri = try {
            val url = java.net.URL(destination)
            java.net.URLDecoder.decode(url.path, "UTF-8")
        } catch (e: Exception) {
            destination.substringAfter("://").substringAfter("/").substringAfter(":")
        }
        
        val destFile = File(rootDir, destUri.removePrefix("/"))
        
        if (!sourceFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Source not found")
        }
        
        destFile.parentFile?.mkdirs()
        val moved = sourceFile.renameTo(destFile)
        
        return if (moved) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Move failed")
        }
    }
    
    private fun handleCopy(session: IHTTPSession, uri: String): Response {
        val destination = session.headers["destination"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))
        
        // Parse destination URL more robustly
        val destUri = try {
            val url = java.net.URL(destination)
            java.net.URLDecoder.decode(url.path, "UTF-8")
        } catch (e: Exception) {
            destination.substringAfter("://").substringAfter("/").substringAfter(":")
        }
        
        val destFile = File(rootDir, destUri.removePrefix("/"))
        
        if (!sourceFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Source not found")
        }
        
        try {
            destFile.parentFile?.mkdirs()
            sourceFile.copyTo(destFile, overwrite = true)
            return newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Copy failed: ${e.message}")
        }
    }
    
    private fun generatePropFindResponse(file: File, uri: String, depth: String): String {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        
        val xml = StringBuilder()
        xml.append("""<?xml version="1.0" encoding="utf-8"?>""")
        xml.append("""<D:multistatus xmlns:D="DAV:">""")
        
        // Add current resource
        xml.append(generateResourceResponse(file, uri, dateFormat))
        
        // Add children if depth > 0 and it's a directory
        if (depth != "0" && file.isDirectory) {
            file.listFiles()?.forEach { child ->
                val childUri = if (uri.endsWith("/")) uri + child.name else "$uri/${child.name}"
                xml.append(generateResourceResponse(child, childUri, dateFormat))
            }
        }
        
        xml.append("</D:multistatus>")
        return xml.toString()
    }
    
    private fun generateResourceResponse(file: File, uri: String, dateFormat: SimpleDateFormat): String {
        val lastModified = dateFormat.format(Date(file.lastModified()))
        val isCollection = if (file.isDirectory) "<D:collection/>" else ""
        val contentLength = if (!file.isDirectory) "<D:getcontentlength>${file.length()}</D:getcontentlength>" else ""
        val contentType = if (!file.isDirectory) "<D:getcontenttype>${getCustomMimeType(file.name)}</D:getcontenttype>" else ""
        
        return """
            <D:response>
                <D:href>$uri</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>${file.name}</D:displayname>
                        <D:getlastmodified>$lastModified</D:getlastmodified>
                        <D:resourcetype>$isCollection</D:resourcetype>
                        $contentLength
                        $contentType
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        """.trimIndent()
    }
    
    private fun generateDirectoryListing(dir: File, uri: String): Response {
        val html = StringBuilder()
        html.append("<html><body><h1>Directory listing for $uri</h1><ul>")
        
        if (uri != "/") {
            html.append("<li><a href=\"../\">../</a></li>")
        }
        
        dir.listFiles()?.forEach { file ->
            val name = if (file.isDirectory) "${file.name}/" else file.name
            html.append("<li><a href=\"$name\">$name</a></li>")
        }
        
        html.append("</ul></body></html>")
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString())
    }
    
    private fun getCustomMimeType(filename: String): String {
        return when (filename.substringAfterLast('.').lowercase()) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }
}

// Add the copyFileFromUri function to MainActivity
private suspend fun copyFileFromUri(
    context: android.content.Context,
    uri: android.net.Uri,
    targetDirectory: File,
    fileName: String,
    onProgress: (Float) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        val targetFile = File(targetDirectory, fileName)
        
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            java.io.FileOutputStream(targetFile).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                
                outputStream.flush()
            }
        }
        
        onProgress(1.0f)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
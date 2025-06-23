package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.util.concurrent.TimeUnit
import com.hqsrawmelon.webdavserver.utils.PerformanceUtils

/**
 * 网络诊断工具类 - 优化版本
 */
class NetworkDiagnosticsOptimized(private val context: Context) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    // 缓存网络状态，避免频繁查询
    private val networkStatusFlow = MutableStateFlow<NetworkStatus?>(null)
    
    // 节流器，避免频繁的网络检查
    private val throttler = PerformanceUtils.Throttler(2000L) // 2秒间隔
    
    init {
        // 监听网络变化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateNetworkStatus()
                }
                
                override fun onLost(network: Network) {
                    updateNetworkStatus()
                }
            }
            
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // 忽略注册失败
            }
        }
        
        // 初始化网络状态
        updateNetworkStatus()
    }
    
    private fun updateNetworkStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            val status = checkNetworkStatusInternal()
            networkStatusFlow.value = status
        }
    }
    
    /**
     * 获取网络状态 - 优化版本
     */
    suspend fun checkNetworkStatus(): NetworkStatus {
        return networkStatusFlow.value ?: run {
            val status = checkNetworkStatusInternal()
            networkStatusFlow.value = status
            status
        }
    }
    
    private suspend fun checkNetworkStatusInternal(): NetworkStatus = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "network_status_${System.currentTimeMillis() / 5000}" // 5秒缓存
            PerformanceUtils.getCached(cacheKey) {
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                
                when {
                    networkCapabilities == null -> NetworkStatus("未连接网络", NetworkStatus.Type.DISCONNECTED)
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val ssid = getWifiSSID()
                        NetworkStatus("WiFi 已连接${if (ssid.isNotEmpty()) " ($ssid)" else ""}", NetworkStatus.Type.WIFI)
                    }
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 
                        NetworkStatus("移动数据已连接", NetworkStatus.Type.CELLULAR)
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 
                        NetworkStatus("以太网已连接", NetworkStatus.Type.ETHERNET)
                    else -> NetworkStatus("已连接网络", NetworkStatus.Type.OTHER)
                }
            }
        } catch (e: Exception) {
            NetworkStatus("网络状态检查失败: ${e.message}", NetworkStatus.Type.DISCONNECTED)
        }
    }
    
    private fun getWifiSSID(): String {
        return try {
            // Check if we have the necessary permissions
            val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (!hasLocationPermission) {
                return "需要位置权限"
            }

            val wifiInfo = wifiManager.connectionInfo

            // Handle different Android versions
            when {
                wifiInfo?.ssid == null -> ""
                wifiInfo.ssid == "<unknown ssid>" -> "未知网络"
                wifiInfo.ssid.startsWith("\"") && wifiInfo.ssid.endsWith("\"") ->
                    wifiInfo.ssid.removeSurrounding("\"")
                else -> wifiInfo.ssid
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * 测试端口可用性 - 优化版本
     */
    suspend fun testPort(port: Int, timeout: Int = 3000): PortTestResult = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "port_test_${port}_${System.currentTimeMillis() / 10000}" // 10秒缓存
            PerformanceUtils.getCached(cacheKey) {
                try {
                    val socket = Socket()
                    socket.bind(InetSocketAddress("0.0.0.0", port))
                    socket.close()
                    PortTestResult(true, "端口 $port 可用")
                } catch (e: IOException) {
                    when {
                        e.message?.contains("bind failed: EADDRINUSE") == true ->
                            PortTestResult(false, "端口 $port 已被占用")
                        e.message?.contains("Permission denied") == true ->
                            PortTestResult(false, "端口 $port 权限被拒绝")
                        else -> PortTestResult(false, "端口 $port 测试失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            PortTestResult(false, "端口测试失败: ${e.message}")
        }
    }
    
    /**
     * 获取网络接口信息 - 优化版本
     */
    suspend fun getNetworkInterfaces(): List<NetworkInterfaceInfo> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "network_interfaces_${System.currentTimeMillis() / 30000}" // 30秒缓存
            PerformanceUtils.getCached(cacheKey) {
                val interfaces = mutableListOf<NetworkInterfaceInfo>()
                
                NetworkInterface.getNetworkInterfaces().asSequence().forEach { networkInterface ->
                    try {
                        if (networkInterface.isUp && !networkInterface.isLoopback) {
                            val addresses = networkInterface.inetAddresses
                                .asSequence()
                                .filterIsInstance<Inet4Address>()
                                .filter { !it.isLoopbackAddress }
                                .map { it.hostAddress ?: "" }
                                .toList()

                            if (addresses.isNotEmpty()) {
                                interfaces.add(
                                    NetworkInterfaceInfo(
                                        name = networkInterface.displayName ?: networkInterface.name,
                                        addresses = addresses,
                                        isWifi = networkInterface.name.contains("wlan", ignoreCase = true),
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略单个接口的错误
                    }
                }
                
                interfaces
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Ping测试 - 优化版本
     */
    suspend fun pingTest(host: String = "8.8.8.8", timeout: Int = 3000): PingResult = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "ping_${host}_${System.currentTimeMillis() / 15000}" // 15秒缓存
            PerformanceUtils.getCached(cacheKey) {
                val process = Runtime.getRuntime().exec("ping -c 1 -W $timeout $host")
                val completed = process.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
                
                if (completed && process.exitValue() == 0) {
                    PingResult(true, "网络连通性正常")
                } else {
                    PingResult(false, "网络连通性异常")
                }
            }
        } catch (e: Exception) {
            PingResult(false, "网络测试失败: ${e.message}")
        }
    }
    
    // 数据类保持不变
    data class NetworkStatus(
        val message: String,
        val type: Type,
    ) {
        enum class Type {
            WIFI,
            CELLULAR,
            ETHERNET,
            OTHER,
            DISCONNECTED,
        }
    }

    data class PortTestResult(
        val isAvailable: Boolean,
        val message: String,
    )

    data class NetworkInterfaceInfo(
        val name: String,
        val addresses: List<String>,
        val isWifi: Boolean,
    )

    data class PingResult(
        val success: Boolean,
        val message: String,
    )
}

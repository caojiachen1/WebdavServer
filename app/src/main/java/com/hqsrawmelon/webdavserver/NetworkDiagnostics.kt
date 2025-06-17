package com.hqsrawmelon.webdavserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.NetworkInterface
import java.net.Inet4Address

class NetworkDiagnostics(private val context: Context) {
    
    suspend fun checkNetworkStatus(): NetworkStatus = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
    
    private fun getWifiSSID(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Check if we have the necessary permissions
            val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
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
            e.printStackTrace()
            ""
        }
    }
    
    suspend fun testPort(port: Int): PortTestResult = withContext(Dispatchers.IO) {
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
    
    suspend fun getNetworkInterfaces(): List<NetworkInterfaceInfo> = withContext(Dispatchers.IO) {
        val interfaces = mutableListOf<NetworkInterfaceInfo>()
        
        try {
            NetworkInterface.getNetworkInterfaces().asSequence().forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .map { it.hostAddress ?: "" }
                        .toList()
                    
                    if (addresses.isNotEmpty()) {
                        interfaces.add(
                            NetworkInterfaceInfo(
                                name = networkInterface.displayName ?: networkInterface.name,
                                addresses = addresses,
                                isWifi = networkInterface.name.contains("wlan", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        interfaces
    }
    
    suspend fun pingTest(host: String = "8.8.8.8"): PingResult = withContext(Dispatchers.IO) {
        try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ping -c 1 -W 3000 $host")
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                PingResult(true, "网络连通性正常")
            } else {
                PingResult(false, "网络连通性异常")
            }
        } catch (e: Exception) {
            PingResult(false, "网络测试失败: ${e.message}")
        }
    }
    
    data class NetworkStatus(
        val message: String,
        val type: Type
    ) {
        enum class Type {
            WIFI, CELLULAR, ETHERNET, OTHER, DISCONNECTED
        }
    }
    
    data class PortTestResult(
        val isAvailable: Boolean,
        val message: String
    )
    
    data class NetworkInterfaceInfo(
        val name: String,
        val addresses: List<String>,
        val isWifi: Boolean
    )
    
    data class PingResult(
        val success: Boolean,
        val message: String
    )
}

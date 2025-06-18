package com.hqsrawmelon.webdavserver.utils

import android.content.Context
import android.net.*
import java.net.Inet4Address
import java.net.NetworkInterface

fun getLocalIpAddress(context: Context): String {
    try {
        // Try modern approach first (API 23+)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

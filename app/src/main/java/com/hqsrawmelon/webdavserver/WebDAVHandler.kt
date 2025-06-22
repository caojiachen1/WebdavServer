package com.hqsrawmelon.webdavserver

import fi.iki.elonen.NanoHTTPD.*
import com.hqsrawmelon.webdavserver.utils.WebDAVUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.*
import java.util.zip.GZIPInputStream
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64
import java.util.*

class WebDAVHandler(
    private val rootDir: File,
    private val settingsManager: SettingsManager,
    private val logManager: LogManager
) {
    private val webdavUtils = WebDAVUtils()
    private val failedAttempts = ConcurrentHashMap<String, AttemptTracker>()
    private val blockedIPs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, LockInfo>()
    private val customProperties = ConcurrentHashMap<String, MutableMap<String, String>>()
    
    data class AttemptTracker(var count: Int, var lastAttempt: Long)
    
    data class LockInfo(
        val token: String,
        val owner: String,
        val depth: String,
        val timeout: Long,
        val scope: String = "exclusive",
        val type: String = "write"
    )
    
    // Security utility methods migrated from CustomWebDAVServer
    fun getClientIP(session: IHTTPSession): String {
        val xForwardedFor = session.headers["x-forwarded-for"]
        val xRealIP = session.headers["x-real-ip"]
        return when {
            !xRealIP.isNullOrEmpty() -> xRealIP
            !xForwardedFor.isNullOrEmpty() -> xForwardedFor.split(",")[0].trim()
            else -> session.remoteIpAddress ?: "unknown"
        }
    }

    fun isIPBlocked(clientIP: String): Boolean {
        val blockDuration = runBlocking { settingsManager.blockDuration.first() } * 1000L
        val blockedTime = blockedIPs[clientIP]
        return if (blockedTime != null) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - blockedTime > blockDuration) {
                blockedIPs.remove(clientIP)
                failedAttempts.remove(clientIP)
                false
            } else {
                true
            }
        } else {
            false
        }
    }

    fun isIPAllowed(clientIP: String): Boolean {
        val enableIpWhitelist = runBlocking { settingsManager.enableIpWhitelist.first() }
        if (!enableIpWhitelist) return true

        val ipWhitelist = runBlocking { settingsManager.ipWhitelist.first() }
        return try {
            val whitelistRanges = ipWhitelist.split(",").map { it.trim() }
            whitelistRanges.any { range ->
                if (range.contains("/")) {
                    isIPInCIDR(clientIP, range)
                } else {
                    clientIP == range
                }
            }
        } catch (e: Exception) {
            logManager.logError("Security", "Error checking IP whitelist: ${e.message}")
            true
        }
    }

    fun isIPInCIDR(ip: String, cidr: String): Boolean {
        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false

            val network = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()
            val targetIP = InetAddress.getByName(ip)

            if (network.address.size != targetIP.address.size) return false

            val mask = (-1L shl (32 - prefixLength)).toInt()
            val networkInt = ((network.address[0].toInt() and 0xFF) shl 24) or
                           ((network.address[1].toInt() and 0xFF) shl 16) or
                           ((network.address[2].toInt() and 0xFF) shl 8) or
                           (network.address[3].toInt() and 0xFF)

            val targetInt = ((targetIP.address[0].toInt() and 0xFF) shl 24) or
                          ((targetIP.address[1].toInt() and 0xFF) shl 16) or
                          ((targetIP.address[2].toInt() and 0xFF) shl 8) or
                          (targetIP.address[3].toInt() and 0xFF)

            return (networkInt and mask) == (targetInt and mask)
        } catch (e: Exception) {
            return false
        }
    }

    fun recordFailedAttempt(clientIP: String) {
        val maxFailedAttempts = runBlocking { settingsManager.maxFailedAttempts.first() }
        val currentTime = System.currentTimeMillis()

        val tracker = failedAttempts.getOrPut(clientIP) { AttemptTracker(0, currentTime) }
        tracker.count++
        tracker.lastAttempt = currentTime

        if (tracker.count >= maxFailedAttempts) {
            blockedIPs[clientIP] = currentTime
            logManager.logWarn("Security", "IP $clientIP blocked after $maxFailedAttempts failed attempts")
        }
    }

    fun isAuthenticated(authHeader: String?): Boolean {
        val allowAnonymous = runBlocking { settingsManager.allowAnonymous.first() }
        if (allowAnonymous) return true
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false

        try {
            val encodedCredentials = authHeader.substring(6)
            val decodedCredentials = String(Base64.decode(encodedCredentials, Base64.DEFAULT))
            val parts = decodedCredentials.split(":", limit = 2)

            val username = runBlocking { settingsManager.username.first() }
            val password = runBlocking { settingsManager.password.first() }
            return parts.size == 2 && parts[0] == username && parts[1] == password
        } catch (e: Exception) {
            return false
        }
    }

    private fun checkSecurityPreconditions(session: IHTTPSession): Response? {
        val clientIP = getClientIP(session)
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }

        if (isIPBlocked(clientIP)) {
            if (enableLogging) logManager.logWarn("Security", "Blocked IP $clientIP attempted access")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP blocked due to repeated failed attempts")
        }

        if (!isIPAllowed(clientIP)) {
            if (enableLogging) logManager.logWarn("Security", "IP $clientIP not in whitelist")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP not allowed")
        }

        val authHeader = session.headers["authorization"]
        if (!isAuthenticated(authHeader)) {
            recordFailedAttempt(clientIP)
            if (enableLogging) logManager.logWarn("Auth", "Authentication failed for IP $clientIP")
            val response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Authentication required")
            response.addHeader("WWW-Authenticate", "Basic realm=\"WebDAV\"")
            return response
        }

        // Reset failed attempts on successful auth
        failedAttempts.remove(clientIP)
        if (enableLogging) logManager.logInfo("Auth", "Authentication successful for IP $clientIP")
        return null
    }

    fun handleOptions(settingsManager: SettingsManager): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        response.addHeader("Allow", "OPTIONS, GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK")
        response.addHeader("DAV", "1,2")
        
        runBlocking {
            if (settingsManager.enableCors.first()) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK")
                response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Depth, Destination, Lock-Token, Timeout")
            }
        }
        
        return response
    }
    
    fun handleGet(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        if (file.isDirectory) {
            return webdavUtils.generateDirectoryListing(file, uri)
        }
        
        try {
            val fis = FileInputStream(file)
            val mimeType = webdavUtils.getMimeTypeForFile(file.name)
            
            return runBlocking {
                if (settingsManager.enableCompression.first()) {
                    val gzipStream = GZIPInputStream(fis)
                    val response = newChunkedResponse(Response.Status.OK, mimeType, gzipStream)
                    response.addHeader("Content-Encoding", "gzip")
                    response.addHeader("Accept-Ranges", "bytes")
                    response
                } else {
                    val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
                    response.addHeader("Content-Length", file.length().toString())
                    response.addHeader("Accept-Ranges", "bytes")
                    response
                }
            }
        } catch (e: IOException) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file")
        }
    }
    
    fun handlePut(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
        // Check if resource is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse
        
        val fileExists = file.exists()
        
        try {
            file.parentFile?.mkdirs()
            
            // Get content length from headers
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            
            // Write file directly from input stream without parsing body
            val bufferSize = runBlocking { settingsManager.bufferSize.first() }
        session.inputStream?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream, bufferSize)
            }
        }
            
            // Return appropriate status code
            val status = if (fileExists) Response.Status.NO_CONTENT else Response.Status.CREATED
            return newFixedLengthResponse(status, "text/plain", "")
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error creating file: ${e.message}")
        }
    }
    
    fun handleDelete(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        
        // Check if resource is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse
        
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
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
    
    fun handleMkCol(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
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
    
    fun handlePropFind(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val depth = session.headers["depth"] ?: "1"
        val xml = webdavUtils.generatePropFindResponse(file, uri, depth)
        
        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
    }

    fun handlePropPatch(uri: String): Response {
        val properties = customProperties.getOrPut(uri) { mutableMapOf() }
        // Example: Add logic to parse and update properties from the request body
        return newFixedLengthResponse(Response.Status.OK, "application/xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>$uri</D:href>
                    <D:propstat>
                        <D:prop/>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent())
    }
    
    fun handleMove(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val destination = session.headers["destination"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))
        
        // Check if source is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse
        
        // Parse destination URL more robustly
        val destUri = try {
            val url = java.net.URL(destination)
            URLDecoder.decode(url.path, "UTF-8")
        } catch (e: Exception) {
            // Fallback parsing
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
    
    fun handleCopy(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val destination = session.headers["destination"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))
        
        // Parse destination URL more robustly
        val destUri = try {
            val url = java.net.URL(destination)
            URLDecoder.decode(url.path, "UTF-8")
        } catch (e: Exception) {
            // Fallback parsing
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
    
    fun handleLock(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Resource not found")
        }
        
        // Parse request body to get lock information
        val lockInfo = parseLockRequest(session)
        if (lockInfo == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid lock request")
        }
        
        // Check if resource is already locked
        val existingLock = locks[decodedUri]
        if (existingLock != null && System.currentTimeMillis() < existingLock.timeout) {
            // Resource is already locked by someone else
            if (existingLock.token != lockInfo.token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/xml", generateLockErrorResponse(decodedUri))
            } else {
                // Refresh existing lock
                val refreshedLock = existingLock.copy(timeout = System.currentTimeMillis() + parseTimeout(session.headers["timeout"]))
                locks[decodedUri] = refreshedLock
                return generateLockResponse(decodedUri, refreshedLock)
            }
        }
        
        // Create new lock
        val lockToken = "opaquelocktoken:" + UUID.randomUUID().toString()
        val timeout = System.currentTimeMillis() + parseTimeout(session.headers["timeout"])
        val depth = session.headers["depth"] ?: "0"
        val owner = lockInfo.owner
        
        val newLock = LockInfo(lockToken, owner, depth, timeout)
        locks[decodedUri] = newLock
        
        // Log lock creation
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        if (enableLogging) {
            logManager.logInfo("WebDAV", "Lock created for $decodedUri by $owner, token: $lockToken")
        }
        
        return generateLockResponse(decodedUri, newLock)
    }
    
    fun handleUnlock(session: IHTTPSession, uri: String): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val lockToken = session.headers["lock-token"]
        
        if (lockToken.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing Lock-Token header")
        }
        
        // Clean lock token format (remove angle brackets if present)
        val cleanToken = lockToken.removePrefix("<").removeSuffix(">")
        
        val existingLock = locks[decodedUri]
        if (existingLock == null) {
            return newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "No lock found for resource")
        }
        
        if (existingLock.token != cleanToken) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid lock token")
        }
        
        // Remove the lock
        locks.remove(decodedUri)
        
        // Log unlock
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        if (enableLogging) {
            logManager.logInfo("WebDAV", "Lock removed for $decodedUri, token: $cleanToken")
        }
        
        return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
    }
    
    private fun checkLockConstraints(session: IHTTPSession, uri: String): Response? {
        val existingLock = locks[uri]
        if (existingLock != null && System.currentTimeMillis() < existingLock.timeout) {
            val ifHeader = session.headers["if"]
            val lockToken = session.headers["lock-token"]
            
            // Check if the request includes the correct lock token
            val hasValidToken = ifHeader?.contains(existingLock.token) == true || 
                               lockToken?.removePrefix("<")?.removeSuffix(">") == existingLock.token
            
            if (!hasValidToken) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/xml", generateLockErrorResponse(uri))
            }
        }
        return null
    }
    
    private fun parseLockRequest(session: IHTTPSession): LockInfo? {
        return try {
            // For simplicity, create a basic lock info
            // In a full implementation, you would parse the XML request body
            val owner = session.headers["authorization"]?.let { auth ->
                if (auth.startsWith("Basic ")) {
                    val credentials = String(Base64.decode(auth.substring(6), Base64.DEFAULT))
                    credentials.split(":")[0]
                } else "unknown"
            } ?: "anonymous"
            
            LockInfo("", owner, "0", 0L)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseTimeout(timeoutHeader: String?): Long {
        return try {
            when {
                timeoutHeader == null -> 3600000L // Default 1 hour
                timeoutHeader.startsWith("Second-") -> timeoutHeader.substring(7).toLong() * 1000L
                timeoutHeader == "Infinite" -> Long.MAX_VALUE
                else -> 3600000L // Default 1 hour
            }
        } catch (e: Exception) {
            3600000L // Default 1 hour
        }
    }
    
    private fun generateLockResponse(uri: String, lockInfo: LockInfo): Response {
        val timeoutSeconds = (lockInfo.timeout - System.currentTimeMillis()) / 1000
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:prop xmlns:D="DAV:">
                <D:lockdiscovery>
                    <D:activelock>
                        <D:locktype><D:${lockInfo.type}/></D:locktype>
                        <D:lockscope><D:${lockInfo.scope}/></D:lockscope>
                        <D:depth>${lockInfo.depth}</D:depth>
                        <D:owner>${lockInfo.owner}</D:owner>
                        <D:timeout>Second-$timeoutSeconds</D:timeout>
                        <D:locktoken>
                            <D:href>${lockInfo.token}</D:href>
                        </D:locktoken>
                    </D:activelock>
                </D:lockdiscovery>
            </D:prop>
        """.trimIndent()
        
        val response = newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", xml)
        response.addHeader("Lock-Token", "<${lockInfo.token}>")
        return response
    }
    
    private fun generateLockErrorResponse(uri: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:error xmlns:D="DAV:">
                <D:lock-token-submitted>
                    <D:href>$uri</D:href>
                </D:lock-token-submitted>
            </D:error>
        """.trimIndent()
    }
    
    // Clean up expired locks periodically
    fun cleanupExpiredLocks() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = locks.filterValues { it.timeout < currentTime }.keys
        expiredKeys.forEach { locks.remove(it) }
        
        if (expiredKeys.isNotEmpty()) {
            val enableLogging = runBlocking { settingsManager.enableLogging.first() }
            if (enableLogging) {
                logManager.logInfo("WebDAV", "Cleaned up ${expiredKeys.size} expired locks")
            }
        }
    }
}

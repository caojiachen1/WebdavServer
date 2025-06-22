package com.hqsrawmelon.webdavserver.server

import android.util.Base64
import com.hqsrawmelon.webdavserver.*
import com.hqsrawmelon.webdavserver.utils.WebDAVUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.InetAddress
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CustomWebDAVServer(
    private val port: Int,
    private val rootDir: File,
    private val username: String,
    private val password: String,
    private val allowAnonymous: Boolean = false,
    private val settingsManager: SettingsManager
) : NanoHTTPD(port) {

    private val webdavUtils = WebDAVUtils()
    private val logManager = LogManager(settingsManager)
    private val failedAttempts = ConcurrentHashMap<String, AttemptTracker>()
    private val blockedIPs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, String>() // Map to store locks
    private val customProperties = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val webdavHandler = WebDAVHandler(rootDir, settingsManager, logManager)

    data class AttemptTracker(var count: Int, var lastAttempt: Long)
    
    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        logManager.logInfo("Server", "CustomWebDAVServer initialized on port $port")
    }

    override fun serve(session: IHTTPSession): Response {
        val clientIP = getClientIP(session)
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        
        if (enableLogging) {
            logManager.logInfo("Request", "Client ${clientIP} accessing ${session.uri}")
        }
        
        // Check if IP is blocked
        if (isIPBlocked(clientIP)) {
            if (enableLogging) {
                logManager.logWarn("Security", "Blocked IP $clientIP attempted access")
            }
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP blocked due to repeated failed attempts")
        }
        
        // Check IP whitelist if enabled
        if (!isIPAllowed(clientIP)) {
            if (enableLogging) {
                logManager.logWarn("Security", "IP $clientIP not in whitelist")
            }
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP not allowed")
        }
        
        try {
            val response = handleRequest(session, clientIP)
            
            // Add CORS headers if enabled
            val enableCors = runBlocking { settingsManager.enableCors.first() }
            if (enableCors) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE")
                response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Depth, Destination")
            }
            
            return response
        } catch (e: Exception) {
            if (enableLogging) {
                logManager.logError("Server", "Error processing request from $clientIP: ${e.message}")
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal server error")
        }
    }
    
    private fun getClientIP(session: IHTTPSession): String {
        // Try to get real IP from headers (for proxy setups)
        val xForwardedFor = session.headers["x-forwarded-for"]
        val xRealIP = session.headers["x-real-ip"]
        
        return when {
            !xRealIP.isNullOrEmpty() -> xRealIP
            !xForwardedFor.isNullOrEmpty() -> xForwardedFor.split(",")[0].trim()
            else -> session.remoteIpAddress ?: "unknown"
        }
    }
    
    private fun isIPBlocked(clientIP: String): Boolean {
        val blockDuration = runBlocking { settingsManager.blockDuration.first() } * 1000L
        val blockedTime = blockedIPs[clientIP]
        
        return if (blockedTime != null) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - blockedTime > blockDuration) {
                // Unblock IP after duration
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
    
    private fun isIPAllowed(clientIP: String): Boolean {
        val enableIpWhitelist = runBlocking { settingsManager.enableIpWhitelist.first() }
        if (!enableIpWhitelist) return true
        
        val ipWhitelist = runBlocking { settingsManager.ipWhitelist.first() }
        
        // Simple CIDR matching (basic implementation)
        return try {
            val whitelistRanges = ipWhitelist.split(",").map { it.trim() }
            whitelistRanges.any { range ->
                if (range.contains("/")) {
                    // CIDR notation
                    isIPInCIDR(clientIP, range)
                } else {
                    // Direct IP match
                    clientIP == range
                }
            }
        } catch (e: Exception) {
            logManager.logError("Security", "Error checking IP whitelist: ${e.message}")
            true // Allow access if whitelist check fails
        }
    }
    
    private fun isIPInCIDR(ip: String, cidr: String): Boolean {
        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false
            
            val network = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()
            val targetIP = InetAddress.getByName(ip)
            
            if (network.address.size != targetIP.address.size) return false
            
            val mask = (-1L shl (32 - prefixLength)).toInt()
            val networkBytes = network.address
            val targetBytes = targetIP.address
            
            val networkInt = ((networkBytes[0].toInt() and 0xFF) shl 24) or
                           ((networkBytes[1].toInt() and 0xFF) shl 16) or
                           ((networkBytes[2].toInt() and 0xFF) shl 8) or
                           (networkBytes[3].toInt() and 0xFF)
            
            val targetInt = ((targetBytes[0].toInt() and 0xFF) shl 24) or
                          ((targetBytes[1].toInt() and 0xFF) shl 16) or
                          ((targetBytes[2].toInt() and 0xFF) shl 8) or
                          (targetBytes[3].toInt() and 0xFF)
            
            return (networkInt and mask) == (targetInt and mask)
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun recordFailedAttempt(clientIP: String) {
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
    
    private fun handleRequest(session: IHTTPSession, clientIP: String): Response {
        val method = session.method
        val uri = session.uri
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        
        // Handle authentication if not anonymous
        if (!allowAnonymous) {
            val authHeader = session.headers["authorization"]
            if (!isAuthenticated(authHeader)) {
                recordFailedAttempt(clientIP)
                if (enableLogging) {
                    logManager.logWarn("Auth", "Authentication failed for IP $clientIP")
                }
                val response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Authentication required")
                response.addHeader("WWW-Authenticate", "Basic realm=\"WebDAV\"")
                return response
            } else {
                // Reset failed attempts on successful auth
                failedAttempts.remove(clientIP)
                if (enableLogging) {
                    logManager.logInfo("Auth", "Authentication successful for IP $clientIP")
                }
            }
        }
        
        return when (method) {
            Method.GET -> handleGet(uri)
            Method.PUT -> handlePut(session, uri)
            Method.DELETE -> handleDelete(uri)
            Method.OPTIONS -> handleOptions()
            else -> {
                // Handle WebDAV methods
                when (session.headers["method"] ?: method.name) {
                    "PROPFIND" -> handlePropfind(session, uri)
                    "PROPPATCH" -> handleProppatch(uri)
                    "MKCOL" -> handleMkcol(uri)
                    "COPY" -> handleCopy(session, uri)
                    "MOVE" -> handleMove(session, uri)
                    "LOCK" -> handleLock(session, uri)
                    "UNLOCK" -> handleUnlock(session, uri)
                    else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
                }
            }
        }
    }

    private fun isAuthenticated(authHeader: String?): Boolean {
        if (allowAnonymous) return true
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false
        
        try {
            val encodedCredentials = authHeader.substring(6)
            val decodedCredentials = String(Base64.decode(encodedCredentials, Base64.DEFAULT))
            val parts = decodedCredentials.split(":", limit = 2)
            
            return parts.size == 2 && parts[0] == username && parts[1] == password
        } catch (e: Exception) {
            return false
        }
    }

    private fun handleGet(uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        return when {
            !file.exists() -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            file.isDirectory -> {
                // Return directory listing as HTML
                val html = generateDirectoryListing(file, uri)
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
            else -> {
                try {
                    val mimeType = webdavUtils.getMimeTypeForFile(file.name)
                    val inputStream = FileInputStream(file)
                    newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                } catch (e: IOException) {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading file")
                }
            }
        }
    }

    private fun handlePut(session: IHTTPSession, uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        return try {
            file.parentFile?.mkdirs()
            
            val body = HashMap<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"]
            if (postData != null) {
                file.writeText(postData)
            } else {
                // Handle binary data
                val inputStream = session.inputStream
                val outputStream = FileOutputStream(file)
                inputStream.copyTo(outputStream)
                outputStream.close()
            }
            
            logManager.logInfo("File", "File uploaded: ${file.absolutePath}")
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "File created")
        } catch (e: Exception) {
            logManager.logError("File", "Error uploading file: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error creating file")
        }
    }

    private fun handleDelete(uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        return if (file.exists()) {
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted) {
                logManager.logInfo("File", "File/directory deleted: ${file.absolutePath}")
                newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            } else {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error deleting file")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
    }

    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        response.addHeader("Allow", "OPTIONS, GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE")
        response.addHeader("DAV", "1,2")
        return response
    }

    private fun handleLock(session: IHTTPSession, uri: String): Response {
        val lockToken = "urn:uuid:" + UUID.randomUUID().toString()
        locks[uri] = lockToken
        return newFixedLengthResponse(Response.Status.OK, "application/xml", """<?xml version=\"1.0\" encoding=\"utf-8\"?>
            <D:prop xmlns:D=\"DAV:\">
                <D:lockdiscovery>
                    <D:activelock>
                        <D:locktype><D:write/></D:locktype>
                        <D:lockscope><D:exclusive/></D:lockscope>
                        <D:depth>infinity</D:depth>
                        <D:owner>${session.headers["owner"] ?: "unknown"}</D:owner>
                        <D:timeout>Second-3600</D:timeout>
                        <D:locktoken><D:href>$lockToken</D:href></D:locktoken>
                    </D:activelock>
                </D:lockdiscovery>
            </D:prop>""")
    }

    private fun handleUnlock(session: IHTTPSession, uri: String): Response {
        val lockToken = session.headers["Lock-Token"]?.removeSurrounding("<", ">")
        return if (locks[uri] == lockToken) {
            locks.remove(uri)
            newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        } else {
            newFixedLengthResponse(Response.Status.PRECONDITION_FAILED, MIME_PLAINTEXT, "Lock token does not match")
        }
    }

    private fun handlePropfind(session: IHTTPSession, uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        
        val depth = session.headers["depth"] ?: "1"
        val xml = generatePropfindXml(file, uri, depth)
        
        val response = newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml", xml)
        response.addHeader("Content-Type", "application/xml; charset=utf-8")
        return response
    }

    private fun handleProppatch(uri: String): Response {
        val properties = customProperties.getOrPut(uri) { mutableMapOf() }
        // Example: Add logic to parse and update properties from the request body
        return newFixedLengthResponse(Response.Status.OK, "application/xml", """<?xml version=\"1.0\" encoding=\"UTF-8\"?>
            <D:multistatus xmlns:D=\"DAV:\">
                <D:response>
                    <D:href>$uri</D:href>
                    <D:propstat>
                        <D:prop/>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>""")
    }

    private fun handleMkcol(uri: String): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        return if (file.mkdirs()) {
            logManager.logInfo("File", "Directory created: ${file.absolutePath}")
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Directory created")
        } else {
            newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "Cannot create directory")
        }
    }

    private fun handleCopy(session: IHTTPSession, uri: String): Response {
        val source = File(rootDir, uri.removePrefix("/"))
        val destination = session.headers["destination"]?.let { dest ->
            File(rootDir, dest.removePrefix("/"))
        } ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing destination")
        
        return try {
            if (source.isDirectory) {
                source.copyRecursively(destination)
            } else {
                source.copyTo(destination)
            }
            logManager.logInfo("File", "File/directory copied from ${source.absolutePath} to ${destination.absolutePath}")
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Resource copied")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error copying resource")
        }
    }

    private fun handleMove(session: IHTTPSession, uri: String): Response {
        val source = File(rootDir, uri.removePrefix("/"))
        val destination = session.headers["destination"]?.let { dest ->
            File(rootDir, dest.removePrefix("/"))
        } ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing destination")
        
        return if (source.renameTo(destination)) {
            logManager.logInfo("File", "File/directory moved from ${source.absolutePath} to ${destination.absolutePath}")
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Resource moved")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error moving resource")
        }
    }

    // ...existing code for helper methods...
    
    private fun generateDirectoryListing(dir: File, uri: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><title>Directory: $uri</title></head><body>")
        sb.append("<h1>Directory: $uri</h1><hr>")
        sb.append("<ul>")
        
        if (uri != "/") {
            val parentUri = File(uri).parent?.replace("\\", "/") ?: "/"
            sb.append("<li><a href=\"$parentUri\">[Parent Directory]</a></li>")
        }
        
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val fileName = file.name
            val fileUri = if (uri.endsWith("/")) uri + fileName else "$uri/$fileName"
            val displayName = if (file.isDirectory) "[$fileName]" else fileName
            sb.append("<li><a href=\"$fileUri\">$displayName</a></li>")
        }
        
        sb.append("</ul><hr></body></html>")
        return sb.toString()
    }
    
    private fun generatePropfindXml(file: File, uri: String, depth: String): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<D:multistatus xmlns:D=\"DAV:\">\n")
        
        addResourceToXml(xml, file, uri)
        
        if (depth != "0" && file.isDirectory) {
            file.listFiles()?.forEach { child ->
                val childUri = if (uri.endsWith("/")) uri + child.name else "$uri/${child.name}"
                addResourceToXml(xml, child, childUri)
            }
        }
        
        xml.append("</D:multistatus>")
        return xml.toString()
    }
    
    private fun addResourceToXml(xml: StringBuilder, file: File, uri: String) {
        xml.append("<D:response>\n")
        xml.append("<D:href>$uri</D:href>\n")
        xml.append("<D:propstat>\n")
        xml.append("<D:prop>\n")
        xml.append("<D:resourcetype>")
        if (file.isDirectory) xml.append("<D:collection/>")
        xml.append("</D:resourcetype>\n")
        xml.append("<D:getcontentlength>${if (file.isFile) file.length() else 0}</D:getcontentlength>\n")
        xml.append("<D:getlastmodified>${Date(file.lastModified())}</D:getlastmodified>\n")
        if (file.isFile) {
            xml.append("<D:getcontenttype>${webdavUtils.getMimeTypeForFile(file.name)}</D:getcontenttype>\n")
        }
        xml.append("</D:prop>\n")
        xml.append("<D:status>HTTP/1.1 200 OK</D:status>\n")
        xml.append("</D:propstat>\n")
        xml.append("</D:response>\n")
    }
}

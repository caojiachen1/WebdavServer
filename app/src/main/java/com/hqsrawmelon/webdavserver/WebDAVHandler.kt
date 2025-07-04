package com.hqsrawmelon.webdavserver

import android.util.Base64
import com.hqsrawmelon.webdavserver.utils.WebDAVUtils
import fi.iki.elonen.NanoHTTPD.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class WebDAVHandler(
    private val rootDir: File,
    private val settingsManager: SettingsManager,
    private val logManager: LogManager,
    private val serverUsername: String = "",
    private val serverPassword: String = "",
) {
    private val webdavUtils = WebDAVUtils()
    private val failedAttempts = ConcurrentHashMap<String, AttemptTracker>()
    private val blockedIPs = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, LockInfo>()
    private val customProperties = ConcurrentHashMap<String, MutableMap<String, String>>()

    data class AttemptTracker(
        var count: Int,
        var lastAttempt: Long,
    )

    data class LockInfo(
        val token: String,
        val owner: String,
        val depth: String,
        val timeout: Long,
        val scope: String = "exclusive",
        val type: String = "write",
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

    fun isIPInCIDR(
        ip: String,
        cidr: String,
    ): Boolean {
        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false

            val network = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()
            val targetIP = InetAddress.getByName(ip)

            if (network.address.size != targetIP.address.size) return false

            val mask = (-1L shl (32 - prefixLength)).toInt()
            val networkInt =
                ((network.address[0].toInt() and 0xFF) shl 24) or
                    ((network.address[1].toInt() and 0xFF) shl 16) or
                    ((network.address[2].toInt() and 0xFF) shl 8) or
                    (network.address[3].toInt() and 0xFF)

            val targetInt =
                ((targetIP.address[0].toInt() and 0xFF) shl 24) or
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

            // 优先使用服务器传递的用户名和密码
            val username =
                if (serverUsername.isNotEmpty()) {
                    serverUsername
                } else {
                    runBlocking { settingsManager.username.first() }
                }
            val password =
                if (serverPassword.isNotEmpty()) {
                    serverPassword
                } else {
                    runBlocking { settingsManager.password.first() }
                }

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
        
        // Essential Windows WebDAV headers - order matters for compatibility
        response.addHeader("MS-Author-Via", "DAV")
        response.addHeader("DAV", "1,2,3")
        response.addHeader("Server", "Microsoft-IIS/10.0") // Mimic IIS for better Windows compatibility
        response.addHeader("Allow", "OPTIONS, TRACE, GET, HEAD, DELETE, PUT, POST, COPY, MOVE, MKCOL, PROPFIND, PROPPATCH, LOCK, UNLOCK, SEARCH")
        response.addHeader("Public", "OPTIONS, TRACE, GET, HEAD, DELETE, PUT, POST, COPY, MOVE, MKCOL, PROPFIND, PROPPATCH, LOCK, UNLOCK, SEARCH")
        
        // Connection management
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Keep-Alive", "timeout=300, max=1000")
        
        // Cache control for Windows WebDAV
        response.addHeader("Cache-Control", "private")
        
        // Additional WebDAV compliance headers
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Type", "text/html")
        
        // Date header is important for Windows WebDAV
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        response.addHeader("Date", dateFormat.format(Date()))

        runBlocking {
            if (settingsManager.enableCors.first()) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader(
                    "Access-Control-Allow-Methods",
                    "GET, POST, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK",
                )
                response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Depth, Destination, Lock-Token, Timeout, User-Agent, Content-Range, X-File-Size, X-File-Name, X-Requested-With")
                response.addHeader("Access-Control-Expose-Headers", "DAV, MS-Author-Via, Server")
            }
        }

        return response
    }

    fun handleGet(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        val userAgent = session.headers["user-agent"] ?: ""
        val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")

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
                    response.addHeader("ETag", "\"${file.lastModified().toString(16)}-${file.length().toString(16)}\"")
                    response.addHeader("Last-Modified", formatDateForHTTP(file.lastModified()))
                    addStandardWebDAVHeaders(response, isWindowsWebDAV)
                    response
                } else {
                    val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
                    response.addHeader("Accept-Ranges", "bytes")
                    response.addHeader("ETag", "\"${file.lastModified().toString(16)}-${file.length().toString(16)}\"")
                    response.addHeader("Last-Modified", formatDateForHTTP(file.lastModified()))
                    addStandardWebDAVHeaders(response, isWindowsWebDAV)
                    response
                }
            }
        } catch (e: IOException) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file")
        }
    }

    fun handlePut(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        // Special handling for Windows WebDAV paste operations
        val windowsPasteResponse = handleWindowsPasteOperation(session, uri)
        if (windowsPasteResponse != null) return windowsPasteResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))

        // Check if resource is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse

        val fileExists = file.exists()
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }

        try {
            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            // Get content length and other headers
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
            val contentType = session.headers["content-type"] ?: "application/octet-stream"
            val transferEncoding = session.headers["transfer-encoding"]
            val expectHeader = session.headers["expect"]
            
            if (enableLogging) {
                logManager.logInfo("WebDAV", "PUT request for $decodedUri, Content-Length: $contentLength, Content-Type: $contentType, Transfer-Encoding: $transferEncoding, Expect: $expectHeader")
            }
            
            // Handle Expect: 100-continue for Windows WebDAV clients
            if (expectHeader?.contains("100-continue", ignoreCase = true) == true) {
                if (enableLogging) {
                    logManager.logInfo("WebDAV", "Client expects 100-continue, this will be handled by NanoHTTPD")
                }
            }

            var bytesWritten = 0L
            val bufferSize = runBlocking { settingsManager.bufferSize.first() }
            
            // Handle Windows WebDAV client special cases
            val userAgent = session.headers["user-agent"] ?: ""
            val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")
            
            if (enableLogging && isWindowsWebDAV) {
                logManager.logInfo("WebDAV", "Windows WebDAV client detected: $userAgent")
            }
            
            // For WebDAV PUT, we need to properly handle the request body
            try {
                // Handle zero-length files (common with Windows WebDAV client)
                if (contentLength == 0L) {
                    file.createNewFile()
                    bytesWritten = 0L
                    if (enableLogging) {
                        logManager.logInfo("WebDAV", "Created zero-length file: $decodedUri")
                    }
                } else {
                    // Create temporary file first to ensure atomic operation
                    val tempFile = File(file.parentFile, ".tmp_${file.name}_${System.currentTimeMillis()}")
                    
                    try {
                        // Try multiple approaches to read the request body
                        var dataRead = false
                        
                        // Approach 1: Try to use NanoHTTPD's parseBody for multipart/form data
                        try {
                            val files = mutableMapOf<String, String>()
                            val params = mutableMapOf<String, String>()
                            
                            if (enableLogging) {
                                logManager.logInfo("WebDAV", "Attempting parseBody approach...")
                            }
                            
                            session.parseBody(files)
                            
                            if (files.isNotEmpty()) {
                                val tempUploadFile = File(files.values.first())
                                if (tempUploadFile.exists() && tempUploadFile.length() > 0) {
                                    tempUploadFile.copyTo(tempFile, overwrite = true)
                                    bytesWritten = tempFile.length()
                                    tempUploadFile.delete()
                                    dataRead = true
                                    
                                    if (enableLogging) {
                                        logManager.logInfo("WebDAV", "Successfully used parseBody approach, size: $bytesWritten bytes")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (enableLogging) {
                                logManager.logWarn("WebDAV", "parseBody approach failed: ${e.message}")
                            }
                        }
                        
                        // Approach 2: Direct stream reading if parseBody didn't work
                        if (!dataRead) {
                            if (enableLogging) {
                                logManager.logInfo("WebDAV", "Attempting direct stream reading...")
                            }
                            
                            // Get the raw input stream from the session
                            val inputStream = session.inputStream
                            if (inputStream != null) {
                                FileOutputStream(tempFile).use { outputStream ->
                                    val buffer = ByteArray(bufferSize)
                                    var totalBytes = 0L
                                    var bytesRead: Int
                                    
                                    // Read until EOF or expected content length is reached
                                    while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                                        outputStream.write(buffer, 0, bytesRead)
                                        totalBytes += bytesRead
                                        
                                        // For safety, stop reading if we've read significantly more than expected
                                        if (contentLength > 0 && totalBytes > contentLength + 1024) {
                                            if (enableLogging) {
                                                logManager.logWarn("WebDAV", "Read more than expected, stopping at $totalBytes bytes")
                                            }
                                            break
                                        }
                                    }
                                    
                                    outputStream.flush()
                                    outputStream.fd.sync()
                                    
                                    bytesWritten = totalBytes
                                    dataRead = totalBytes > 0
                                    
                                    if (enableLogging) {
                                        logManager.logInfo("WebDAV", "Direct stream reading completed: $bytesWritten bytes")
                                    }
                                }
                            } else {
                                if (enableLogging) {
                                    logManager.logWarn("WebDAV", "Input stream is null")
                                }
                            }
                        }
                        
                        // Approach 3: If we still don't have data and content-length suggests we should
                        if (!dataRead && contentLength > 0) {
                            if (enableLogging) {
                                logManager.logWarn("WebDAV", "No data read but content-length is $contentLength, creating empty file")
                            }
                            tempFile.createNewFile()
                            bytesWritten = 0L
                        } else if (!dataRead) {
                            // Create empty file for zero-length case
                            tempFile.createNewFile()
                            bytesWritten = 0L
                        }
                        
                        // Atomically move temp file to final location
                        if (tempFile.exists()) {
                            if (file.exists()) {
                                file.delete()
                            }
                            val moved = tempFile.renameTo(file)
                            if (!moved) {
                                // Fallback: copy then delete
                                tempFile.copyTo(file, overwrite = true)
                                tempFile.delete()
                            }
                            
                            if (enableLogging) {
                                logManager.logInfo("WebDAV", "Successfully moved temp file to final location: ${file.exists()}")
                            }
                        }
                        
                    } finally {
                        // Clean up temp file if it still exists
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }
                    
                    // Verify content length if specified and we got data
                    if (contentLength > 0 && bytesWritten != contentLength) {
                        if (enableLogging) {
                            logManager.logWarn("WebDAV", "Content length mismatch: expected $contentLength, got $bytesWritten")
                        }
                        // For Windows WebDAV compatibility, if the difference is small, accept it
                        if (Math.abs(bytesWritten - contentLength) > 1024) { // Allow 1KB difference
                            if (enableLogging) {
                                logManager.logError("WebDAV", "Content length mismatch too large, rejecting upload")
                            }
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Content length mismatch")
                        }
                    }
                }
                
                if (enableLogging) {
                    logManager.logInfo("WebDAV", "Successfully wrote $bytesWritten bytes to $decodedUri (expected: $contentLength)")
                }
                
            } catch (e: IOException) {
                if (enableLogging) {
                    logManager.logError("WebDAV", "IO Error during PUT operation: ${e.message}")
                }
                throw e
            } catch (e: Exception) {
                if (enableLogging) {
                    logManager.logError("WebDAV", "Unexpected error during PUT operation: ${e.message}")
                }
                throw e
            }

            // Ensure file is properly synced and accessible before responding
            if (file.exists()) {
                // Verify file is readable and has correct size
                try {
                    val actualSize = file.length()
                    val canRead = file.canRead()
                    val canWrite = file.canWrite()
                    
                    if (enableLogging) {
                        logManager.logInfo("WebDAV", "File verification - Size: $actualSize, CanRead: $canRead, CanWrite: $canWrite")
                    }
                    
                    // For Windows WebDAV, ensure file is not locked
                    if (!canRead) {
                        if (enableLogging) {
                            logManager.logError("WebDAV", "File created but not readable: $decodedUri")
                        }
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "File created but not accessible")
                    }
                } catch (e: Exception) {
                    if (enableLogging) {
                        logManager.logError("WebDAV", "Error verifying file: ${e.message}")
                    }
                }
            }
            
            // Return appropriate status code - ensure we always return a valid response
            val status = if (fileExists) Response.Status.NO_CONTENT else Response.Status.CREATED
            val response = newFixedLengthResponse(status, "text/plain", "")
            
            // Add essential WebDAV compliance headers for Windows
            addStandardWebDAVHeaders(response, isWindowsWebDAV)
            
            // Windows WebDAV specific headers for PUT operations
            if (isWindowsWebDAV) {
                response.addHeader("Accept-Ranges", "bytes")
                // Override cache control for PUT operations
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                response.addHeader("Pragma", "no-cache")
                response.addHeader("Expires", "0")
            }
            
            // Add ETag and content info if file exists
            if (file.exists()) {
                // Use a more stable ETag format
                val etag = "\"${file.lastModified().toString(16)}-${file.length().toString(16)}\""
                response.addHeader("ETag", etag)
                
                // Add Last-Modified header with proper format
                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("GMT")
                response.addHeader("Last-Modified", dateFormat.format(Date(file.lastModified())))
                
                // Add Location header for newly created resources
                if (!fileExists) {
                    response.addHeader("Location", uri)
                }
                
                if (enableLogging) {
                    logManager.logInfo("WebDAV", "PUT completed successfully - File: $decodedUri, Size: ${file.length()}, ETag: $etag")
                }
            } else {
                if (enableLogging) {
                    logManager.logWarn("WebDAV", "File does not exist after PUT operation: $decodedUri, but returning success status")
                }
                // Even if file doesn't exist, return success to avoid client errors
                response.addHeader("Location", uri)
            }
            
            return response
        } catch (e: Exception) {
            if (enableLogging) {
                logManager.logError("WebDAV", "PUT failed for $decodedUri: ${e.message}")
                e.printStackTrace()
            }
            
            // For Windows WebDAV compatibility, try to return a more specific error
            val errorMessage = when {
                e is IOException -> "IO Error: ${e.message}"
                e is SecurityException -> "Permission denied"
                e.message?.contains("space", ignoreCase = true) == true -> "Insufficient storage space"
                else -> "Error creating file: ${e.message}"
            }
            
            val response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", errorMessage)
            
            // Even in error cases, add WebDAV headers for Windows compatibility
            response.addHeader("MS-Author-Via", "DAV")
            response.addHeader("DAV", "1,2,3")
            val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("GMT")
            response.addHeader("Date", dateFormat.format(Date()))
            
            return response
        }
    }

    fun handleDelete(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val userAgent = session.headers["user-agent"] ?: ""
        val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")

        // Check if resource is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse

        val file = File(rootDir, decodedUri.removePrefix("/"))

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val deleted =
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }

        val response = if (deleted) {
            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error deleting file")
        }
        
        addStandardWebDAVHeaders(response, isWindowsWebDAV)
        return response
    }

    fun handleMkCol(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        val userAgent = session.headers["user-agent"] ?: ""
        val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")

        if (enableLogging) {
            logManager.logInfo("WebDAV", "MKCOL request for $decodedUri (file: ${file.absolutePath})")
        }

        if (file.exists()) {
            if (enableLogging) {
                logManager.logWarn("WebDAV", "MKCOL: Directory already exists $decodedUri")
            }
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Directory already exists")
        }

        val created = file.mkdirs()
        if (enableLogging) {
            logManager.logInfo("WebDAV", "MKCOL result for $decodedUri: $created")
        }
        
        val response = if (created) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Directory created")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error creating directory")
        }
        
        addStandardWebDAVHeaders(response, isWindowsWebDAV)
        return response
    }

    fun handlePropFind(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        
        // Get Windows WebDAV client info
        val userAgent = session.headers["user-agent"] ?: ""
        val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")

        if (enableLogging) {
            logManager.logInfo("WebDAV", "PROPFIND request for $decodedUri (file: ${file.absolutePath}), Windows client: $isWindowsWebDAV")
        }

        // Handle root directory case
        if (decodedUri == "/" || decodedUri.isEmpty()) {
            val rootFile = rootDir
            if (!rootFile.exists()) {
                rootFile.mkdirs()
            }
            val depth = session.headers["depth"] ?: "1"
            val xml = webdavUtils.generatePropFindResponse(rootFile, "/", depth)
            
            val response = newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
            addStandardWebDAVHeaders(response, isWindowsWebDAV)
            return response
        }

        if (!file.exists()) {
            if (enableLogging) {
                logManager.logWarn("WebDAV", "PROPFIND: File not found $decodedUri")
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val depth = session.headers["depth"] ?: "1"
        if (enableLogging) {
            logManager.logInfo("WebDAV", "PROPFIND depth: $depth for ${file.name}")
        }
        
        val xml = webdavUtils.generatePropFindResponse(file, uri, depth)

        val response = newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
        addStandardWebDAVHeaders(response, isWindowsWebDAV)
        return response
    }
    
    private fun addStandardWebDAVHeaders(response: Response, isWindowsWebDAV: Boolean) {
        // Essential headers for Windows WebDAV compatibility
        response.addHeader("MS-Author-Via", "DAV")
        response.addHeader("DAV", "1,2,3")
        
        if (isWindowsWebDAV) {
            response.addHeader("Server", "Microsoft-IIS/10.0")
            response.addHeader("Cache-Control", "private")
            response.addHeader("Public", "OPTIONS, TRACE, GET, HEAD, DELETE, PUT, POST, COPY, MOVE, MKCOL, PROPFIND, PROPPATCH, LOCK, UNLOCK, SEARCH")
        } else {
            response.addHeader("Server", "WebDAVServer/1.0")
            response.addHeader("Cache-Control", "no-cache")
        }
        
        // Date header is critical for Windows WebDAV
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        response.addHeader("Date", dateFormat.format(Date()))
        
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Keep-Alive", "timeout=300, max=1000")
    }

    fun handlePropPatch(uri: String): Response {
        customProperties.getOrPut(uri) { mutableMapOf() }
        // For Windows compatibility, return basic PROPPATCH response
        val response = newFixedLengthResponse(
            Response.Status.MULTI_STATUS,
            "application/xml",
            """
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
            """.trimIndent(),
        )
        response.addHeader("MS-Author-Via", "DAV")
        return response
    }

    fun handleMove(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val destination =
            session.headers["destination"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))

        // Check if source is locked
        val lockResponse = checkLockConstraints(session, decodedUri)
        if (lockResponse != null) return lockResponse

        // Parse destination URL more robustly
        val destUri =
            try {
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

    fun handleCopy(
        session: IHTTPSession,
        uri: String,
    ): Response {
        val securityResponse = checkSecurityPreconditions(session)
        if (securityResponse != null) return securityResponse

        val destination =
            session.headers["destination"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination")
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val sourceFile = File(rootDir, decodedUri.removePrefix("/"))

        // Parse destination URL more robustly
        val destUri =
            try {
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

    fun handleLock(
        session: IHTTPSession,
        uri: String,
    ): Response {
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

    fun handleUnlock(
        session: IHTTPSession,
        uri: String,
    ): Response {
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

        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        response.addHeader("MS-Author-Via", "DAV")
        return response
    }

    private fun checkLockConstraints(
        session: IHTTPSession,
        uri: String,
    ): Response? {
        val existingLock = locks[uri]
        if (existingLock != null && System.currentTimeMillis() < existingLock.timeout) {
            val ifHeader = session.headers["if"]
            val lockToken = session.headers["lock-token"]

            // Check if the request includes the correct lock token
            val hasValidToken =
                ifHeader?.contains(existingLock.token) == true ||
                    lockToken?.removePrefix("<")?.removeSuffix(">") == existingLock.token

            if (!hasValidToken) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/xml", generateLockErrorResponse(uri))
            }
        }
        return null
    }

    private fun parseLockRequest(session: IHTTPSession): LockInfo? =
        try {
            // For simplicity, create a basic lock info
            // In a full implementation, you would parse the XML request body
            val owner =
                session.headers["authorization"]?.let { auth ->
                    if (auth.startsWith("Basic ")) {
                        val credentials = String(Base64.decode(auth.substring(6), Base64.DEFAULT))
                        credentials.split(":")[0]
                    } else {
                        "unknown"
                    }
                } ?: "anonymous"

            LockInfo("", owner, "0", 0L)
        } catch (e: Exception) {
            null
        }

    private fun parseTimeout(timeoutHeader: String?): Long =
        try {
            when {
                timeoutHeader == null -> 3600000L // Default 1 hour
                timeoutHeader.startsWith("Second-") -> timeoutHeader.substring(7).toLong() * 1000L
                timeoutHeader == "Infinite" -> Long.MAX_VALUE
                else -> 3600000L // Default 1 hour
            }
        } catch (e: Exception) {
            3600000L // Default 1 hour
        }

    private fun generateLockResponse(
        @Suppress("UNUSED_PARAMETER") uri: String,
        lockInfo: LockInfo,
    ): Response {
        val timeoutSeconds = (lockInfo.timeout - System.currentTimeMillis()) / 1000
        val xml =
            """
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

    private fun generateLockErrorResponse(uri: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:error xmlns:D="DAV:">
            <D:lock-token-submitted>
                <D:href>$uri</D:href>
            </D:lock-token-submitted>
        </D:error>
        """.trimIndent()

    private fun formatDateForHTTP(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        return dateFormat.format(Date(timestamp))
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

    // Special handling for Windows WebDAV paste operations
    fun handleWindowsPasteOperation(
        session: IHTTPSession,
        uri: String,
    ): Response? {
        val userAgent = session.headers["user-agent"] ?: ""
        val isWindowsWebDAV = userAgent.contains("Microsoft-WebDAV-MiniRedir") || userAgent.contains("DavClnt")
        
        if (!isWindowsWebDAV) return null
        
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        
        // Check for Windows-specific headers that indicate paste operation
        val overwrite = session.headers["overwrite"] ?: "T"
        val ifMatch = session.headers["if-match"]
        val ifNoneMatch = session.headers["if-none-match"]
        
        if (enableLogging) {
            logManager.logInfo("WebDAV", "Windows paste operation detected - Overwrite: $overwrite, If-Match: $ifMatch, If-None-Match: $ifNoneMatch")
        }
        
        // Pre-check destination accessibility
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
        
        // Ensure parent directory exists and is writable
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
            
            if (!parent.canWrite()) {
                if (enableLogging) {
                    logManager.logError("WebDAV", "Parent directory not writable: ${parent.absolutePath}")
                }
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Directory not writable")
            }
        }
        
        return null // Continue with normal PUT processing
    }
}

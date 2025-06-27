package com.hqsrawmelon.webdavserver.server

import com.hqsrawmelon.webdavserver.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.util.concurrent.ConcurrentHashMap

class CustomWebDAVServer(
    private val port: Int,
    private val rootDir: File,
    private val username: String,
    private val password: String,
    private val allowAnonymous: Boolean = false,
    private val settingsManager: SettingsManager,
) : NanoHTTPD(port) {
    private val logManager = LogManager(settingsManager)
    private val failedAttempts = ConcurrentHashMap<String, AttemptTracker>()
    private val blockedIPs = ConcurrentHashMap<String, Long>()
    private val webdavHandler = WebDAVHandler(rootDir, settingsManager, logManager, username, password)

    data class AttemptTracker(
        var count: Int,
        var lastAttempt: Long,
    )

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        logManager.logInfo(
            "Server",
            "CustomWebDAVServer initialized on port $port with username: ${if (username.isNotEmpty()) "set" else "not set"}, allowAnonymous: $allowAnonymous",
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val clientIP = webdavHandler.getClientIP(session)
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }

        if (enableLogging) {
            logManager.logInfo("Request", "Client $clientIP accessing ${session.uri}")
        }

        // Check if IP is blocked
        if (webdavHandler.isIPBlocked(clientIP)) {
            if (enableLogging) {
                logManager.logWarn("Security", "Blocked IP $clientIP attempted access")
            }
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP blocked due to repeated failed attempts")
        }

        // Check IP whitelist if enabled
        if (!webdavHandler.isIPAllowed(clientIP)) {
            if (enableLogging) {
                logManager.logWarn("Security", "IP $clientIP not in whitelist")
            }
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "IP not allowed")
        }

        try {
            val response = handleRequest(session, clientIP)

            // Add Windows WebDAV compatibility headers
            response.addHeader("MS-Author-Via", "DAV")
            response.addHeader("DAV", "1,2,3")
            response.addHeader("Server", "WebDAVServer/1.0")
            
            // Add connection management headers
            response.addHeader("Connection", "keep-alive")
            response.addHeader("Keep-Alive", "timeout=300, max=1000")

            // Add CORS headers if enabled
            val enableCors = runBlocking { settingsManager.enableCors.first() }
            if (enableCors) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader(
                    "Access-Control-Allow-Methods",
                    "GET, POST, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK",
                )
                response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Depth, Destination, Lock-Token, Timeout, User-Agent, Content-Range, X-File-Size, X-File-Name")
            }

            return response
        } catch (e: Exception) {
            if (enableLogging) {
                logManager.logError("Server", "Error processing request from $clientIP: ${e.message}")
                e.printStackTrace()
            }
            
            // Create a proper error response with WebDAV headers
            val response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal server error: ${e.message}")
            
            // Add essential WebDAV headers even for errors
            response.addHeader("MS-Author-Via", "DAV")
            response.addHeader("DAV", "1,2,3")
            response.addHeader("Server", "WebDAVServer/1.0")
            response.addHeader("Connection", "close") // Close connection on error
            
            // Add date header
            val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            dateFormat.timeZone = java.util.TimeZone.getTimeZone("GMT")
            response.addHeader("Date", dateFormat.format(java.util.Date()))
            
            return response
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

    private fun handleRequest(
        session: IHTTPSession,
        clientIP: String,
    ): Response {
        val method = session.method
        val uri = session.uri
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }

        // Handle authentication if not anonymous
        if (!allowAnonymous) {
            val authHeader = session.headers["authorization"]
            if (!webdavHandler.isAuthenticated(authHeader)) {
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
            Method.GET -> webdavHandler.handleGet(session, uri)
            Method.PUT -> webdavHandler.handlePut(session, uri)
            Method.DELETE -> webdavHandler.handleDelete(session, uri)
            Method.OPTIONS -> webdavHandler.handleOptions(settingsManager)
            Method.POST -> {
                // Some WebDAV clients may send POST for certain operations
                val webdavMethod = session.headers["x-http-method-override"] ?: session.headers["x-method-override"]
                when (webdavMethod) {
                    "PROPFIND" -> webdavHandler.handlePropFind(session, uri)
                    "PROPPATCH" -> webdavHandler.handlePropPatch(uri)
                    "MKCOL" -> webdavHandler.handleMkCol(session, uri)
                    "COPY" -> webdavHandler.handleCopy(session, uri)
                    "MOVE" -> webdavHandler.handleMove(session, uri)
                    "LOCK" -> webdavHandler.handleLock(session, uri)
                    "UNLOCK" -> webdavHandler.handleUnlock(session, uri)
                    else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
                }
            }
            else -> {
                // Handle WebDAV methods - check both method name and headers
                val methodName = method.name
                val headerMethod = session.headers["method"]
                val actualMethod = headerMethod ?: methodName

                // Log the method for debugging
                if (enableLogging) {
                    logManager.logInfo("WebDAV", "Processing method: $actualMethod (original: $methodName, header: $headerMethod)")
                }

                when (actualMethod) {
                    "PROPFIND" -> webdavHandler.handlePropFind(session, uri)
                    "PROPPATCH" -> webdavHandler.handlePropPatch(uri)
                    "MKCOL" -> webdavHandler.handleMkCol(session, uri)
                    "COPY" -> webdavHandler.handleCopy(session, uri)
                    "MOVE" -> webdavHandler.handleMove(session, uri)
                    "LOCK" -> webdavHandler.handleLock(session, uri)
                    "UNLOCK" -> webdavHandler.handleUnlock(session, uri)
                    else -> {
                        if (enableLogging) {
                            logManager.logWarn("WebDAV", "Unsupported method: $actualMethod")
                        }
                        newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed: $actualMethod")
                    }
                }
            }
        }
    }
}

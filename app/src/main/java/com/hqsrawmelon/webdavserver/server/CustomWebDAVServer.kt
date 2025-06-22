package com.hqsrawmelon.webdavserver.server

import com.hqsrawmelon.webdavserver.*
import com.hqsrawmelon.webdavserver.utils.WebDAVUtils
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
    private val settingsManager: SettingsManager
) : NanoHTTPD(port) {
    private val logManager = LogManager(settingsManager)
    private val failedAttempts = ConcurrentHashMap<String, AttemptTracker>()
    private val blockedIPs = ConcurrentHashMap<String, Long>()
    private val webdavHandler = WebDAVHandler(rootDir, settingsManager, logManager)

    data class AttemptTracker(var count: Int, var lastAttempt: Long)
    
    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        logManager.logInfo("Server", "CustomWebDAVServer initialized on port $port")
    }

    override fun serve(session: IHTTPSession): Response {
        val clientIP = webdavHandler.getClientIP(session)
        val enableLogging = runBlocking { settingsManager.enableLogging.first() }
        
        if (enableLogging) {
            logManager.logInfo("Request", "Client ${clientIP} accessing ${session.uri}")
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
            else -> {
                // Handle WebDAV methods
                when (session.headers["method"] ?: method.name) {
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
        }
    }
}

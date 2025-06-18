package com.hqsrawmelon.webdavserver.server

import android.util.Base64
import com.hqsrawmelon.webdavserver.SettingsManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CustomWebDAVServer(
    port: Int, 
    private val rootDir: File, 
    private val username: String, 
    private val password: String,
    private val allowAnonymous: Boolean = false,
    private val settingsManager: SettingsManager? = null
) : NanoHTTPD(port) {
    
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        val headers = session.headers
        
        // Check authentication for all methods except OPTIONS
        if (method != Method.OPTIONS && !allowAnonymous && !isAuthenticated(headers)) {
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

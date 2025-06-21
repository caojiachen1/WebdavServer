package com.hqsrawmelon.webdavserver

import fi.iki.elonen.NanoHTTPD.*
import com.hqsrawmelon.webdavserver.utils.WebDAVUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

class WebDAVHandler(private val rootDir: File) {
    
    private val webdavUtils = WebDAVUtils()
    
    fun handleOptions(settingsManager: SettingsManager): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        response.addHeader("Allow", "GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, OPTIONS")
        
        runBlocking {
            if (settingsManager.enableCors.first()) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type, Depth, Authorization, If-Match, If-None-Match")
            }
        }
        
        return response
    }
    
    fun handleGet(uri: String, settingsManager: SettingsManager): Response {
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
    
    fun handlePut(session: IHTTPSession, uri: String, settingsManager: SettingsManager): Response {
        val decodedUri = URLDecoder.decode(uri, "UTF-8")
        val file = File(rootDir, decodedUri.removePrefix("/"))
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
    
    fun handleDelete(uri: String): Response {
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
    
    fun handleMkCol(uri: String): Response {
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
    
    fun handlePropFind(uri: String, headers: Map<String, String>): Response {
        val file = File(rootDir, uri.removePrefix("/"))
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val depth = headers["depth"] ?: "1"
        val xml = webdavUtils.generatePropFindResponse(file, uri, depth)
        
        return newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
    }
    
    fun handlePropPatch(): Response {
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
    
    fun handleMove(session: IHTTPSession, uri: String): Response {
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
        
        destFile.parentFile?.mkdirs()
        val moved = sourceFile.renameTo(destFile)
        
        return if (moved) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Move failed")
        }
    }
    
    fun handleCopy(session: IHTTPSession, uri: String): Response {
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
}

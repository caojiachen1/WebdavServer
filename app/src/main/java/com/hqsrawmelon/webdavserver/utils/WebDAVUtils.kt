package com.hqsrawmelon.webdavserver.utils

import fi.iki.elonen.NanoHTTPD.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WebDAVUtils {
    fun generatePropFindResponse(file: File, uri: String, depth: String): String {
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
        val contentType = if (!file.isDirectory) "<D:getcontenttype>${getMimeTypeForFile(file.name)}</D:getcontenttype>" else ""
        
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
    
    fun generateDirectoryListing(dir: File, uri: String): Response {
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
    
    fun getMimeTypeForFile(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "txt" -> "text/plain"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }
}
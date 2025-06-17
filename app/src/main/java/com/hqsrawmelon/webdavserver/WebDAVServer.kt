package com.hqsrawmelon.webdavserver

import fi.iki.elonen.NanoHTTPD
import java.io.File

class WebDAVServer(port: Int, private val rootDir: File) : NanoHTTPD(port) {
    
    private val webdavHandler = WebDAVHandler(rootDir)
    
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        val headers = session.headers
        
        val response = when (method) {
            Method.OPTIONS -> webdavHandler.handleOptions()
            Method.GET -> webdavHandler.handleGet(uri)
            Method.PUT -> webdavHandler.handlePut(session, uri)
            Method.DELETE -> webdavHandler.handleDelete(uri)
            Method.MKCOL -> webdavHandler.handleMkCol(uri)
            Method.PROPFIND -> webdavHandler.handlePropFind(uri, headers)
            Method.PROPPATCH -> webdavHandler.handlePropPatch()
            Method.MOVE -> webdavHandler.handleMove(session, uri)
            Method.COPY -> webdavHandler.handleCopy(session, uri)
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
}

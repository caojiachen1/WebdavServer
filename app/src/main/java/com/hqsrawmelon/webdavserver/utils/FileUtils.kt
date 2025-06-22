package com.hqsrawmelon.webdavserver.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File

suspend fun copyFileFromUri(
    context: Context,
    uri: Uri,
    targetDirectory: File,
    fileName: String,
    onProgress: (Float) -> Unit,
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val targetFile = File(targetDirectory, fileName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                java.io.FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    outputStream.flush()
                }
            }

            onProgress(1.0f)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

package com.cardnudge.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual suspend fun httpPostForm(url: String, form: String, userAgent: String): String? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 40_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        conn.setRequestProperty("User-Agent", userAgent)
        conn.outputStream.use { it.write(form.encodeToByteArray()) }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream.use { it.readBytes().decodeToString() } else null
    } catch (e: java.io.IOException) {
        null
    } finally {
        conn.disconnect()
    }
}

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.cardnudge.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy
import kotlin.coroutines.resume

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual suspend fun httpPostForm(url: String, form: String, userAgent: String): String? {
    val nsUrl = NSURL.URLWithString(url) ?: return null
    val request = NSMutableURLRequest.requestWithURL(nsUrl, NSURLRequestReloadIgnoringLocalCacheData, 40.0)
    request.setHTTPMethod("POST")
    request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField = "Content-Type")
    request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
    request.setHTTPBody(form.encodeToByteArray().toNSData())
    return suspendCancellableCoroutine { cont ->
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val ok = error == null && (response as? NSHTTPURLResponse)?.statusCode == 200L
            cont.resume(if (ok) data?.toByteArray()?.decodeToString() else null)
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

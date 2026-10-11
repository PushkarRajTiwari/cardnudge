package com.cardnudge.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.cardnudge.domain.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Holds the application context (never an Activity) for file access outside composition. */
object AndroidFiles {
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    internal fun photosDir(): File = File(checkNotNull(app) { "AndroidFiles.init not called" }.filesDir, "cards").apply { mkdirs() }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (String?) -> Unit): PhotoPicker {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onPicked)
    val cameraFile = remember { File(app.cacheDir, "camera").apply { mkdirs() }.resolve("capture.jpg") }

    fun importUri(uri: Uri?) {
        if (uri == null) return callback(null)
        scope.launch {
            callback(withContext(Dispatchers.IO) { importPhoto(app, uri) })
            cameraFile.delete()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { importUri(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        importUri(if (ok) Uri.fromFile(cameraFile) else null)
    }

    return remember(gallery, camera) {
        object : PhotoPicker {
            override fun choosePhoto() =
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

            override fun takePhoto() =
                camera.launch(FileProvider.getUriForFile(app, "${app.packageName}.photos", cameraFile))
        }
    }
}

/** Decodes [uri] upright and downscaled to [PHOTO_MAX_PX], then saves it as a JPEG. */
private fun importPhoto(context: Context, uri: Uri): String? = try {
    val bitmap = decodeScaled(context, uri, PHOTO_MAX_PX)
    val name = "${newId()}.jpg"
    File(AndroidFiles.photosDir(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    bitmap.recycle()
    name
} catch (e: Exception) {
    Log.w("CardNudge", "Couldn't import photo", e)
    null
}

private fun decodeScaled(context: Context, uri: Uri, maxPx: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies EXIF rotation, so camera shots come out upright.
        val source = if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
        else ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxPx) {
                val scale = maxPx.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxPx) }
    return checkNotNull(context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) })
}

private fun sampleSize(longest: Int, maxPx: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= maxPx) sample *= 2
    return sample
}

actual fun loadCardPhoto(name: String, maxWidthPx: Int): ImageBitmap? {
    val file = File(AndroidFiles.photosDir(), name)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, maxWidthPx) }
    return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}

actual fun deleteCardPhoto(name: String) {
    File(AndroidFiles.photosDir(), name).delete()
}

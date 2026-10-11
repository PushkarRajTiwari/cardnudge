package com.cardnudge.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Opens the system camera or photo library; the result arrives through the callback given to [rememberPhotoPicker]. */
interface PhotoPicker {
    fun takePhoto()
    fun choosePhoto()
}

/**
 * Returns a picker tied to the calling composable. A picked image is downscaled, saved in
 * app-private storage and reported as a file name (or null if cancelled).
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (String?) -> Unit): PhotoPicker

/** Decodes a saved card photo, downsampled to about [maxWidthPx]. Call off the main thread. */
expect fun loadCardPhoto(name: String, maxWidthPx: Int): ImageBitmap?

expect fun deleteCardPhoto(name: String)

/** Longest side of saved photos; plenty for a full-width card and small in memory. */
const val PHOTO_MAX_PX = 1200

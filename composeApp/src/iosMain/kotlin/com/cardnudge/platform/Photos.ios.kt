@file:OptIn(ExperimentalForeignApi::class)

package com.cardnudge.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.cardnudge.domain.newId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.VisionKit.VNDocumentCameraScan
import platform.VisionKit.VNDocumentCameraViewController
import platform.VisionKit.VNDocumentCameraViewControllerDelegateProtocol
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun rememberPhotoPicker(onPicked: (String?) -> Unit): PhotoPicker {
    val callback = rememberUpdatedState(onPicked)
    return remember { IosPhotoPicker { callback.value(it) } }
}

private class IosPhotoPicker(private val onPicked: (String?) -> Unit) : PhotoPicker {
    // UIKit holds picker delegates weakly; keep ours alive only while the picker is on screen.
    private var delegate: PickerDelegate? = null
    private var scanDelegate: ScanDelegate? = null

    override fun scanCard() {
        // Apple's document scanner finds the card's edges and flattens it; the simulator lacks it.
        if (!VNDocumentCameraViewController.supported) return takePhoto()
        val scanner = VNDocumentCameraViewController()
        val d = ScanDelegate { image ->
            scanDelegate = null
            onPicked(image?.let { savePhoto(it, landscape = true) })
        }
        scanDelegate = d
        scanner.delegate = d
        topViewController()?.presentViewController(scanner, animated = true, completion = null)
    }

    override fun takePhoto() {
        val camera = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        // The simulator has no camera; fall back to the library there.
        present(if (UIImagePickerController.isSourceTypeAvailable(camera)) camera else library())
    }

    override fun choosePhoto() = present(library())

    private fun library() = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary

    private fun present(source: UIImagePickerControllerSourceType) {
        val picker = UIImagePickerController()
        picker.sourceType = source
        val d = PickerDelegate { image ->
            delegate = null
            onPicked(image?.let(::savePhoto))
        }
        delegate = d
        picker.delegate = d
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(private val done: (UIImage?) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, null)
        done(image)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, null)
        done(null)
    }
}

private class ScanDelegate(private val done: (UIImage?) -> Unit) : NSObject(), VNDocumentCameraViewControllerDelegateProtocol {

    override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFinishWithScan: VNDocumentCameraScan) {
        val image = if (didFinishWithScan.pageCount > 0uL) didFinishWithScan.imageOfPageAtIndex(0uL) else null
        controller.dismissViewControllerAnimated(true, null)
        done(image)
    }

    override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFailWithError: NSError) {
        controller.dismissViewControllerAnimated(true, null)
        done(null)
    }

    override fun documentCameraViewControllerDidCancel(controller: VNDocumentCameraViewController) {
        controller.dismissViewControllerAnimated(true, null)
        done(null)
    }
}

private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

private fun photosDir(): String {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val dir = "$docs/cards"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}

/**
 * Redraws the image upright at most [PHOTO_MAX_PX] on its longest side and saves it as JPEG.
 * With [landscape], a portrait image is turned a quarter so it sits like a card.
 */
private fun savePhoto(original: UIImage, landscape: Boolean = false): String? {
    val (ow, oh) = original.size.useContents { width to height }
    val image = if (landscape && oh > ow) {
        upright(original)?.CGImage?.let { UIImage.imageWithCGImage(it, 1.0, UIImageOrientation.UIImageOrientationLeft) } ?: original
    } else {
        original
    }
    val (w, h) = image.size.useContents { width to height }
    val scale = minOf(1.0, PHOTO_MAX_PX / maxOf(w, h))
    val tw = w * scale
    val th = h * scale
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(tw, th), true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, tw, th)) // Applies the photo's orientation.
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val data = scaled?.let { UIImageJPEGRepresentation(it, 0.88) } ?: return null
    val name = "${newId()}.jpg"
    return if (data.writeToFile("${photosDir()}/$name", atomically = true)) name else null
}

/** Bakes the image's orientation into its pixels so it can be re-oriented reliably. */
private fun upright(image: UIImage): UIImage? {
    val (w, h) = image.size.useContents { width to height }
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, w, h))
    val out = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return out
}

actual fun loadCardPhoto(name: String, maxWidthPx: Int): ImageBitmap? {
    // Photos are already downscaled when saved, so maxWidthPx needs no extra work here.
    val data = NSData.dataWithContentsOfFile("${photosDir()}/$name") ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    val bytes = ByteArray(size)
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

actual fun deleteCardPhoto(name: String) {
    NSFileManager.defaultManager.removeItemAtPath("${photosDir()}/$name", error = null)
}

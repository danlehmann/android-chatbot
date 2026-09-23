package net.daniellehmann.localchat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Attached images live as JPEGs in app-private storage (files/images) and are
 * referenced from messages by file name. They are downscaled on import so they
 * are cheap to send through the tunnel and to keep around.
 */
class ImageStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "images").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    /** Decodes, rotates upright, downscales and stores [uri]; returns the stored file name. */
    fun import(uri: Uri, maxSide: Int = MAX_SIDE): String {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not decode image")

        val rotation = runCatching {
            resolver.openInputStream(uri).use { ExifInterface(it!!).rotationDegrees() }
        }.getOrDefault(0)

        val scale = minOf(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        file(name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return name
    }

    fun dataUrl(name: String): String =
        "data:image/jpeg;base64," + Base64.encodeToString(file(name).readBytes(), Base64.NO_WRAP)

    /** Deletes stored images that no message or pending draft refers to. */
    fun retainOnly(referenced: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    /** Temporary target for the camera app, shared via FileProvider. */
    fun newCameraFile(): File =
        File(app.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot-${System.currentTimeMillis()}.jpg") }

    private fun ExifInterface.rotationDegrees(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    companion object {
        /** Long side in px; a common vision-model input size. */
        const val MAX_SIDE = 1568
        /** Rough character-budget cost of one image when trimming history. */
        const val BUDGET_CHARS_PER_IMAGE = 3000
    }
}

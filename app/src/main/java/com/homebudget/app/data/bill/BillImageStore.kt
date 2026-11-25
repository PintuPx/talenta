package com.homebudget.app.data.bill

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bill photos, stored only in the app's private files directory (files/bills). They are excluded
 * from Android cloud backup (see data_extraction_rules.xml) and from the app's backup file.
 *
 * Photos are re-encoded on import: rotated upright using EXIF, scaled to at most [MAX_EDGE] pixels
 * and saved as JPEG. That keeps storage small and removes metadata such as GPS location.
 */
@Singleton
class BillImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "bills").apply { mkdirs() }
    private val captureDir: File get() = File(context.cacheDir, "capture").apply { mkdirs() }

    /** A fresh content:// URI the camera app can write a photo to. */
    fun newCaptureUri(): Uri {
        captureDir.listFiles()?.forEach { it.delete() } // leftovers from abandoned captures
        val file = File(captureDir, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Copies the photo at [source] (camera capture or picked image) into private storage. Returns its file name. */
    suspend fun import(source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds, decodeStream always returns null; the size is reported in [bounds].
        val stream = resolver.openInputStream(source) ?: throw IOException("The photo could not be opened")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The file is not a supported image")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("The photo could not be decoded")

        val orientation = runCatching {
            resolver.openInputStream(source)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val upright = scaleAndRotate(decoded, orientation)

        val name = "bill-${UUID.randomUUID()}.jpg"
        val out = File(dir, name)
        try {
            out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: IOException) {
            out.delete()
            throw e
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
        if (source.authority == "${context.packageName}.fileprovider") captureDir.listFiles()?.forEach { it.delete() }
        name
    }

    fun file(name: String): File? = File(dir, name).takeIf { isSafeName(name) && it.isFile }

    /** Decodes a stored photo for display, downsampled so its longer edge is about [maxEdge] pixels. */
    suspend fun loadBitmap(name: String, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(name) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(name: String?) {
        if (name != null && isSafeName(name)) File(dir, name).delete()
    }

    /** Deletes every stored photo whose name is not in [keep] (e.g. after a restore or clear). */
    fun deleteAllExcept(keep: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun isSafeName(name: String) = name.matches(Regex("""[A-Za-z0-9._-]+""")) && !name.startsWith(".")

    private fun scaleAndRotate(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longest = maxOf(src.width, src.height)
        if (longest > MAX_EDGE) {
            val s = MAX_EDGE.toFloat() / longest
            m.postScale(s, s)
        }
        return if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        /** Large enough for small receipt print to stay readable for OCR. */
        const val MAX_EDGE = 2400
        const val JPEG_QUALITY = 88
    }
}

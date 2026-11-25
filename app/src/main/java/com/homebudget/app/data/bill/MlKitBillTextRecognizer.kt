package com.homebudget.app.data.bill

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.homebudget.app.domain.bill.BillTextRecognizer
import com.homebudget.app.domain.bill.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2

/**
 * Text recognition with ML Kit's bundled Latin-script model. The model ships inside the app, so it
 * runs entirely on the device and needs no download or network. Photos are already stored upright
 * (see [BillImageStore]), so rotation is 0.
 */
@Singleton
class MlKitBillTextRecognizer @Inject constructor() : BillTextRecognizer {

    override suspend fun recognize(image: File): List<OcrLine> {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(image.path) }
            ?: throw IOException("The bill photo could not be read")
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine { cont ->
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(IOException("Text recognition failed", it)) }
            }
            return text.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox
                    // Corner points are clockwise from top-left; the top edge gives the text angle.
                    val corners = line.cornerPoints
                    val angle = if (corners != null && corners.size >= 2) {
                        Math.toDegrees(atan2((corners[1].y - corners[0].y).toDouble(), (corners[1].x - corners[0].x).toDouble())).toFloat()
                    } else 0f
                    if (box == null) OcrLine(line.text) else OcrLine(line.text, box.left, box.top, box.right, box.bottom, angle)
                }
            }
        } finally {
            // The bitmap is left to the GC: if this coroutine is cancelled, ML Kit may still be reading it.
            client.close()
        }
    }
}

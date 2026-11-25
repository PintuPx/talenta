package com.homebudget.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homebudget.app.data.bill.MlKitBillTextRecognizer
import com.homebudget.app.domain.bill.ParsedItem
import com.homebudget.app.domain.bill.ReceiptParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Runs the real ML Kit bundled text recognizer on a rendered receipt image, on the device, and
 * parses the result. Proves recognition works with the app's manifest (no INTERNET permission).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceOcrTest {

    private fun renderReceipt(file: File) {
        val bmp = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f; typeface = Typeface.MONOSPACE }
        var y = 80f
        fun row(left: String, right: String? = null) {
            c.drawText(left, 40f, y, p)
            if (right != null) c.drawText(right, 860f - p.measureText(right), y, p)
            y += 70f
        }
        row("FRESH MART")
        row("Date: 18/11/2026")
        row("Veggie", "40.00")
        row("Tomato", "30.00")
        row("Milk", "56.00")
        row("Bread", "45.00")
        row("Grand Total", "171.00")
        row("Cash", "200.00")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun recognisesAndParsesARenderedReceipt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "ocr-test.png")
        renderReceipt(file)

        val lines = MlKitBillTextRecognizer().recognize(file)
        assertTrue("no text recognised", lines.isNotEmpty())
        val r = ReceiptParser.parse(lines)

        assertEquals(r.text, LocalDate.of(2026, 11, 18), r.date)
        assertEquals(r.text, 17_100L, r.total)
        assertEquals(
            r.text,
            listOf(ParsedItem("Veggie", 4_000), ParsedItem("Tomato", 3_000), ParsedItem("Milk", 5_600), ParsedItem("Bread", 4_500)),
            r.items,
        )
    }
}

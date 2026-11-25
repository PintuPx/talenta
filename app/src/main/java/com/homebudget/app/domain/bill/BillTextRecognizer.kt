package com.homebudget.app.domain.bill

import java.io.File

/** Reads text from a bill photo on the device. Implemented with ML Kit; faked in tests. */
interface BillTextRecognizer {
    /** @throws java.io.IOException if the image cannot be read or recognition fails. */
    suspend fun recognize(image: File): List<OcrLine>
}

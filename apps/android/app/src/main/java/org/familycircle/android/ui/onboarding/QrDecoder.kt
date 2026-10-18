package org.familycircle.android.ui.onboarding

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code from a camera luminance plane (the Y plane of YUV_420_888) with ZXing core.
 * Pure JVM code, unit-tested with generated QR codes. Not thread-safe: one instance per analyser.
 */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * @param data luminance bytes, at least `rowStride * (height - 1) + width` long.
     * @param rowStride bytes per row (may be larger than [width] on real cameras).
     * @return the text, or null if no QR code was found. Light-on-dark codes are tried as well.
     */
    fun decode(data: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (data.size < rowStride * (height - 1) + width) return null
        val buffer = if (data.size >= rowStride * height) data else data.copyOf(rowStride * height)
        val source = PlanarYUVLuminanceSource(buffer, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null // NotFoundException, ChecksumException, FormatException
    } finally {
        reader.reset()
    }
}

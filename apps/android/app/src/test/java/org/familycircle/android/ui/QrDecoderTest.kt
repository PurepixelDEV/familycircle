package org.familycircle.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.familycircle.android.api.QrLink
import org.familycircle.android.ui.onboarding.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    /** Renders [text] as a luminance plane like a camera frame (with padding bytes per row). */
    private fun frame(text: String, size: Int = 320, rowPadding: Int = 0, invert: Boolean = false): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val stride = size + rowPadding
        val data = ByteArray(stride * size) { 0x55 } // padding bytes are garbage
        for (y in 0 until size) for (x in 0 until size) {
            val dark = matrix[x, y] != invert
            data[y * stride + x] = if (dark) 20 else 230.toByte()
        }
        return Triple(data, stride, size)
    }

    @Test
    fun `decodes a pairing QR code into a QrLink`() {
        val url = "https://fc.example.org/pair?code=K7QF-2M9X-HD4R"
        val (data, stride, size) = frame(url)
        val text = QrDecoder().decode(data, stride, size, size)
        assertEquals(url, text)
        assertEquals(QrLink.Pair("https://fc.example.org", "K7QF-2M9X-HD4R"), QrLink.parse(text!!))
    }

    @Test
    fun `handles row stride larger than the width`() {
        val url = "http://192.168.1.5:8080/register?code=ABCD-EFGH-JKLM"
        val (data, stride, size) = frame(url, rowPadding = 64)
        assertEquals(url, QrDecoder().decode(data, stride, size, size))
    }

    @Test
    fun `handles a buffer whose last row is shorter than the stride`() {
        val url = "https://fc.example.org/"
        val (data, stride, size) = frame(url, rowPadding = 32)
        val short = data.copyOf(stride * (size - 1) + size)
        assertEquals(url, QrDecoder().decode(short, stride, size, size))
    }

    @Test
    fun `decodes light-on-dark codes`() {
        val url = "https://fc.example.org/"
        val (data, stride, size) = frame(url, invert = true)
        assertEquals(url, QrDecoder().decode(data, stride, size, size))
    }

    @Test
    fun `returns null for frames without a code and for invalid geometry`() {
        val blank = ByteArray(200 * 200) { 120 }
        assertNull(QrDecoder().decode(blank, 200, 200, 200))
        assertNull(QrDecoder().decode(ByteArray(10), 200, 200, 200))
        assertNull(QrDecoder().decode(blank, 100, 200, 200))
    }
}

package be.appmire.gpsinfo.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Tiny wrapper around ZXing's [QRCodeWriter] that produces a 1-bit
 * ARGB_8888 bitmap ready for [androidx.compose.foundation.Image] /
 * `BitmapPainter`.
 *
 * Error-correction level Q (≈ 25 %) is the sweet spot for screen-to-
 * screen scanning at typical café-table distances — high enough to
 * shrug off a smudged phone or modest glare, low enough that a
 * `geo:` URI still fits in a small QR (Version 2-3, ~30 mm at 300 px).
 *
 * Margin of 1 module — ZXing's default is 4 which wastes half the
 * Compose canvas on white border. Scanners cope with 1 thanks to the
 * surrounding screen pixels.
 */
internal object QrEncoder {

    /**
     * Encode [text] as a QR matrix and rasterise it to a [size]x[size]
     * bitmap. Each module is rendered as a square block; the bitmap is
     * fully opaque with black-on-white pixels. Returns null when ZXing
     * can't encode (e.g., the payload exceeds Version-40 capacity).
     */
    fun encode(text: String, size: Int = DEFAULT_SIZE_PX): Bitmap? {
        if (text.isEmpty() || size <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }

    /** Compose-friendly default — looks crisp on a 1080p phone and
     *  encodes a typical `geo:lat,lon` URI in Version 2 / ECC Q. */
    const val DEFAULT_SIZE_PX: Int = 512
}

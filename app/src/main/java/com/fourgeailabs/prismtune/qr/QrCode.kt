package com.fourgeailabs.prismtune.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a QR code bitmap for a URL. Used on the sign-in screen so the phone
 * can be pointed at the car screen instead of typing plex.tv/link by hand.
 * Amber modules on a near-black field, matching the PrismTune theme.
 */
object QrCode {
    fun render(text: String, sizePx: Int = 640): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bitmap.setPixel(
                        x, y,
                        if (matrix.get(x, y)) Color.rgb(255, 176, 32) else Color.rgb(16, 14, 10),
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

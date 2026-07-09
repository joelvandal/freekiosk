package com.freekiosk.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * Generates raw ESC/POS byte sequences for thermal receipt printers.
 *
 * Targets the generic ESC/POS dialect supported by Epson TM-T20/T70/T88,
 * Bixolon, and most 58/80mm thermal printers. Star printers in
 * ESC/POS-emulation mode also work.
 *
 * No external dependencies — the protocol is small and stable.
 */
object EscPosEncoder {

    // Single byte command constants — kept as Int to avoid Kotlin's signed-byte ergonomics.
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A
    private const val DLE = 0x10

    val INIT: ByteArray = byteArrayOf(ESC.toByte(), 0x40)
    // DLE ENQ 1 — real-time recovery from cutter error. Safe no-op when the
    // cutter is healthy. Epson TM-T20IV silently ignores cut commands after a
    // recoverable cutter error (until the next power cycle), so we prepend
    // this to every job to keep the cutter responsive across consecutive prints.
    val CUTTER_RECOVERY: ByteArray = byteArrayOf(DLE.toByte(), 0x05, 0x01)
    val LINE_FEED: ByteArray = byteArrayOf(LF.toByte())
    // GS V m — immediate cut. m=0 full, m=1 partial. The cutter blade sits
    // ~12-15 mm above the print head, so callers must feed enough line feeds
    // before the cut (see CUT_FEED_LINES). The feed-and-cut variants (m=65/66)
    // exist but cause parser issues on some TM-T20IV firmware, so we stick to
    // the simple form and pre-feed paper with LFs instead.
    val CUT_FULL: ByteArray = byteArrayOf(GS.toByte(), 0x56, 0x00)
    val CUT_PARTIAL: ByteArray = byteArrayOf(GS.toByte(), 0x56, 0x01)
    /** Recommended number of line feeds to send before a cut to clear the print head. */
    const val CUT_FEED_LINES = 5
    val DRAWER_PIN2: ByteArray = byteArrayOf(ESC.toByte(), 0x70, 0x00, 0x19, 0xFA.toByte())
    val DRAWER_PIN5: ByteArray = byteArrayOf(ESC.toByte(), 0x70, 0x01, 0x19, 0xFA.toByte())

    enum class Alignment(val n: Int) { LEFT(0), CENTER(1), RIGHT(2) }
    enum class Font(val n: Int) { A(0), B(1) }
    enum class CutMode { FULL, PARTIAL }

    /** ESC a n — text alignment (applies until next ESC a). */
    fun align(a: Alignment): ByteArray = byteArrayOf(ESC.toByte(), 0x61, a.n.toByte())

    /** ESC E n — bold on/off. */
    fun bold(on: Boolean): ByteArray = byteArrayOf(ESC.toByte(), 0x45, (if (on) 1 else 0).toByte())

    /** ESC M n — font A (0) / font B (1). */
    fun font(f: Font): ByteArray = byteArrayOf(ESC.toByte(), 0x4D, f.n.toByte())

    /**
     * GS ! n — character size. Bit 0..3 = width multiplier, bit 4..7 = height multiplier.
     * 1x1 = 0x00, 2x2 = 0x11, etc.
     */
    fun size(doubleWidth: Boolean, doubleHeight: Boolean): ByteArray {
        val width = if (doubleWidth) 1 else 0
        val height = if (doubleHeight) 1 else 0
        val n = (width shl 4) or height
        return byteArrayOf(GS.toByte(), 0x21, n.toByte())
    }

    /**
     * ESC t n — select character code table. Common values:
     *   0  PC437 (USA)
     *   2  PC850 (multilingual Latin 1)
     *   16 cp1252 (WPC1252)
     *   19 PC858 (Latin 1 + Euro)
     */
    fun codePage(n: Int): ByteArray = byteArrayOf(ESC.toByte(), 0x74, n.toByte())

    fun cut(mode: CutMode): ByteArray = if (mode == CutMode.FULL) CUT_FULL else CUT_PARTIAL

    // ─── 1D Barcodes (GS k) ───────────────────────────────────────────────────

    /** Supported 1D barcode symbologies (using ESC/POS function B: `GS k m n d1...dn`). */
    enum class BarcodeSymbology(val m: Int) {
        UPC_A(65), UPC_E(66),
        EAN13(67), JAN13(67),
        EAN8(68), JAN8(68),
        CODE39(69),
        ITF(70),
        CODABAR(71),
        CODE93(72),
        CODE128(73),
    }

    /** HRI text position relative to the barcode. */
    enum class HriPosition(val n: Int) { NONE(0), ABOVE(1), BELOW(2), BOTH(3) }

    /**
     * Render a 1D barcode.
     *
     * @param data Numeric/alphanumeric data — caller is responsible for validity
     *   (e.g. EAN13 needs 12 or 13 digits). For CODE128, prefix data with the
     *   code-set selector (`{A`, `{B`, `{C`) if needed.
     * @param heightDots Bar height in dots (1..255). Default 80 (~10 mm).
     * @param moduleWidth Bar module width 2..6. Default 2 (≈0.25 mm).
     * @param hri Human-readable interpretation position.
     */
    fun barcode(
        symbology: BarcodeSymbology,
        data: String,
        heightDots: Int = 80,
        moduleWidth: Int = 2,
        hri: HriPosition = HriPosition.BELOW,
    ): ByteArray {
        val height = heightDots.coerceIn(1, 255)
        val width = moduleWidth.coerceIn(2, 6)
        val payload = data.toByteArray(Charsets.US_ASCII)

        val out = ByteArrayOutputStream()
        // GS H n — HRI position
        out.write(GS); out.write(0x48); out.write(hri.n)
        // GS h n — barcode height
        out.write(GS); out.write(0x68); out.write(height)
        // GS w n — module width
        out.write(GS); out.write(0x77); out.write(width)
        // GS k m n d1...dn  (function B — length-prefixed; works for all symbologies)
        out.write(GS); out.write(0x6B); out.write(symbology.m)
        out.write(payload.size.coerceAtMost(255))
        out.write(payload)
        return out.toByteArray()
    }

    // ─── QR Code (GS ( k) ─────────────────────────────────────────────────────

    /** QR error correction level. Higher = more redundancy, less data capacity. */
    enum class QrErrorCorrection(val n: Int) {
        L(48), // ~7%
        M(49), // ~15%
        Q(50), // ~25%
        H(51), // ~30%
    }

    /**
     * Render a QR code via the standard ESC/POS GS ( k sequence.
     *
     * @param data UTF-8 payload (URL, text, vCard, etc.). Max ≈2953 bytes for
     *   level L at model 2.
     * @param moduleSize Dot size of each QR module, 1..16. Default 6 (~80×80
     *   px on an 80 mm printer — fits comfortably).
     * @param ec Error correction level (default M).
     */
    fun qrCode(
        data: String,
        moduleSize: Int = 6,
        ec: QrErrorCorrection = QrErrorCorrection.M,
    ): ByteArray {
        val size = moduleSize.coerceIn(1, 16)
        val payload = data.toByteArray(Charsets.UTF_8)
        // Length used by Store-Data command: payload.size + 3 (for cn fn m)
        val storeLen = payload.size + 3
        val pL = storeLen and 0xFF
        val pH = (storeLen shr 8) and 0xFF

        val out = ByteArrayOutputStream()
        // Set model 2:  GS ( k  4 0 49 65 50 0
        out.write(GS); out.write(0x28); out.write(0x6B)
        out.write(4); out.write(0); out.write(49); out.write(65); out.write(50); out.write(0)
        // Set module size:  GS ( k  3 0 49 67 n
        out.write(GS); out.write(0x28); out.write(0x6B)
        out.write(3); out.write(0); out.write(49); out.write(67); out.write(size)
        // Set error correction:  GS ( k  3 0 49 69 n
        out.write(GS); out.write(0x28); out.write(0x6B)
        out.write(3); out.write(0); out.write(49); out.write(69); out.write(ec.n)
        // Store data:  GS ( k  pL pH 49 80 48 d1...dk
        out.write(GS); out.write(0x28); out.write(0x6B)
        out.write(pL); out.write(pH); out.write(49); out.write(80); out.write(48)
        out.write(payload)
        // Print:  GS ( k  3 0 49 81 48
        out.write(GS); out.write(0x28); out.write(0x6B)
        out.write(3); out.write(0); out.write(49); out.write(81); out.write(48)
        return out.toByteArray()
    }

    fun openDrawer(pin: Int): ByteArray = if (pin == 5) DRAWER_PIN5 else DRAWER_PIN2

    /**
     * Encode text using the given charset. Falls back to ASCII for unsupported chars.
     */
    fun text(s: String, charset: String = "cp437"): ByteArray {
        val javaCharset = when (charset.lowercase()) {
            "cp437" -> "IBM437"
            "cp850" -> "IBM850"
            "cp858" -> "IBM00858"
            "cp1252", "windows-1252" -> "windows-1252"
            else -> charset
        }
        return try {
            s.toByteArray(charset(javaCharset))
        } catch (e: Exception) {
            s.toByteArray(Charsets.US_ASCII)
        }
    }

    /**
     * Encode a bitmap as ESC/POS raster (GS v 0).
     *
     * Resizes the bitmap to fit `maxWidthDots` while preserving aspect ratio,
     * flattens transparency over white, applies Floyd-Steinberg dithering,
     * and packs to 1bpp (MSB-first).
     *
     * Large images are split into horizontal bands of `bandHeight` rows
     * because some printers reject very large GS v 0 buffers.
     */
    fun rasterFromBitmap(
        source: Bitmap,
        maxWidthDots: Int,
        bandHeight: Int = 256,
    ): ByteArray {
        val flattened = flattenToWhite(source)
        val scaled = scaleToWidth(flattened, maxWidthDots)
        val widthDots = scaled.width
        val heightDots = scaled.height

        // Width in printer bytes (8 dots per byte), rounded up.
        val widthBytes = (widthDots + 7) / 8
        val mono = ditherFloydSteinberg(scaled)

        val out = ByteArrayOutputStream()
        var y = 0
        while (y < heightDots) {
            val rows = minOf(bandHeight, heightDots - y)
            // GS v 0 m xL xH yL yH
            out.write(GS); out.write(0x76); out.write(0x30); out.write(0x00)
            out.write(widthBytes and 0xFF)
            out.write((widthBytes shr 8) and 0xFF)
            out.write(rows and 0xFF)
            out.write((rows shr 8) and 0xFF)
            for (row in 0 until rows) {
                val absY = y + row
                for (xByte in 0 until widthBytes) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = xByte * 8 + bit
                        if (x < widthDots && mono[absY * widthDots + x]) {
                            b = b or (0x80 shr bit)
                        }
                    }
                    out.write(b)
                }
            }
            y += rows
        }
        return out.toByteArray()
    }

    /** Composite the source over an opaque white background to remove alpha channel. */
    private fun flattenToWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width == targetWidth) return src
        val ratio = targetWidth.toFloat() / src.width.toFloat()
        val targetHeight = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    /**
     * Floyd-Steinberg dithering to 1-bit monochrome. Returns a flat boolean array
     * (true = ink / black) of width*height.
     */
    private fun ditherFloydSteinberg(bmp: Bitmap): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val gray = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // Rec. 601 luma
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val oldP = gray[idx]
                val newP = if (oldP < 128f) 0f else 255f
                out[idx] = newP < 128f // black pixel
                val err = oldP - newP
                if (x + 1 < w) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) gray[idx + w - 1] += err * 3f / 16f
                    gray[idx + w] += err * 5f / 16f
                    if (x + 1 < w) gray[idx + w + 1] += err * 1f / 16f
                }
            }
        }
        return out
    }

    /** Helper to concatenate multiple byte arrays. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    /**
     * Quick test sheet: title, alignment demos, font sizes, line, cut.
     * Useful as the body of a "Test print" button in settings.
     */
    fun testSheet(charset: String = "cp437"): ByteArray {
        val divider = "--------------------------------\n"
        return concat(
            INIT,
            codePage(codePageNumberFor(charset)),

            // Header
            align(Alignment.CENTER), bold(true), size(true, true),
            text("FreeKiosk\n", charset),
            size(false, false), bold(false),
            text("Direct Print Test\n\n", charset),

            // Alignment demo
            align(Alignment.LEFT),
            text(divider, charset),
            text("Left aligned\n", charset),
            align(Alignment.CENTER), text("Center aligned\n", charset),
            align(Alignment.RIGHT), text("Right aligned\n", charset),
            align(Alignment.LEFT),

            // Text style demo
            text(divider, charset),
            bold(true), text("Bold text\n", charset), bold(false),
            size(true, false), text("Double width\n", charset),
            size(false, true), text("Double height\n", charset),
            size(true, true), text("DW + DH\n", charset),
            size(false, false),
            text("Normal size\n", charset),

            // Barcode demo (CODE128)
            text(divider, charset),
            align(Alignment.CENTER),
            text("Barcode (CODE128):\n", charset),
            barcode(BarcodeSymbology.CODE128, "{BFreeKiosk", heightDots = 80, moduleWidth = 2, hri = HriPosition.BELOW),
            align(Alignment.LEFT),

            // QR code demo
            text(divider, charset),
            align(Alignment.CENTER),
            text("QR Code:\n", charset),
            qrCode("https://freekiosk.app", moduleSize = 6, ec = QrErrorCorrection.M),
            text("\nhttps://freekiosk.app\n", charset),
            align(Alignment.LEFT),

            // Footer
            text(divider, charset),
            align(Alignment.CENTER),
            text("If you can read this,\n", charset),
            text("the printer is working.\n", charset),
            align(Alignment.LEFT),

            // Feed paper past the cutter blade and cut
            text("\n".repeat(CUT_FEED_LINES), charset),
            CUT_FULL,
        )
    }

    private fun codePageNumberFor(charset: String): Int = when (charset.lowercase()) {
        "cp437" -> 0
        "cp850" -> 2
        "cp1252", "windows-1252" -> 16
        "cp858" -> 19
        else -> 0
    }
}

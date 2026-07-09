package com.freekiosk.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.usb.UsbManager
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.freekiosk.DebugLog
import java.io.IOException

/**
 * Direct printing to ESC/POS thermal printers over TCP (port 9100 RAW) or USB Host.
 *
 * Bypasses Android PrintManager / Print Services. Used when window.print() is
 * intercepted in the WebView and DIRECT_PRINT_ENABLED is set in settings.
 *
 * Connection parameters (host/port or vendor/product IDs) are passed in per call
 * from the TS layer, which reads them from AsyncStorage. The module is stateless.
 */
class DirectPrintModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "DirectPrintModule"
        private const val DEFAULT_PAPER_WIDTH_DOTS = 576 // 80mm @ 203dpi
    }

    override fun getName(): String = NAME

    // ─── Connection config ────────────────────────────────────────────────────

    private data class Conn(
        val type: String,
        val host: String,
        val port: Int,
        val timeoutMs: Int,
        val vendorId: Int,
        val productId: Int,
    )

    private fun readConn(config: ReadableMap): Conn {
        val type = if (config.hasKey("connection")) config.getString("connection") ?: "tcp" else "tcp"
        val host = if (config.hasKey("tcpHost")) config.getString("tcpHost") ?: "" else ""
        val port = if (config.hasKey("tcpPort")) config.getInt("tcpPort") else 9100
        val timeoutMs = if (config.hasKey("tcpTimeoutMs")) config.getInt("tcpTimeoutMs") else 5000
        val vid = if (config.hasKey("usbVendorId")) config.getInt("usbVendorId") else 0
        val pid = if (config.hasKey("usbProductId")) config.getInt("usbProductId") else 0
        return Conn(type, host, port, timeoutMs, vid, pid)
    }

    private fun makeTransport(conn: Conn): PrintTransport = when (conn.type) {
        "tcp" -> PrintTransport.Tcp(conn.host, conn.port, conn.timeoutMs)
        "usb" -> PrintTransport.Usb(reactApplicationContext, conn.vendorId, conn.productId)
        else -> throw IOException("Unknown connection type: ${conn.type}")
    }

    /**
     * Validate connection params and reject the promise immediately if they're
     * obviously wrong. Catches configuration bugs (port 0, empty host, missing
     * USB device) before we ever open a socket and surfaces a clear message
     * instead of a low-level ENETUNREACH.
     */
    private fun validateConn(conn: Conn, promise: Promise): Boolean {
        if (conn.type == "tcp") {
            if (conn.host.isBlank()) {
                promise.reject("INVALID_HOST", "TCP host is empty — open Settings → Printer and fill in the host or IP.")
                return false
            }
            if (conn.port <= 0 || conn.port >= 65536) {
                promise.reject("INVALID_PORT", "Invalid TCP port ${conn.port} — expected 1..65535 (default 9100).")
                return false
            }
        } else if (conn.type == "usb") {
            if (conn.vendorId == 0 || conn.productId == 0) {
                promise.reject("INVALID_USB_IDS", "No USB device selected — open Settings → Printer → Scan USB devices.")
                return false
            }
        }
        return true
    }

    /** Send bytes on a background thread, resolve/reject the promise on completion. */
    private fun sendAsync(conn: Conn, bytes: ByteArray, promise: Promise) {
        if (!validateConn(conn, promise)) return
        Thread {
            try {
                makeTransport(conn).use { it.send(bytes) }
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "Print send failed: ${e.message}")
                promise.reject("PRINT_FAILED", e.message ?: "unknown error", e)
            }
        }.apply {
            name = "DirectPrint-${conn.type}"
            isDaemon = true
            start()
        }
    }

    private fun paperWidth(config: ReadableMap): Int =
        if (config.hasKey("paperWidthDots")) config.getInt("paperWidthDots") else DEFAULT_PAPER_WIDTH_DOTS

    private fun charset(config: ReadableMap): String =
        if (config.hasKey("characterSet")) config.getString("characterSet") ?: "cp437" else "cp437"

    // ─── Bitmap capture ───────────────────────────────────────────────────────

    @ReactMethod
    fun printWebViewBitmap(config: ReadableMap, promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No activity available")
            return
        }
        val conn = readConn(config)
        val widthDots = paperWidth(config)

        activity.runOnUiThread {
            try {
                val webView = findWebViewRecursive(activity.window.decorView)
                if (webView == null) {
                    promise.reject("NO_WEBVIEW", "WebView not found in view hierarchy")
                    return@runOnUiThread
                }
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) {
                    promise.reject("WEBVIEW_EMPTY", "WebView has no measured size yet")
                    return@runOnUiThread
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(bitmap))

                val raster = EscPosEncoder.rasterFromBitmap(bitmap, widthDots)
                val bytes = EscPosEncoder.concat(
                    EscPosEncoder.INIT,
                    raster,
                    EscPosEncoder.LINE_FEED,
                    EscPosEncoder.LINE_FEED,
                    EscPosEncoder.LINE_FEED,
                )
                bitmap.recycle()
                sendAsync(conn, bytes, promise)
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "printWebViewBitmap failed: ${e.message}")
                promise.reject("CAPTURE_FAILED", e.message, e)
            }
        }
    }

    // ─── Text printing ────────────────────────────────────────────────────────

    @ReactMethod
    fun printText(text: String, options: ReadableMap, config: ReadableMap, promise: Promise) {
        val conn = readConn(config)
        val cs = charset(config)
        val align = if (options.hasKey("align")) parseAlign(options.getString("align")) else EscPosEncoder.Alignment.LEFT
        val bold = options.hasKey("bold") && options.getBoolean("bold")
        val doubleHeight = options.hasKey("doubleHeight") && options.getBoolean("doubleHeight")
        val doubleWidth = options.hasKey("doubleWidth") && options.getBoolean("doubleWidth")
        val font = if (options.hasKey("font") && options.getString("font") == "B") EscPosEncoder.Font.B else EscPosEncoder.Font.A
        val ensureNewline = if (text.endsWith("\n")) text else "$text\n"

        val bytes = EscPosEncoder.concat(
            EscPosEncoder.INIT,
            EscPosEncoder.codePage(codePageFor(cs)),
            EscPosEncoder.font(font),
            EscPosEncoder.align(align),
            EscPosEncoder.bold(bold),
            EscPosEncoder.size(doubleWidth, doubleHeight),
            EscPosEncoder.text(ensureNewline, cs),
            // Reset to defaults so subsequent prints aren't affected
            EscPosEncoder.size(false, false),
            EscPosEncoder.bold(false),
            EscPosEncoder.align(EscPosEncoder.Alignment.LEFT),
        )
        sendAsync(conn, bytes, promise)
    }

    // ─── Raster (base64) ──────────────────────────────────────────────────────

    @ReactMethod
    fun printRaster(base64Bitmap: String, width: Int, config: ReadableMap, promise: Promise) {
        try {
            val decoded = Base64.decode(stripDataUri(base64Bitmap), Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                ?: throw IOException("Failed to decode base64 image")
            val targetWidth = if (width > 0) width else paperWidth(config)
            val raster = EscPosEncoder.rasterFromBitmap(bitmap, targetWidth)
            bitmap.recycle()
            val bytes = EscPosEncoder.concat(
                EscPosEncoder.INIT,
                raster,
                EscPosEncoder.LINE_FEED,
            )
            sendAsync(readConn(config), bytes, promise)
        } catch (e: Exception) {
            promise.reject("RASTER_FAILED", e.message, e)
        }
    }

    private fun stripDataUri(s: String): String {
        val comma = s.indexOf(',')
        return if (s.startsWith("data:") && comma > 0) s.substring(comma + 1) else s
    }

    // ─── Compound print (sequence of blocks) ──────────────────────────────────

    /**
     * Print a multi-block document in a single TCP/USB session. `blocks` is a
     * ReadableArray of ReadableMaps shaped like:
     *   { type: "text", text: "Hello", bold: true, align: "center" }
     *   { type: "newline", count: 2 }
     *   { type: "raster", base64: "...", width: 576 }
     *   { type: "cut", mode: "full" }
     *   { type: "drawer", pin: 2 }
     */
    @ReactMethod
    fun printBlocks(blocks: ReadableArray, config: ReadableMap, promise: Promise) {
        try {
            val cs = charset(config)
            val defaultWidth = paperWidth(config)
            val parts = mutableListOf<ByteArray>()
            parts.add(EscPosEncoder.INIT)
            parts.add(EscPosEncoder.codePage(codePageFor(cs)))

            for (i in 0 until blocks.size()) {
                if (blocks.getType(i) != ReadableType.Map) continue
                val block = blocks.getMap(i) ?: continue
                when (block.getString("type")) {
                    "text" -> {
                        val t = block.getString("text") ?: ""
                        val align = if (block.hasKey("align")) parseAlign(block.getString("align")) else EscPosEncoder.Alignment.LEFT
                        val bold = block.hasKey("bold") && block.getBoolean("bold")
                        val dh = block.hasKey("doubleHeight") && block.getBoolean("doubleHeight")
                        val dw = block.hasKey("doubleWidth") && block.getBoolean("doubleWidth")
                        val font = if (block.hasKey("font") && block.getString("font") == "B") EscPosEncoder.Font.B else EscPosEncoder.Font.A
                        parts.add(EscPosEncoder.font(font))
                        parts.add(EscPosEncoder.align(align))
                        parts.add(EscPosEncoder.bold(bold))
                        parts.add(EscPosEncoder.size(dw, dh))
                        parts.add(EscPosEncoder.text(if (t.endsWith("\n")) t else "$t\n", cs))
                        parts.add(EscPosEncoder.size(false, false))
                        parts.add(EscPosEncoder.bold(false))
                    }
                    "newline" -> {
                        val count = if (block.hasKey("count")) block.getInt("count") else 1
                        repeat(count.coerceAtLeast(1)) { parts.add(EscPosEncoder.LINE_FEED) }
                    }
                    "raster" -> {
                        val b64 = block.getString("base64") ?: continue
                        val width = if (block.hasKey("width")) block.getInt("width") else defaultWidth
                        val decoded = Base64.decode(stripDataUri(b64), Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.size) ?: continue
                        parts.add(EscPosEncoder.rasterFromBitmap(bmp, width))
                        bmp.recycle()
                    }
                    "cut" -> {
                        val mode = if (block.hasKey("mode") && block.getString("mode") == "partial")
                            EscPosEncoder.CutMode.PARTIAL else EscPosEncoder.CutMode.FULL
                        parts.add(EscPosEncoder.cut(mode))
                    }
                    "drawer" -> {
                        val pin = if (block.hasKey("pin")) block.getInt("pin") else 2
                        parts.add(EscPosEncoder.openDrawer(pin))
                    }
                    "barcode" -> {
                        val data = block.getString("data") ?: continue
                        val symbology = parseSymbology(block.getString("symbology"))
                        val height = if (block.hasKey("height")) block.getInt("height") else 80
                        val width = if (block.hasKey("width")) block.getInt("width") else 2
                        val hri = parseHri(block.getString("hri"))
                        val align = if (block.hasKey("align")) parseAlign(block.getString("align")) else EscPosEncoder.Alignment.LEFT
                        parts.add(EscPosEncoder.align(align))
                        parts.add(EscPosEncoder.barcode(symbology, data, height, width, hri))
                        parts.add(EscPosEncoder.LINE_FEED)
                        parts.add(EscPosEncoder.align(EscPosEncoder.Alignment.LEFT))
                    }
                    "qrcode" -> {
                        val data = block.getString("data") ?: continue
                        val moduleSize = if (block.hasKey("moduleSize")) block.getInt("moduleSize") else 6
                        val ec = parseQrEc(block.getString("ec"))
                        val align = if (block.hasKey("align")) parseAlign(block.getString("align")) else EscPosEncoder.Alignment.LEFT
                        parts.add(EscPosEncoder.align(align))
                        parts.add(EscPosEncoder.qrCode(data, moduleSize, ec))
                        parts.add(EscPosEncoder.LINE_FEED)
                        parts.add(EscPosEncoder.align(EscPosEncoder.Alignment.LEFT))
                    }
                }
            }
            sendAsync(readConn(config), EscPosEncoder.concat(*parts.toTypedArray()), promise)
        } catch (e: Exception) {
            promise.reject("BLOCKS_FAILED", e.message, e)
        }
    }

    // ─── Single-action helpers ────────────────────────────────────────────────

    @ReactMethod
    fun cutPaper(mode: String, config: ReadableMap, promise: Promise) {
        val cm = if (mode == "partial") EscPosEncoder.CutMode.PARTIAL else EscPosEncoder.CutMode.FULL
        sendAsync(readConn(config), EscPosEncoder.cut(cm), promise)
    }

    @ReactMethod
    fun openCashDrawer(pin: Int, config: ReadableMap, promise: Promise) {
        sendAsync(readConn(config), EscPosEncoder.openDrawer(pin), promise)
    }

    @ReactMethod
    fun testPrint(config: ReadableMap, promise: Promise) {
        val cs = charset(config)
        sendAsync(readConn(config), EscPosEncoder.testSheet(cs), promise)
    }

    // ─── Discovery & permissions ──────────────────────────────────────────────

    @ReactMethod
    fun listUsbDevices(promise: Promise) {
        try {
            val manager = reactApplicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (manager == null) {
                promise.resolve(Arguments.createArray())
                return
            }
            val result: WritableArray = Arguments.createArray()
            for ((_, device) in manager.deviceList) {
                val info = Arguments.createMap().apply {
                    putString("deviceName", device.deviceName)
                    putInt("vendorId", device.vendorId)
                    putInt("productId", device.productId)
                    putString("manufacturerName", device.manufacturerName)
                    putString("productName", device.productName)
                    putBoolean("hasPermission", manager.hasPermission(device))
                }
                result.pushMap(info)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("USB_LIST_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun requestUsbPermission(vendorId: Int, productId: Int, promise: Promise) {
        Thread {
            try {
                val manager = reactApplicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
                if (manager == null) {
                    promise.resolve(false)
                    return@Thread
                }
                val device = manager.deviceList.values.firstOrNull {
                    it.vendorId == vendorId && it.productId == productId
                }
                if (device == null) {
                    promise.reject("USB_NOT_FOUND", "No USB device with VID=$vendorId PID=$productId")
                    return@Thread
                }
                val granted = UsbPermissionHelper.request(reactApplicationContext, device)
                promise.resolve(granted)
            } catch (e: Exception) {
                promise.reject("USB_PERM_FAILED", e.message, e)
            }
        }.apply { isDaemon = true; start() }
    }

    @ReactMethod
    fun pingTcpPrinter(host: String, port: Int, timeoutMs: Int, promise: Promise) {
        Thread {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                socket.close()
                promise.resolve(true)
            } catch (e: Exception) {
                promise.resolve(false)
            }
        }.apply { isDaemon = true; start() }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun parseAlign(s: String?): EscPosEncoder.Alignment = when (s) {
        "center" -> EscPosEncoder.Alignment.CENTER
        "right" -> EscPosEncoder.Alignment.RIGHT
        else -> EscPosEncoder.Alignment.LEFT
    }

    private fun parseSymbology(s: String?): EscPosEncoder.BarcodeSymbology = when (s?.uppercase()) {
        "UPC_A", "UPCA", "UPC-A" -> EscPosEncoder.BarcodeSymbology.UPC_A
        "UPC_E", "UPCE", "UPC-E" -> EscPosEncoder.BarcodeSymbology.UPC_E
        "EAN13", "EAN_13", "EAN-13", "JAN13" -> EscPosEncoder.BarcodeSymbology.EAN13
        "EAN8", "EAN_8", "EAN-8", "JAN8" -> EscPosEncoder.BarcodeSymbology.EAN8
        "CODE39", "CODE_39", "CODE-39" -> EscPosEncoder.BarcodeSymbology.CODE39
        "ITF" -> EscPosEncoder.BarcodeSymbology.ITF
        "CODABAR", "NW7" -> EscPosEncoder.BarcodeSymbology.CODABAR
        "CODE93", "CODE_93", "CODE-93" -> EscPosEncoder.BarcodeSymbology.CODE93
        else -> EscPosEncoder.BarcodeSymbology.CODE128
    }

    private fun parseHri(s: String?): EscPosEncoder.HriPosition = when (s?.lowercase()) {
        "none", "off" -> EscPosEncoder.HriPosition.NONE
        "above" -> EscPosEncoder.HriPosition.ABOVE
        "both" -> EscPosEncoder.HriPosition.BOTH
        else -> EscPosEncoder.HriPosition.BELOW
    }

    private fun parseQrEc(s: String?): EscPosEncoder.QrErrorCorrection = when (s?.uppercase()) {
        "L" -> EscPosEncoder.QrErrorCorrection.L
        "Q" -> EscPosEncoder.QrErrorCorrection.Q
        "H" -> EscPosEncoder.QrErrorCorrection.H
        else -> EscPosEncoder.QrErrorCorrection.M
    }

    private fun codePageFor(charset: String): Int = when (charset.lowercase()) {
        "cp437" -> 0
        "cp850" -> 2
        "cp1252", "windows-1252" -> 16
        "cp858" -> 19
        else -> 0
    }

    private fun findWebViewRecursive(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebViewRecursive(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}

package com.freekiosk.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.freekiosk.DebugLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transport-layer abstractions for sending raw ESC/POS bytes to a printer.
 *
 * Implementations are blocking — callers must invoke them on a background
 * thread (DirectPrintModule wraps them in a dedicated Thread).
 */
sealed class PrintTransport : AutoCloseable {

    abstract fun send(bytes: ByteArray)

    companion object {
        private const val TAG = "PrintTransport"
    }

    /** Raw TCP socket (JetDirect / port 9100). */
    class Tcp(
        private val host: String,
        private val port: Int,
        private val connectTimeoutMs: Int,
    ) : PrintTransport() {

        private var socket: Socket? = null

        @Throws(IOException::class)
        override fun send(bytes: ByteArray) {
            val s = Socket().apply { soTimeout = connectTimeoutMs }
            socket = s
            try {
                DebugLog.d(TAG, "Connecting to $host:$port (timeout=${connectTimeoutMs}ms)")
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                DebugLog.d(TAG, "Connected to $host:$port — sending ${bytes.size} bytes")
                val out = s.getOutputStream()
                out.write(bytes)
                out.flush()
                DebugLog.d(TAG, "Flushed ${bytes.size} bytes to $host:$port")
            } catch (e: IOException) {
                // Wrap with host:port so the user sees exactly which target failed.
                throw IOException("TCP $host:$port — ${e.message}", e)
            } finally {
                runCatching { s.close() }
                socket = null
            }
        }

        override fun close() {
            socket?.let { runCatching { it.close() } }
            socket = null
        }
    }

    /** USB Host bulk-out transfer. */
    class Usb(
        private val context: Context,
        private val vendorId: Int,
        private val productId: Int,
        private val transferTimeoutMs: Int = 5000,
    ) : PrintTransport() {

        private var connection: UsbDeviceConnection? = null
        private var iface: UsbInterface? = null

        @Throws(IOException::class)
        override fun send(bytes: ByteArray) {
            DebugLog.d(TAG, "USB send: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)} bytes=${bytes.size}")
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: throw IOException("UsbManager unavailable")

            val device = findDevice(manager)
                ?: throw IOException("USB printer not found (VID=0x${vendorId.toString(16)}, PID=0x${productId.toString(16)})")
            DebugLog.d(TAG, "USB device found: ${device.deviceName} ${device.manufacturerName}/${device.productName} interfaceCount=${device.interfaceCount}")

            // Dump full USB topology so we can see if there are multiple interfaces / endpoints.
            for (i in 0 until device.interfaceCount) {
                val ifc = device.getInterface(i)
                DebugLog.d(TAG, "  iface[$i]: id=${ifc.id} alt=${ifc.alternateSetting} class=${ifc.interfaceClass} sub=${ifc.interfaceSubclass} proto=${ifc.interfaceProtocol} endpoints=${ifc.endpointCount}")
                for (j in 0 until ifc.endpointCount) {
                    val ep = ifc.getEndpoint(j)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "?"
                    }
                    DebugLog.d(TAG, "    ep[$j]: addr=0x${ep.address.toString(16)} dir=$dir type=$type maxPacket=${ep.maxPacketSize}")
                }
            }

            if (!manager.hasPermission(device)) {
                // USB permission is per-attachment on Android — it is lost on
                // replug / reboot / re-enumeration (USB power save, hub cycling).
                // Self-heal: (re)acquire it here instead of hard-failing. If the
                // USB_DEVICE_ATTACHED "always open" grant is set, hasPermission is
                // already true and we never reach this; otherwise request() prompts
                // and blocks (safe — send() runs on a background thread).
                DebugLog.w(TAG, "USB permission missing — requesting before send")
                if (!UsbPermissionHelper.request(context, device)) {
                    throw IOException(
                        "USB permission denied (VID=0x${vendorId.toString(16)}, PID=0x${productId.toString(16)}). " +
                            "Grant access in the USB dialog, and tick \"always open FreeKiosk for this device\" so it persists across replug/reboot."
                    )
                }
            }

            val printerInterface = findPrinterInterface(device)
                ?: throw IOException("No printer-class interface (class=7) found on device")
            DebugLog.d(TAG, "USB interface: class=${printerInterface.interfaceClass} subclass=${printerInterface.interfaceSubclass} endpoints=${printerInterface.endpointCount}")

            val outEndpoint = findBulkOutEndpoint(printerInterface)
                ?: throw IOException("No bulk OUT endpoint on printer interface")
            DebugLog.d(TAG, "USB bulk-out endpoint: addr=0x${outEndpoint.address.toString(16)} maxPacketSize=${outEndpoint.maxPacketSize}")

            val inEndpoint = findBulkInEndpoint(printerInterface)
            if (inEndpoint != null) {
                DebugLog.d(TAG, "USB bulk-in endpoint: addr=0x${inEndpoint.address.toString(16)} maxPacketSize=${inEndpoint.maxPacketSize}")
            }

            val conn = manager.openDevice(device)
                ?: throw IOException("Failed to open USB device — permission may have been revoked (replug/reboot/re-enumeration). Re-grant USB access.")
            connection = conn
            iface = printerInterface

            try {
                if (!conn.claimInterface(printerInterface, true)) {
                    throw IOException("Failed to claim USB interface")
                }
                // Force alternate setting 0 — some printers expose multiple
                // alt settings and the kernel-selected default isn't always the
                // data path. setInterface returns void; failure is invisible
                // but harmless.
                conn.setInterface(printerInterface)

                // USB Printer Class SOFT_RESET (bmRequestType=0x21, bRequest=2).
                // Many Epson TM-series printers (including TM-T20IV) require this
                // class-specific control transfer at the start of every session
                // — without it, the printer firmware silently ignores bulk-out
                // data even though the USB stack ACKs it. Failure is non-fatal:
                // some printers don't implement SOFT_RESET but still print fine.
                val ifaceNum = printerInterface.id
                val resetRc = conn.controlTransfer(0x21, 2, 0, ifaceNum, null, 0, 1000)
                DebugLog.d(TAG, "USB SOFT_RESET (interface $ifaceNum): rc=$resetRc")

                // Drain the bulk-in endpoint before sending. Epson TM printers
                // push Auto Status Back (ASB) bytes to the host's bulk-in; if
                // we never read them, the printer's TX buffer fills up and the
                // firmware stops processing RX bulk-out data. Drain with a
                // short timeout — any data is discarded; "no data" returns -1
                // quickly without blocking.
                if (inEndpoint != null) {
                    val drainBuf = ByteArray(inEndpoint.maxPacketSize)
                    var totalDrained = 0
                    var iterations = 0
                    while (iterations < 16) {
                        val n = conn.bulkTransfer(inEndpoint, drainBuf, drainBuf.size, 50)
                        if (n <= 0) break
                        totalDrained += n
                        iterations++
                    }
                    DebugLog.d(TAG, "USB bulk-in drained: $totalDrained bytes in $iterations reads")
                }

                DebugLog.d(TAG, "USB interface claimed, sending ${bytes.size} bytes (timeout=${transferTimeoutMs}ms)")
                val chunk = 4096
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(chunk, bytes.size - offset)
                    val written = conn.bulkTransfer(outEndpoint, bytes, offset, len, transferTimeoutMs)
                    DebugLog.d(TAG, "USB bulkTransfer: requested=$len written=$written offset=$offset")
                    if (written < 0) throw IOException("USB bulkTransfer failed at offset=$offset (returned $written)")
                    if (written < len) {
                        DebugLog.w(TAG, "Partial bulkTransfer: wrote $written / $len")
                    }
                    offset += written
                }
                DebugLog.d(TAG, "USB send complete: ${bytes.size} bytes total")

                // Give the printer firmware a moment to drain the endpoint buffer
                // before we release the interface. Without this, releasing too
                // fast can cause some printers to abort the in-progress job.
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            } finally {
                runCatching { conn.releaseInterface(printerInterface) }
                runCatching { conn.close() }
                connection = null
                iface = null
            }
        }

        override fun close() {
            val c = connection
            val i = iface
            if (c != null && i != null) {
                runCatching { c.releaseInterface(i) }
            }
            connection?.let { runCatching { it.close() } }
            connection = null
            iface = null
        }

        private fun findDevice(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }

        private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
            // Prefer USB class 7 (printer). Fall back to first interface if none declared.
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return iface
            }
            return if (device.interfaceCount > 0) device.getInterface(0) else null
        }

        private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) return ep
            }
            return null
        }

        private fun findBulkInEndpoint(iface: UsbInterface): UsbEndpoint? {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) return ep
            }
            return null
        }
    }
}

/**
 * Helper for requesting USB permission. Runs synchronously on the calling
 * thread by registering a one-shot broadcast receiver and blocking on a latch.
 *
 * The caller MUST already be on a background thread.
 */
object UsbPermissionHelper {
    private const val ACTION_USB_PERMISSION = "com.freekiosk.print.USB_PERMISSION"
    private const val TIMEOUT_MS = 30_000L

    fun request(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (manager.hasPermission(device)) return true

        val latch = CountDownLatch(1)
        val result = arrayOf(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val received: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (received != null && received.deviceName == device.deviceName) {
                        result[0] = granted
                        latch.countDown()
                    }
                }
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        try {
            manager.requestPermission(device, pi)
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return result[0]
    }
}

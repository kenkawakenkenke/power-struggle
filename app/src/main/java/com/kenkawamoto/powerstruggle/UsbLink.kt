package com.kenkawamoto.powerstruggle

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "PowerStruggle"

// Android Open Accessory identity. accessory_filter.xml matches these on the player phone.
const val ACCESSORY_MANUFACTURER = "Power Struggle"
const val ACCESSORY_MODEL = "Referee"
// Offered to the other phone when it does not have the app installed.
private const val ACCESSORY_URI = "https://kenkawamoto.com/projects/power-struggle"

private const val GOOGLE_VID = 0x18D1
private const val APPLE_VID = 0x05AC
private val AOA_PIDS = 0x2D00..0x2D05
private const val AOA_GET_PROTOCOL = 51
private const val AOA_SEND_STRING = 52
private const val AOA_START = 53

/** Newline-delimited text messages over a USB byte pipe. */
class Link(
    private val read: (ByteArray) -> Int,
    private val write: (ByteArray) -> Unit,
    private val closeIo: () -> Unit,
    private val onLine: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    @Volatile private var open = true
    private val writer = Executors.newSingleThreadExecutor()

    init {
        thread(name = "usb-read") { readLoop() }
    }

    private fun readLoop() {
        val buf = ByteArray(16384) // Accessory reads fail with smaller buffers on some devices.
        val pending = StringBuilder()
        try {
            while (open) {
                val n = read(buf)
                if (n < 0) break
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                while (true) {
                    val end = pending.indexOf("\n")
                    if (end < 0) break
                    onLine(pending.substring(0, end))
                    pending.delete(0, end + 1)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "USB read failed", e)
        }
        close()
    }

    fun send(line: String) {
        if (!open) return
        writer.execute {
            try {
                write("$line\n".toByteArray())
            } catch (e: IOException) {
                Log.w(TAG, "USB write failed", e)
                close()
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (!open) return
            open = false
        }
        writer.shutdown()
        runCatching(closeIo)
        onClosed()
    }
}

private fun permissionIntent(context: Context) = PendingIntent.getBroadcast(
    context, 0,
    Intent("com.kenkawamoto.powerstruggle.USB_PERMISSION").setPackage(context.packageName),
    PendingIntent.FLAG_MUTABLE,
)

/** Referee side: USB host that switches the other phone into accessory mode and talks to it. */
class AoaHost(private val context: Context, private val log: (String) -> Unit) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private var permissionRequestedFor: String? = null

    /** Product name of the connected USB device, if any. */
    @Volatile var partnerName: String? = null
        private set

    /**
     * The connected device can't run the accessory protocol (e.g. an iPad or iPhone). It can still
     * swap power, so it is played against in one-phone mode.
     */
    @Volatile var partnerWithoutAoa = false
        private set
    private var noAoaDevice: String? = null

    fun tryConnect(onLine: (String) -> Unit, onClosed: () -> Unit): Link? {
        val device = usb.deviceList.values.firstOrNull()
        partnerName = device?.productName
        partnerWithoutAoa = device != null && device.deviceName == noAoaDevice
        if (device == null || partnerWithoutAoa) return null
        if (device.vendorId == APPLE_VID) {
            markWithoutAoa(device)
            return null
        }
        if (!usb.hasPermission(device)) {
            if (permissionRequestedFor != device.deviceName) {
                permissionRequestedFor = device.deviceName
                log("Requesting USB permission for ${device.productName}")
                usb.requestPermission(device, permissionIntent(context))
            }
            return null
        }
        if (device.vendorId == GOOGLE_VID && device.productId in AOA_PIDS) {
            return open(device, onLine, onClosed)
        }
        switchToAccessoryMode(device)
        return null
    }

    private fun switchToAccessoryMode(device: UsbDevice) {
        val conn = usb.openDevice(device) ?: return log("Couldn't open ${device.productName}")
        try {
            val buf = ByteArray(2)
            val read = conn.controlTransfer(0xC0, AOA_GET_PROTOCOL, 0, 0, buf, 2, 1000)
            val protocol = (buf[1].toInt() shl 8) or (buf[0].toInt() and 0xFF)
            if (read < 0 || protocol < 1) return markWithoutAoa(device)
            listOf(ACCESSORY_MANUFACTURER, ACCESSORY_MODEL, "Power Struggle referee", "1", ACCESSORY_URI, "0")
                .forEachIndexed { index, s ->
                    val bytes = (s + "\u0000").toByteArray()
                    conn.controlTransfer(0x40, AOA_SEND_STRING, 0, index, bytes, bytes.size, 1000)
                }
            conn.controlTransfer(0x40, AOA_START, 0, 0, null, 0, 1000)
            log("Switched ${device.productName} to accessory mode (AOA v$protocol)")
        } finally {
            conn.close()
        }
    }

    private fun markWithoutAoa(device: UsbDevice) {
        noAoaDevice = device.deviceName
        partnerWithoutAoa = true
        log("${device.productName} can't run the app over USB: one-phone mode")
    }

    private fun open(device: UsbDevice, onLine: (String) -> Unit, onClosed: () -> Unit): Link? {
        val conn = usb.openDevice(device) ?: return null
        val intf = device.getInterface(0)
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return null
        }
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) {
            conn.close()
            return null
        }
        return Link(
            read = { conn.bulkTransfer(inEp, it, it.size, 0) },
            write = { if (conn.bulkTransfer(outEp, it, it.size, 1000) < 0) throw IOException("bulk write failed") },
            closeIo = { conn.releaseInterface(intf); conn.close() },
            onLine = onLine,
            onClosed = onClosed,
        )
    }
}

/** Player side: the accessory end of the link. */
class AoaAccessory(private val context: Context, private val log: (String) -> Unit) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private var permissionRequested = false

    private fun accessory() = usb.accessoryList?.firstOrNull { it.manufacturer == ACCESSORY_MANUFACTURER }

    fun isAttached() = accessory() != null

    fun tryConnect(onLine: (String) -> Unit, onClosed: () -> Unit): Link? {
        val accessory = accessory() ?: return null
        if (!usb.hasPermission(accessory)) {
            if (!permissionRequested) {
                permissionRequested = true
                log("Requesting accessory permission")
                usb.requestPermission(accessory, permissionIntent(context))
            }
            return null
        }
        val pfd = usb.openAccessory(accessory) ?: return null
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        return Link(
            read = { input.read(it) },
            write = { output.write(it) },
            closeIo = { pfd.close() },
            onLine = onLine,
            onClosed = onClosed,
        )
    }
}

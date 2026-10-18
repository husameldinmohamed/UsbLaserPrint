package com.husam.usbprint.usb

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
import java.io.Closeable
import java.io.IOException

/** A USB device exposing a printer-class (7) interface with a bulk OUT endpoint. */
data class PrinterInfo(
    val device: UsbDevice,
    val iface: UsbInterface,
    val outEp: UsbEndpoint,
    val inEp: UsbEndpoint?,
)

object UsbPrinterFinder {
    const val ACTION_USB_PERMISSION = "com.husam.usbprint.USB_PERMISSION"

    /**
     * Find a printer-class interface with a bulk OUT endpoint. Raw-data interfaces
     * (protocol 1 = unidirectional, 2 = bidirectional) are preferred; protocol 4 is
     * IPP-over-USB (AirPrint) and must not receive raw QPDL data.
     */
    fun find(usb: UsbManager): PrinterInfo? {
        val candidates = ArrayList<PrinterInfo>()
        for (dev in usb.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
                var out: UsbEndpoint? = null
                var inp: UsbEndpoint? = null
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep else inp = ep
                }
                if (out != null) candidates.add(PrinterInfo(dev, itf, out, inp))
            }
        }
        fun rank(p: PrinterInfo) = when (p.iface.interfaceProtocol) {
            2 -> 0; 1 -> 1; 3 -> 2; 4 -> 9; else -> 5
        }
        return candidates.minByOrNull { rank(it) }
    }

    fun requestPermission(ctx: Context, usb: UsbManager, dev: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usb.hasPermission(dev)) { onResult(true); return }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || usb.hasPermission(dev))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, intent, flags))
    }
}

/** An open connection to the printer. */
class UsbPrinterConnection private constructor(
    private val info: PrinterInfo,
    private val conn: UsbDeviceConnection,
) : Closeable {

    companion object {
        fun open(usb: UsbManager, info: PrinterInfo): UsbPrinterConnection {
            val c = usb.openDevice(info.device) ?: throw IOException("تعذّر فتح الطابعة (الإذن مرفوض؟)")
            if (!c.claimInterface(info.iface, true)) {
                c.close()
                throw IOException("تعذّر حجز واجهة الطابعة")
            }
            return UsbPrinterConnection(info, c)
        }
    }

    /** IEEE 1284 Device ID string (e.g. "MFG:HP;MDL:HP Laser MFP 135a;CMD:SPL,..."). */
    fun deviceId(): String? {
        val buf = ByteArray(1024)
        val idx = (info.iface.id shl 8) or info.iface.alternateSetting
        val n = conn.controlTransfer(0xA1, 0, 0, idx, buf, buf.size, 3000)
        if (n < 2) return null
        val len = minOf(((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF), n)
        if (len <= 2) return null
        return String(buf, 2, len - 2, Charsets.ISO_8859_1).trim()
    }

    /**
     * Write all bytes. Data is sent one USB packet at a time, so a timeout means
     * that packet was not accepted and can be retried safely (no duplicated data).
     */
    fun write(data: ByteArray, off0: Int = 0, len0: Int = data.size - off0) {
        val pkt = maxOf(64, info.outEp.maxPacketSize)
        val chunkSize = pkt * 32            // whole packets, retried only on full failure
        var off = off0
        val end = off0 + len0
        var waitedMs = 0L
        val buf = ByteArray(chunkSize)
        while (off < end) {
            val len = minOf(chunkSize, end - off)
            System.arraycopy(data, off, buf, 0, len)
            val n = conn.bulkTransfer(info.outEp, buf, len, 0)   // 0 = wait until accepted
            if (n > 0) {
                off += n
                waitedMs = 0
            } else {
                waitedMs += 500
                if (waitedMs > 60_000) throw IOException("الطابعة مش بتستقبل بيانات (اتأكد إنها شغالة وفيها ورق)")
                Thread.sleep(500)
            }
        }
    }

    override fun close() {
        try { conn.releaseInterface(info.iface) } catch (_: Exception) {}
        conn.close()
    }
}

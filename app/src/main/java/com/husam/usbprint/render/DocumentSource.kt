package com.husam.usbprint.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.husam.usbprint.spl.PageBitmap
import com.husam.usbprint.spl.PaperSize
import java.io.Closeable
import java.io.File

enum class ColorMode { TEXT, PHOTO }

/** A printable document (PDF or image) that can be rasterized page by page. */
abstract class DocumentSource : Closeable {
    abstract val pageCount: Int

    /** Page size in points (1/72 inch). */
    protected abstract fun pageSizePt(index: Int): Pair<Float, Float>

    /** Draw [index] into [strip]; [m] maps page points to strip pixels. */
    protected abstract fun drawStrip(index: Int, strip: Bitmap, m: Matrix)

    protected open fun beginPage(index: Int) {}
    protected open fun endPage() {}

    /**
     * Rasterize a page to a 1-bit bitmap the size of [paper] at [dpi].
     * The content is scaled to fit inside the page (keeping aspect ratio),
     * auto-rotated to portrait, centered, and optionally rotated 180°.
     */
    fun rasterize(index: Int, paper: PaperSize, dpi: Int, mode: ColorMode, rotate180: Boolean, darkness: Int = 128): PageBitmap {
        val w = paper.pixelWidth(dpi)
        val h = paper.pixelHeight(dpi)
        val rowBytes = w / 8
        val out = ByteArray(rowBytes * h)

        beginPage(index)
        try {
            val (pw, ph) = pageSizePt(index)
            val landscape = pw > ph
            val cw = if (landscape) ph else pw   // content size after rotation
            val ch = if (landscape) pw else ph
            val scale = minOf(w / cw, h / ch)
            val base = Matrix()
            if (landscape) {
                // rotate 90° clockwise: (x, y) -> (ph - y, x)
                base.postRotate(90f)
                base.postTranslate(ph, 0f)
            }
            base.postScale(scale, scale)
            base.postTranslate((w - cw * scale) / 2f, (h - ch * scale) / 2f)
            if (rotate180) base.postRotate(180f, w / 2f, h / 2f)

            val stripH = if (dpi >= 1200) 128 else 256
            val strip = Bitmap.createBitmap(w, stripH, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * stripH)
            var top = 0
            while (top < h) {
                val rows = minOf(stripH, h - top)
                strip.eraseColor(Color.WHITE)
                val m = Matrix(base)
                m.postTranslate(0f, -top.toFloat())
                drawStrip(index, strip, m)
                strip.getPixels(px, 0, w, 0, 0, w, rows)
                for (y in 0 until rows) {
                    val gy = top + y
                    val rowOff = gy * rowBytes
                    val bayerRow = (gy and 7) * 8
                    for (x in 0 until w) {
                        val c = px[y * w + x]
                        val a = (c ushr 24) and 0xFF
                        var r = (c shr 16) and 0xFF
                        var g = (c shr 8) and 0xFF
                        var b = c and 0xFF
                        if (a < 255) { // composite on white
                            r = (r * a + 255 * (255 - a)) / 255
                            g = (g * a + 255 * (255 - a)) / 255
                            b = (b * a + 255 * (255 - a)) / 255
                        }
                        val lum = (r * 299 + g * 587 + b * 114) / 1000
                        val black = when (mode) {
                            ColorMode.TEXT -> lum < darkness
                            ColorMode.PHOTO -> lum * 64 / 255 <= BAYER[bayerRow + (x and 7)] + (darkness - 128) / 4
                        }
                        if (black) {
                            val i = rowOff + (x ushr 3)
                            out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
                top += rows
            }
            strip.recycle()
        } finally {
            endPage()
        }
        return PageBitmap(w, h, out)
    }

    /** Small preview of a page for the UI. */
    fun thumbnail(index: Int, maxW: Int): Bitmap {
        beginPage(index)
        try {
            val (pw, ph) = pageSizePt(index)
            val s = maxW / pw
            val bmp = Bitmap.createBitmap(maxW, maxOf(1, (ph * s).toInt()), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { postScale(s, s) }
            drawStrip(index, bmp, m)
            return bmp
        } finally {
            endPage()
        }
    }

    companion object {
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
        )

        /** Open a content URI as PDF or image. The file is copied to cache first. */
        fun open(ctx: Context, uri: Uri, mime: String?): DocumentSource {
            val tmp = File(ctx.cacheDir, "doc.tmp")
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val isPdf = mime == "application/pdf" || tmp.inputStream().use { s ->
                val head = ByteArray(5); s.read(head) == 5 && String(head, Charsets.ISO_8859_1) == "%PDF-"
            }
            return if (isPdf) PdfSource(tmp) else ImageSource(tmp)
        }
    }
}

class PdfSource(file: File) : DocumentSource() {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private var page: PdfRenderer.Page? = null

    override val pageCount: Int get() = renderer.pageCount

    override fun beginPage(index: Int) { page = renderer.openPage(index) }
    override fun endPage() { page?.close(); page = null }

    override fun pageSizePt(index: Int): Pair<Float, Float> =
        page!!.width.toFloat() to page!!.height.toFloat()

    override fun drawStrip(index: Int, strip: Bitmap, m: Matrix) {
        page!!.render(strip, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }

    override fun close() {
        endPage()
        renderer.close()
        pfd.close()
    }
}

class ImageSource(file: File) : DocumentSource() {
    private val bitmap: Bitmap

    init {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 4000 || bounds.outHeight / sample > 4000) sample *= 2
        bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("الملف مش PDF ولا صورة مدعومة")
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override val pageCount: Int get() = 1
    override fun pageSizePt(index: Int) = bitmap.width.toFloat() to bitmap.height.toFloat()
    override fun drawStrip(index: Int, strip: Bitmap, m: Matrix) {
        Canvas(strip).drawBitmap(bitmap, m, paint)
    }
    override fun close() { bitmap.recycle() }
}

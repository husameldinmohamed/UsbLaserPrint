/*
 * QPDL (Samsung/HP "SPL") job generator for the HP Laser MFP 13x series
 * (HP Laser MFP 135a / 135w / 137fnw ...), monochrome, QPDL v3, algorithm 0x11.
 *
 * Ported from SpliX (src/compress.cpp, src/qpdl.cpp, src/printer.cpp) and the
 * "HP Laser MFP 13x Series" definition in ppd/hp.drv.in.
 * SpliX: (C) 2006-2008 Aurélien Croc (AP²C), HP models (C) 2025 ValdikSS.
 * Licensed under the GNU General Public License, version 2.
 */
package com.husam.usbprint.spl

import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Paper sizes accepted by the printer. Values from the SpliX PPD / ULD tables. */
enum class PaperSize(
    val label: String,
    val qpdlType: Int,
    val widthPt: Int,
    val heightPt: Int,
    private val bandBytes600: Int,
    private val bandBytes1200: Int,
) {
    A4("A4", 2, 595, 842, 608, 1216),
    A5("A5", 16, 420, 595, 416, 832),
    B5("B5 (JIS)", 11, 516, 729, 544, 1056),
    LETTER("Letter", 0, 612, 792, 640, 1248),
    LEGAL("Legal", 1, 612, 1008, 640, 1248),
    OFICIO("Oficio 216×340", 28, 612, 972, 640, 1248),
    FOLIO("Folio", 24, 595, 935, 640, 1248),
    EXECUTIVE("Executive", 3, 522, 756, 544, 1056);

    fun bandWidthBytes(dpi: Int): Int = if (dpi == 1200) bandBytes1200 else bandBytes600

    /** Full-page raster width in pixels (multiple of 8), as SpliX computes it. */
    fun pixelWidth(dpi: Int): Int = (ceil(widthPt * dpi / 72.0).toInt() + 7) and 7.inv()

    /** Full-page raster height in pixels. */
    fun pixelHeight(dpi: Int): Int = ceil(heightPt * dpi / 72.0).toInt()
}

enum class TonerDensity(val pjl: String) { LIGHT("1"), MEDIUM("3"), DARK("5") }

data class JobOptions(
    val paper: PaperSize = PaperSize.A4,
    val dpi: Int = 600,
    val density: TonerDensity = TonerDensity.MEDIUM,
    /** null = printer default, true = ON, false = OFF */
    val tonerSave: Boolean? = null,
    val userName: String = "android",
    val jobName: String = "document",
    /** yyyyMMdd used in "@PJL DEFAULT SERVICEDATE". */
    val serviceDate: String = "20260101",
)

/**
 * One page as a packed 1-bit bitmap: 1 = black, MSB first,
 * rowBytes = width / 8, width/height must equal paper.pixelWidth/Height(dpi).
 */
class PageBitmap(val width: Int, val height: Int, val data: ByteArray) {
    val rowBytes: Int get() = width / 8
    fun isBlank(): Boolean = data.all { it.toInt() == 0 }
}

class QpdlEncoder(private val opt: JobOptions) {

    private val hardMarginPt = 12.5      // ImageableArea margin from the PPD
    private val bandHeight = 128         // QPDL BandSize
    private val qpdlVersion = 3
    private val docHeader = intArrayOf(0, 0, 1)
    private val paperSource = 1          // Auto

    // ---------------------------------------------------------------- PJL

    fun jobHeader(): ByteArray {
        val sb = StringBuilder()
        sb.append("\u001B%-12345X")
        sb.append("@PJL DEFAULT SERVICEDATE=${opt.serviceDate}\n")
        sb.append("@PJL SET USERNAME=\"${ascii(opt.userName)}\"\n")
        sb.append("@PJL SET JOBNAME=\"${ascii(opt.jobName)}\"\n")
        when (opt.tonerSave) {
            true -> sb.append("@PJL SET ECONOMODE=ON\n")
            false -> sb.append("@PJL SET ECONOMODE=OFF\n")
            null -> {}
        }
        sb.append("@PJL DEFAULT POWERSAVE=ON\n")
        sb.append("@PJL DEFAULT POWERSAVETIME=5\n")
        sb.append("@PJL SET JAMRECOVERY=OFF\n")
        sb.append("@PJL SET DUPLEX=OFF\n")
        sb.append("@PJL SET PAPERTYPE=OFF\n")
        sb.append("@PJL SET ALTITUDE=LOW\n")
        sb.append("@PJL SET DENSITY=${opt.density.pjl}\n")
        sb.append("@PJL SET RET=NORMAL\n")
        sb.append("@PJL ENTER LANGUAGE = QPDL\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun jobFooter(): ByteArray = "\t\u001B%-12345X".toByteArray(Charsets.ISO_8859_1)

    private fun ascii(s: String): String =
        s.map { c -> if (c.code in 0x20..0x7E && c != '"') c else '_' }.joinToString("").take(60)

    // --------------------------------------------------------------- Pages

    private class Band(val number: Int, val width: Int, val height: Int, val data: ByteArray, val checksum: Long)

    /**
     * Encode a page. A blank page is sent as a header/footer with no bands, exactly
     * like SpliX does, so the printer still feeds a (blank) sheet.
     */
    fun encodePage(page: PageBitmap, copies: Int = 1): ByteArray {
        val dpi = opt.dpi
        require(page.width == opt.paper.pixelWidth(dpi) && page.height == opt.paper.pixelHeight(dpi)) {
            "Page bitmap size ${page.width}x${page.height} does not match paper"
        }
        val bands = compressPage(page)

        val out = ByteArrayOutputStream(64 * 1024)
        val widthArea = ceil(300 * (opt.paper.widthPt / 72.0)).toInt()
        val heightArea = ceil(300 * (opt.paper.heightPt / 72.0)).toInt()

        // Page header (17 bytes)
        out.write(0)
        out.write(dpi / 100)
        out.write((copies shr 8) and 0xFF)
        out.write(copies and 0xFF)
        out.write(opt.paper.qpdlType)
        out.write((widthArea shr 8) and 0xFF)
        out.write(widthArea and 0xFF)
        out.write((heightArea shr 8) and 0xFF)
        out.write(heightArea and 0xFF)
        out.write(paperSource)
        out.write(docHeader[0])
        out.write(1)            // duplex byte: SpliX sends 1 for simplex on non-0x15 printers
        out.write(0)            // tumble
        out.write(docHeader[1])
        out.write(qpdlVersion)
        out.write(docHeader[2])
        out.write(dpi / 100)

        for (b in bands) {
            val dataSize = b.data.size + 4 + 4   // + signature + checksum
            out.write(0x0C)
            out.write(b.number and 0xFF)
            out.write((b.width shr 8) and 0xFF)
            out.write(b.width and 0xFF)
            out.write((b.height shr 8) and 0xFF)
            out.write(b.height and 0xFF)
            out.write(0x11)
            out.write((dataSize ushr 24) and 0xFF)
            out.write((dataSize ushr 16) and 0xFF)
            out.write((dataSize ushr 8) and 0xFF)
            out.write(dataSize and 0xFF)
            // Sub-header signature 0x09ABCDEF, little endian
            out.write(0xEF); out.write(0xCD); out.write(0xAB); out.write(0x09)
            out.write(b.data)
            val cs = b.checksum + 0x09 + 0xAB + 0xCD + 0xEF
            out.write(((cs ushr 24) and 0xFF).toInt())
            out.write(((cs ushr 16) and 0xFF).toInt())
            out.write(((cs ushr 8) and 0xFF).toInt())
            out.write((cs and 0xFF).toInt())
        }

        // Page footer
        out.write(1)
        out.write((copies shr 8) and 0xFF)
        out.write(copies and 0xFF)
        return out.toByteArray()
    }

    private fun compressPage(page: PageBitmap): List<Band> {
        val dpi = opt.dpi
        val hardMarginX = (ceil(hardMarginPt * dpi / 72.0).toInt() + 7) and 7.inv()
        val hardMarginY = ceil(hardMarginPt * dpi / 72.0).toInt()
        val hardMarginXInB = hardMarginX / 8
        val lineWidthInB = (page.width + 7) / 8
        var pageHeight = page.height - hardMarginY
        val bandWidthInB = opt.paper.bandWidthBytes(dpi)
        val bandWidth = bandWidthInB * 8
        val bandSize = bandWidthInB * bandHeight
        val planes = page.data

        var index = hardMarginY * lineWidthInB
        val band = ByteArray(bandSize)
        val result = ArrayList<Band>()
        var bandNumber = 0
        val copyLimit = minOf(lineWidthInB - hardMarginXInB, bandWidthInB)

        while (pageHeight > 0) {
            var localHeight = bandHeight
            var theEnd = false
            if (pageHeight < bandHeight) {
                theEnd = true
                localHeight = pageHeight
                band.fill(0)
            }
            // reverseLineColumn: column-major bytes
            for (y in 0 until localHeight) {
                val rowBase = index + hardMarginXInB + y * lineWidthInB
                for (x in 0 until copyLimit) {
                    band[x * bandHeight + y] = planes[rowBase + x]
                }
                for (x in (lineWidthInB - hardMarginXInB) until bandWidthInB) {
                    band[x * bandHeight + y] = 0
                }
            }

            var empty = true
            for (v in band) if (v.toInt() != 0) { empty = false; break }
            if (!empty) {
                for (j in 0 until bandSize) band[j] = band[j].toInt().inv().toByte()
                val comp = Algo0x11.compress(band, bandSize)
                    ?: Algo0x11.compress(band, bandSize, bandSize + bandSize / 64 + 512)
                    ?: throw IllegalStateException("Compression failed")
                var sum = 0L
                for (v in comp) sum += (v.toInt() and 0xFF)
                result.add(Band(bandNumber, bandWidth, bandHeight, comp, sum))
            }
            bandNumber++
            index += lineWidthInB * bandHeight
            pageHeight = if (theEnd) 0 else pageHeight - bandHeight
        }
        return result
    }
}

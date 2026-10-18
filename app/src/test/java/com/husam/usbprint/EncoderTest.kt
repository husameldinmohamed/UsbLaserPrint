package com.husam.usbprint

import com.husam.usbprint.spl.JobOptions
import com.husam.usbprint.spl.PageBitmap
import com.husam.usbprint.spl.PaperSize
import com.husam.usbprint.spl.QpdlEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class EncoderTest {

    /** Same synthetic pages that were checked byte-for-byte against SpliX 2.0.2 rastertoqpdl. */
    private fun makePage(p: PaperSize, dpi: Int, kind: Int): PageBitmap {
        val w = p.pixelWidth(dpi); val h = p.pixelHeight(dpi); val rb = w / 8
        val d = ByteArray(rb * h)
        val rnd = Random(kind * 31 + dpi)
        fun set(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) { val i = y * rb + x / 8; d[i] = (d[i].toInt() or (0x80 ushr (x % 8))).toByte() } }
        when (kind) {
            0 -> {
                for (line in 0 until 60) { val y0 = 300 + line * (h - 600) / 60; var x = 250
                    while (x < w - 300) { val cw = 20 + rnd.nextInt(40); if (rnd.nextInt(6) != 0) for (yy in 0 until 50) for (xx in 0 until cw) if (rnd.nextInt(3) == 0) set(x + xx, y0 + yy); x += cw + 15 } }
                for (x in 0 until w) for (t in 0 until 4) set(x, 200 + t)
            }
            1 -> {
                val bayer = intArrayOf(0,32,8,40,2,34,10,42,48,16,56,24,50,18,58,26,12,44,4,36,14,46,6,38,60,28,52,20,62,30,54,22,3,35,11,43,1,33,9,41,51,19,59,27,49,17,57,25,15,47,7,39,13,45,5,37,63,31,55,23,61,29,53,21)
                for (y in 1000 until 4000) for (x in 0 until w) { val g = (x * 64 / w); if (g > bayer[(y % 8) * 8 + x % 8]) set(x, y) }
            }
            2 -> {}
            3 -> for (y in 0 until h) for (x in 0 until w) if (x < 3 || y < 3 || x > w - 4 || y > h - 4 || (y in 2000..2600 && rnd.nextBoolean())) set(x, y)
        }
        return PageBitmap(w, h, d)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun letterJobMatchesSplix() {
        val enc = QpdlEncoder(JobOptions(paper = PaperSize.LETTER, dpi = 600, userName = "husam", jobName = "test", serviceDate = "20260101"))
        val out = ByteArrayOutputStream()
        out.write(enc.jobHeader())
        for (k in 0..3) out.write(enc.encodePage(makePage(PaperSize.LETTER, 600, k)))
        out.write(enc.jobFooter())
        assertEquals(LETTER_SHA, sha(out.toByteArray()))
    }

    @Test
    fun pageRanges() {
        assertEquals(listOf(1, 2, 3, 4, 5), PageRange.parse("", 5))
        assertEquals(listOf(1, 2, 3, 5), PageRange.parse("1-3, 5", 5))
        assertEquals(listOf(2, 4, 5), PageRange.parse("٢، ٤-", 5))
    }

    @Test
    fun duplexPlan() {
        val p = DuplexPlan.build(listOf(1, 2, 3, 4, 5), 1, reverseBacks = true)
        assertEquals(listOf(1, 3, 5), p.fronts)
        assertEquals(listOf(null, 4, 2), p.backs)
    }

    companion object {
        const val LETTER_SHA = "d8c03ac65a6923112d5ad402ef1d2841b96ec27f4da08e19ce6490beb086f2ba"
    }
}

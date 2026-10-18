/*
 * Port of SpliX compression algorithm 0x11 (src/algo0x11.cpp) to Kotlin.
 *
 * Original: (C) 2006-2008 Aurélien Croc (AP²C), SpliX project.
 * Licensed under the GNU General Public License, version 2.
 */
package com.husam.usbprint.spl

object Algo0x11 {
    private const val COMPRESS_SAMPLE_RATE = 0x800
    private const val TABLE_PTR_SIZE = 0x40
    private const val MAX_UNCOMPRESSED_BYTES = 0x80
    private const val MAX_COMPRESSED_BYTES = 0x202
    private const val MIN_COMPRESSED_BYTES = 0x2
    private const val COMPRESSION_FLAG = 0x80

    /** Build the back-reference offset table (same as SpliX _lookupBestOccurs). */
    private fun lookupBestOccurs(data: ByteArray, size: Int): IntArray {
        val counts = IntArray(COMPRESS_SAMPLE_RATE)
        var i = COMPRESS_SAMPLE_RATE
        while (i < size) {
            val b = data[i]
            for (j in 1 until COMPRESS_SAMPLE_RATE) {
                if (data[i - j] == b) counts[j - 1]++
            }
            i += COMPRESS_SAMPLE_RATE
        }
        // Stable sort, highest count first (ties keep ascending offset order).
        val order = (0 until COMPRESS_SAMPLE_RATE).sortedByDescending { counts[it] }
        val table = IntArray(TABLE_PTR_SIZE)
        var oneIsPresent = false
        for (k in 0 until TABLE_PTR_SIZE) {
            table[k] = order[k] + 1
            if (table[k] == 1) oneIsPresent = true
        }
        if (!oneIsPresent) table[TABLE_PTR_SIZE - 1] = 1
        return table
    }

    /**
     * Compress one band (already column-reordered and inverted).
     * Returns the compressed bytes, or null if the output did not fit.
     */
    fun compress(data: ByteArray, size: Int, maxOutputSize: Int = size): ByteArray? {
        val ptr = lookupBestOccurs(data, size)
        val out = ByteArray(maxOutputSize)
        var w = 4
        var uncompSize = 0

        for (k in 0 until TABLE_PTR_SIZE) {
            out[w] = (ptr[k] and 0xFF).toByte()
            out[w + 1] = ((ptr[k] shr 8) and 0xFF).toByte()
            w += 2
            if (ptr[k] > uncompSize) uncompSize = ptr[k]
        }
        if (uncompSize > MAX_UNCOMPRESSED_BYTES) uncompSize = MAX_UNCOMPRESSED_BYTES
        out[0] = (uncompSize and 0xFF).toByte()
        out[1] = ((uncompSize shr 8) and 0xFF).toByte()
        out[2] = 0
        out[3] = 0
        var r = 0
        while (r < uncompSize) {
            out[w] = data[r]; r++; w++
        }

        var rawDataCounter = 0
        var rawDataCounterPtr = 0
        do {
            val maxCompSize = if (size - r > MAX_COMPRESSED_BYTES) MAX_COMPRESSED_BYTES else size - r

            if (maxCompSize == 0) {
                if (rawDataCounter != 0) out[rawDataCounterPtr] = (rawDataCounter - 1).toByte()
                break
            } else if (maxCompSize >= 2) {
                var bestCompCounter = 0
                var bestPtr = 0
                if (w + 2 >= maxOutputSize) {
                    w += 2
                    break
                }
                for (k in 0 until TABLE_PTR_SIZE) {
                    val p = ptr[k]
                    if (p > r) continue
                    val rTmp = r - p
                    var counter = 0
                    while (counter < maxCompSize && data[r + counter] == data[rTmp + counter]) counter++
                    if (counter > bestCompCounter) {
                        bestCompCounter = counter
                        bestPtr = k
                    }
                }
                if (bestCompCounter > MIN_COMPRESSED_BYTES) {
                    r += bestCompCounter
                    bestCompCounter -= 3
                    out[w] = (COMPRESSION_FLAG or (bestCompCounter and 0x7F)).toByte()
                    out[w + 1] = (((bestCompCounter shr 1) and 0xC0) or (bestPtr and 0x3F)).toByte()
                    w += 2
                    if (rawDataCounter != 0) {
                        out[rawDataCounterPtr] = (rawDataCounter - 1).toByte()
                        rawDataCounter = 0
                    }
                    continue
                }
            }

            rawDataCounter++
            if (rawDataCounter == 1) {
                if (w + 2 >= maxOutputSize) {
                    w += 2
                    break
                }
                rawDataCounterPtr = w
                w++
            } else if (rawDataCounter == MAX_UNCOMPRESSED_BYTES) {
                out[rawDataCounterPtr] = 0x7F
                rawDataCounter = 0
            }
            out[w] = data[r]
            w++
            r++
        } while (w < maxOutputSize)

        if (w >= maxOutputSize) return null
        return out.copyOf(w)
    }
}

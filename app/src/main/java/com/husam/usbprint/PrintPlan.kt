package com.husam.usbprint

/** Parses page ranges like "1-3, 5, 8-" (Arabic digits and comma accepted). Pages are 1-based. */
object PageRange {
    fun parse(text: String, pageCount: Int): List<Int> {
        val norm = text.map { c ->
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '،', '؛', ';', ' ' -> ','
                '–', '—', '_' -> '-'
                else -> c
            }
        }.joinToString("")
        if (norm.isBlank() || norm.all { it == ',' }) return (1..pageCount).toList()

        val pages = ArrayList<Int>()
        for (part in norm.split(',').filter { it.isNotBlank() }) {
            val p = part.trim()
            if ('-' in p) {
                val (a, b) = p.split('-', limit = 2)
                val from = if (a.isBlank()) 1 else a.toIntOrNull() ?: throw IllegalArgumentException("مدى غلط: $p")
                val to = if (b.isBlank()) pageCount else b.toIntOrNull() ?: throw IllegalArgumentException("مدى غلط: $p")
                if (from < 1 || to > pageCount || from > to)
                    throw IllegalArgumentException("المدى $p خارج الملف (عدد الصفحات $pageCount)")
                for (i in from..to) pages.add(i)
            } else {
                val n = p.toIntOrNull() ?: throw IllegalArgumentException("رقم غلط: $p")
                if (n < 1 || n > pageCount) throw IllegalArgumentException("الصفحة $n مش موجودة (عدد الصفحات $pageCount)")
                pages.add(n)
            }
        }
        return pages
    }
}

/**
 * Manual duplex plan. Each sheet has a front page and an optional back page
 * (null = blank back, the printer still feeds a sheet).
 */
data class DuplexPlan(val fronts: List<Int>, val backs: List<Int?>) {
    companion object {
        fun build(pages: List<Int>, copies: Int, reverseBacks: Boolean): DuplexPlan {
            val fronts = ArrayList<Int>()
            val backs = ArrayList<Int?>()
            repeat(copies) {
                var i = 0
                while (i < pages.size) {
                    fronts.add(pages[i])
                    backs.add(pages.getOrNull(i + 1))
                    i += 2
                }
            }
            return DuplexPlan(fronts, if (reverseBacks) backs.reversed() else backs)
        }
    }
}

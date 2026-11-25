package com.olympus.iptv.ui.login

import android.graphics.Bitmap
import android.graphics.Color

object QrBitmap {
    private const val SIZE = 33
    private const val DATA_LEN = 80
    private const val ECC_LEN = 20

    fun encode(text: String, pixels: Int): Bitmap {
        val modules = build(text.take(70))
        val quiet = 2
        val span = SIZE + quiet * 2
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val cell = pixels.toFloat() / span
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = Color.BLACK }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (!modules[y * SIZE + x]) continue
                canvas.drawRect(
                    (x + quiet) * cell,
                    (y + quiet) * cell,
                    (x + quiet + 1) * cell,
                    (y + quiet + 1) * cell,
                    paint
                )
            }
        }
        return bitmap
    }

    private fun build(text: String): BooleanArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bits = ArrayList<Int>(DATA_LEN * 8)
        fun put(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add((value shr i) and 1)
        }
        put(0b0100, 4)
        put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 255, 8)
        repeat(minOf(4, DATA_LEN * 8 - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val data = IntArray(DATA_LEN)
        var index = 0
        var pad = 0xEC
        while (index < DATA_LEN) {
            if (index * 8 < bits.size) {
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                data[index] = value
            } else {
                data[index] = pad
                pad = if (pad == 0xEC) 0x11 else 0xEC
            }
            index++
        }
        val codewords = data + reedSolomon(data, ECC_LEN)
        val function = BooleanArray(SIZE * SIZE)
        val dark = BooleanArray(SIZE * SIZE)
        fun set(x: Int, y: Int, on: Boolean, reserved: Boolean = true) {
            if (x !in 0 until SIZE || y !in 0 until SIZE) return
            dark[y * SIZE + x] = on
            if (reserved) function[y * SIZE + x] = true
        }
        fun finder(ox: Int, oy: Int) {
            for (y in -1..7) for (x in -1..7) {
                val on = x in 0..6 && y in 0..6 && (x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4))
                set(ox + x, oy + y, on)
            }
        }
        finder(0, 0)
        finder(SIZE - 7, 0)
        finder(0, SIZE - 7)
        for (i in 0 until SIZE) {
            set(i, 6, i % 2 == 0)
            set(6, i, i % 2 == 0)
        }
        fun alignment(cx: Int, cy: Int) {
            for (y in -2..2) for (x in -2..2) {
                val on = maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) != 1
                set(cx + x, cy + y, on)
            }
        }
        alignment(26, 26)
        alignment(26, 6)
        alignment(6, 26)
        set(8, SIZE - 8, true)
        for (i in 0 until 9) {
            if (i != 6) {
                function[8 * SIZE + i] = true
                function[i * SIZE + 8] = true
            }
            if (i < 8) {
                function[8 * SIZE + (SIZE - 1 - i)] = true
                function[(SIZE - 1 - i) * SIZE + 8] = true
            }
        }
        val stream = ArrayList<Int>(codewords.size * 8)
        for (word in codewords) putBits(stream, word, 8)
        var bit = 0
        var col = SIZE - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until SIZE) {
                val row = if (upward) SIZE - 1 - step else step
                for (c in intArrayOf(col, col - 1)) {
                    if (function[row * SIZE + c]) continue
                    val on = bit < stream.size && stream[bit] == 1
                    bit++
                    val masked = on != ((row + c) % 2 == 0)
                    dark[row * SIZE + c] = masked
                }
            }
            upward = !upward
            col -= 2
        }
        val format = formatBits(0b01000)
        for (i in 0 until 15) {
            val on = ((format shr i) and 1) == 1
            val row = if (i < 6) i else if (i < 8) i + 1 else SIZE - 15 + i
            dark[row * SIZE + 8] = on
            val col = if (i < 8) SIZE - 1 - i else if (i == 8) 7 else 14 - i
            dark[8 * SIZE + col] = on
        }
        return dark
    }

    private fun putBits(out: MutableList<Int>, value: Int, length: Int) {
        for (i in length - 1 downTo 0) out.add((value shr i) and 1)
    }

    private fun formatBits(data: Int): Int {
        var value = data shl 10
        val gen = 0b10100110111
        for (i in 4 downTo 0) {
            if (((value ushr (i + 10)) and 1) != 0) value = value xor (gen shl i)
        }
        return ((data shl 10) or value) xor 0b101010000010010
    }

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11d
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    private fun reedSolomon(data: IntArray, eccLen: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until eccLen) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor mul(gen[j], exp[i])
                next[j + 1] = next[j + 1] xor gen[j]
            }
            gen = next
        }
        val result = IntArray(data.size + eccLen)
        data.copyInto(result)
        for (i in data.indices) {
            val factor = result[i]
            if (factor == 0) continue
            for (j in gen.indices) result[i + j] = result[i + j] xor mul(gen[j], factor)
        }
        return result.copyOfRange(data.size, result.size)
    }
}

package dev.infusion.feature.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class PairingQrEncoder(
    private val size: Int = DEFAULT_SIZE,
) {
    init {
        require(size in 64..1024) { "QR size must be in 64..1024" }
    }

    fun encode(content: String): QrMatrix {
        require(content.isNotEmpty()) { "QR content must not be empty" }
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val cells = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                cells[y * size + x] = bitMatrix.get(x, y)
            }
        }
        return QrMatrix(size, cells)
    }

    companion object {
        const val DEFAULT_SIZE: Int = 256
    }
}

class QrMatrix(
    val size: Int,
    val cells: BooleanArray,
) {
    init {
        require(size > 0) { "size must be positive" }
        require(cells.size == size * size) { "cells must be size*size" }
    }

    operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]

    override fun equals(other: Any?): Boolean =
        other is QrMatrix && other.size == size && other.cells.contentEquals(cells)

    override fun hashCode(): Int = 31 * size + cells.contentHashCode()
}

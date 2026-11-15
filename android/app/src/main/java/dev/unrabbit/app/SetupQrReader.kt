package dev.unrabbit.app

import com.google.zxing.*
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/** Consider all finder-pattern candidates: dense invitations can contain false ones. */
internal class SetupQrReader(private val configuredHints: Map<DecodeHintType, *> = emptyMap<DecodeHintType, Any>()) : Reader {
    override fun decode(image: BinaryBitmap): Result = decode(image, configuredHints)
    override fun decode(image: BinaryBitmap, hints: Map<DecodeHintType, *>?): Result {
        val codes = QRCodeMultiReader().decodeMultiple(image, hints ?: configuredHints)
        // Never choose a VM arbitrarily when two codes are visible.
        if (codes.size != 1) throw NotFoundException.getNotFoundInstance()
        return codes.single()
    }
    override fun reset() {}
}

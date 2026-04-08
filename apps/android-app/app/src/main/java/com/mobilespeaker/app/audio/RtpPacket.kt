package com.mobilespeaker.app.audio

data class RtpPacket(
    val sequence: Int,
    val timestamp: Long,
    val payloadType: Int,
    val payload: ByteArray
) {
    companion object {
        fun parse(buf: ByteArray, size: Int): RtpPacket? {
            if (size <= 12) return null
            val version = (buf[0].toInt() shr 6) and 0x03
            if (version != 2) return null
            val payloadType = buf[1].toInt() and 0x7F
            val sequence = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val ts =
                ((buf[4].toLong() and 0xFF) shl 24) or
                    ((buf[5].toLong() and 0xFF) shl 16) or
                    ((buf[6].toLong() and 0xFF) shl 8) or
                    (buf[7].toLong() and 0xFF)
            val payload = buf.copyOfRange(12, size)
            return RtpPacket(sequence, ts, payloadType, payload)
        }
    }
}

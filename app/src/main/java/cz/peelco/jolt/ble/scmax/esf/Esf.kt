package cz.peelco.jolt.ble.scmax.esf

/**
 * The value model of Pavlok's "ESF" wire format used by Shock Clock Max,
 * reconstructed from source paths in the official app: a small
 * self-describing, LEB128-length-prefixed TLV format.
 */
sealed interface EsfValue {
    data object Null : EsfValue

    data class Bool(
        val value: Boolean,
    ) : EsfValue

    data class Int(
        val value: Long,
    ) : EsfValue

    data class Str(
        val value: String,
    ) : EsfValue

    class FixBytes(
        val value: ByteArray,
    ) : EsfValue {
        override fun equals(other: Any?): Boolean = other is FixBytes && value.contentEquals(other.value)

        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }

    data class Array(
        val elements: List<EsfValue>,
    ) : EsfValue

    data class ListValue(
        val elements: List<EsfValue>,
    ) : EsfValue

    data class MapValue(
        val pairs: Map<String, EsfValue>,
    ) : EsfValue
}

/**
 * Tag byte per value type. PLACEHOLDERS: the real bytes are compiled into the
 * official app and were not recoverable by string analysis. They make the
 * codec self-consistent, nothing more.
 */
enum class EsfTag(
    val byte: kotlin.Int,
) {
    NULL(0x00),
    BOOL(0x01),
    INT(0x02),
    STRING(0x03),
    FIX_BYTES(0x04),
    ARRAY(0x05),
    LIST(0x06),
    MAP(0x07),
    ;

    companion object {
        fun of(byte: kotlin.Int): EsfTag? = entries.firstOrNull { it.byte == byte }
    }
}

/** Unsigned LEB128, confirmed as the base integer encoding. */
object Leb128 {
    class DecodeException(
        message: String,
    ) : Exception(message)

    fun encode(value: ULong): ByteArray {
        val out = mutableListOf<Byte>()
        var remaining = value
        do {
            var byte = (remaining and 0x7FuL).toInt()
            remaining = remaining shr 7
            if (remaining != 0uL) byte = byte or 0x80
            out += byte.toByte()
        } while (remaining != 0uL)
        return out.toByteArray()
    }

    /** One varint at [offset]: the value and how many bytes it took. */
    fun decode(
        bytes: ByteArray,
        offset: kotlin.Int,
    ): Pair<ULong, kotlin.Int> {
        var result = 0uL
        var shift = 0
        var index = offset
        while (true) {
            if (index >= bytes.size) throw DecodeException("truncated")
            if (shift >= 64) throw DecodeException("overflow")
            val byte = bytes[index].toInt() and 0xFF
            index++
            result = result or ((byte and 0x7F).toULong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result to (index - offset)
    }
}

/** Encodes and decodes [EsfValue] trees, with LEB128 for lengths and zig-zag integers. */
object EsfCodec {
    class DecodeException(
        message: String,
    ) : Exception(message)

    fun encode(value: EsfValue): ByteArray =
        when (value) {
            EsfValue.Null -> byteArrayOf(EsfTag.NULL.byte.toByte())
            is EsfValue.Bool -> byteArrayOf(EsfTag.BOOL.byte.toByte(), if (value.value) 1 else 0)
            is EsfValue.Int -> byteArrayOf(EsfTag.INT.byte.toByte()) + Leb128.encode(zigZagEncode(value.value))
            is EsfValue.Str -> {
                val utf8 = value.value.toByteArray(Charsets.UTF_8)
                byteArrayOf(EsfTag.STRING.byte.toByte()) + Leb128.encode(utf8.size.toULong()) + utf8
            }
            is EsfValue.FixBytes -> byteArrayOf(EsfTag.FIX_BYTES.byte.toByte()) + Leb128.encode(value.value.size.toULong()) + value.value
            is EsfValue.Array -> sequence(EsfTag.ARRAY, value.elements)
            is EsfValue.ListValue -> sequence(EsfTag.LIST, value.elements)
            is EsfValue.MapValue ->
                value.pairs.toSortedMap().entries.fold(byteArrayOf(EsfTag.MAP.byte.toByte()) + Leb128.encode(value.pairs.size.toULong())) { bytes, (key, element) ->
                    bytes + encode(EsfValue.Str(key)) + encode(element)
                }
        }

    private fun sequence(
        tag: EsfTag,
        elements: List<EsfValue>,
    ): ByteArray = elements.fold(byteArrayOf(tag.byte.toByte()) + Leb128.encode(elements.size.toULong())) { bytes, element -> bytes + encode(element) }

    fun decode(bytes: ByteArray): EsfValue = Reader(bytes).value()

    private class Reader(
        val bytes: ByteArray,
    ) {
        var offset = 0

        fun value(): EsfValue {
            if (offset >= bytes.size) throw DecodeException("truncated")
            val tagByte = bytes[offset].toInt() and 0xFF
            offset++
            return when (EsfTag.of(tagByte) ?: throw DecodeException("unknown tag 0x%02X".format(tagByte))) {
                EsfTag.NULL -> EsfValue.Null
                EsfTag.BOOL -> {
                    if (offset >= bytes.size) throw DecodeException("truncated")
                    EsfValue.Bool(bytes[offset++].toInt() != 0)
                }
                EsfTag.INT -> EsfValue.Int(zigZagDecode(varint()))
                EsfTag.STRING -> EsfValue.Str(take(length()).toString(Charsets.UTF_8))
                EsfTag.FIX_BYTES -> EsfValue.FixBytes(take(length()))
                EsfTag.ARRAY -> EsfValue.Array(List(length()) { value() })
                EsfTag.LIST -> EsfValue.ListValue(List(length()) { value() })
                EsfTag.MAP ->
                    EsfValue.MapValue(
                        buildMap {
                            repeat(length()) {
                                val key = value() as? EsfValue.Str ?: throw DecodeException("map key is not a string")
                                put(key.value, value())
                            }
                        },
                    )
            }
        }

        private fun varint(): ULong {
            val (value, length) =
                try {
                    Leb128.decode(bytes, offset)
                } catch (error: Leb128.DecodeException) {
                    throw DecodeException(error.message ?: "bad varint")
                }
            offset += length
            return value
        }

        private fun length(): kotlin.Int = varint().toInt()

        private fun take(count: kotlin.Int): ByteArray {
            if (offset + count > bytes.size) throw DecodeException("truncated")
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }
    }

    private fun zigZagEncode(value: Long): ULong = ((value shl 1) xor (value shr 63)).toULong()

    private fun zigZagDecode(value: ULong): Long = (value shr 1).toLong() xor -(value and 1uL).toLong()
}

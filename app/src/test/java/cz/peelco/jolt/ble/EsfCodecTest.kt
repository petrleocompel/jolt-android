package cz.peelco.jolt.ble

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.ble.scmax.esf.EsfCodec
import cz.peelco.jolt.ble.scmax.esf.EsfValue
import cz.peelco.jolt.ble.scmax.esf.Leb128
import org.junit.Test

class EsfCodecTest {
    private fun roundTrip(value: EsfValue) = assertThat(EsfCodec.decode(EsfCodec.encode(value))).isEqualTo(value)

    @Test
    fun leb128MatchesTheStandardEncoding() {
        assertThat(Leb128.encode(0uL)).isEqualTo(byteArrayOf(0x00))
        assertThat(Leb128.encode(127uL)).isEqualTo(byteArrayOf(0x7F))
        assertThat(Leb128.encode(128uL)).isEqualTo(byteArrayOf(0x80.toByte(), 0x01))
        assertThat(Leb128.encode(624_485uL)).isEqualTo(byteArrayOf(0xE5.toByte(), 0x8E.toByte(), 0x26))
        assertThat(Leb128.decode(byteArrayOf(0xE5.toByte(), 0x8E.toByte(), 0x26), 0)).isEqualTo(624_485uL to 3)
    }

    @Test
    fun scalarsRoundTrip() {
        roundTrip(EsfValue.Null)
        roundTrip(EsfValue.Bool(true))
        roundTrip(EsfValue.Int(0))
        roundTrip(EsfValue.Int(-1))
        roundTrip(EsfValue.Int(Long.MAX_VALUE))
        roundTrip(EsfValue.Int(Long.MIN_VALUE))
        roundTrip(EsfValue.Str("zap ⚡"))
        roundTrip(EsfValue.FixBytes(byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())))
    }

    @Test
    fun containersRoundTrip() {
        roundTrip(EsfValue.Array(listOf(EsfValue.Int(1), EsfValue.Str("two"))))
        roundTrip(EsfValue.ListValue(emptyList()))
        roundTrip(EsfValue.MapValue(mapOf("kind" to EsfValue.Str("zap"), "intensity" to EsfValue.Int(30), "nested" to EsfValue.MapValue(emptyMap()))))
    }

    @Test
    fun smallNegativeNumbersStaySmall() {
        assertThat(EsfCodec.encode(EsfValue.Int(-1)).size).isEqualTo(2)
    }

    @Test
    fun malformedInputIsRejected() {
        assertThat(runCatching { EsfCodec.decode(byteArrayOf(0xEE.toByte())) }.exceptionOrNull()).isInstanceOf(EsfCodec.DecodeException::class.java)
        assertThat(runCatching { EsfCodec.decode(byteArrayOf(0x03, 0x05, 0x41)) }.exceptionOrNull()).isInstanceOf(EsfCodec.DecodeException::class.java)
        assertThat(runCatching { EsfCodec.decode(ByteArray(0)) }.exceptionOrNull()).isInstanceOf(EsfCodec.DecodeException::class.java)
    }
}

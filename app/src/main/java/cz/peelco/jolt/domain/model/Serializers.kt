package cz.peelco.jolt.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Jolt Server ids are lowercase UUIDs compared case-sensitively.
 * `UUID.toString()` is already lowercase, which is exactly what the server
 * wants — the iOS app needs an `apiString` helper for this, Android does not.
 */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/**
 * RFC 3339 timestamps. The server emits fractional seconds on some fields and
 * not on others, and a proxy or an older server may send an offset instead of
 * `Z`; all of those parse.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())

    fun parseInstant(text: String): Instant =
        runCatching { Instant.parse(text) }.getOrElse { OffsetDateTime.parse(text).toInstant() }
}

typealias SerialUuid =
    @kotlinx.serialization.Serializable(with = UuidSerializer::class)
    UUID

typealias SerialInstant =
    @kotlinx.serialization.Serializable(with = InstantSerializer::class)
    Instant

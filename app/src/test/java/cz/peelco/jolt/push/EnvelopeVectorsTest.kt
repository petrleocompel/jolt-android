package cz.peelco.jolt.push

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.IncomingPush
import cz.peelco.jolt.domain.model.StimulusKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec

/** Normative vectors from jolt-relay `spec/vectors/` (see resources/vectors/README.md). */
private fun vector(name: String): JsonObject =
    Json.parseToJsonElement(EnvelopeVectorsTest::class.java.getResource("/vectors/$name")!!.readText()).jsonObject

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

class EnvelopeVectorsTest {
    private val vectors = vector("envelope-v1.json")
    private val key = Base64Url.decode(vectors.string("keyB64u"))
    private val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }

    private fun case(name: String) = cases.first { it.string("name") == name }

    private fun envelope(case: JsonObject): Envelope {
        val env = case.getValue("envelope").jsonObject
        return Envelope(env.getValue("v").jsonPrimitive.int, env.string("kid"), env.string("n"), env.string("ct"))
    }

    private fun data(case: JsonObject) =
        mapOf(
            "type" to case.string("kind"),
            "srv" to case.string("serverId"),
            "enc" to Json.encodeToString(Envelope.serializer(), envelope(case)),
        )

    @Test
    fun theKeyIdMatches() {
        assertThat(EnvelopeCrypto.kid(key)).isEqualTo(vectors.string("kid"))
        cases.forEach { assertThat(envelope(it).kid).isEqualTo(vectors.string("kid")) }
    }

    @Test
    fun theAadMatches() {
        cases.forEach { case ->
            assertThat(EnvelopeCrypto.aad(case.string("serverId"), case.string("kind")).toString(Charsets.UTF_8)).isEqualTo(case.string("aad"))
        }
    }

    @Test
    fun everyPositiveCaseDecryptsToTheExactPlaintext() {
        cases.forEach { case ->
            val plaintext = EnvelopeCrypto.open(key, case.string("serverId"), case.string("kind"), envelope(case))
            assertThat(plaintext.toString(Charsets.UTF_8)).isEqualTo(case.string("plaintext"))
        }
    }

    @Test
    fun sealingWithTheVectorNonceReproducesTheCiphertext() {
        cases.forEach { case ->
            val expected = envelope(case)
            val sealed =
                EnvelopeCrypto.seal(key, case.string("serverId"), case.string("kind"), case.string("plaintext").toByteArray(), Base64Url.decode(expected.n))
            assertThat(sealed).isEqualTo(expected)
        }
    }

    @Test
    fun theNegativeCasesFailToDecrypt() {
        vectors.getValue("negative").jsonArray.map { it.jsonObject }.forEach { negative ->
            val source = case(negative.string("envelopeOf"))
            val kind = negative["kind"]?.jsonPrimitive?.content ?: source.string("kind")
            val serverId = negative["serverId"]?.jsonPrimitive?.content ?: source.string("serverId")
            assertThat(negative.string("expect")).isEqualTo("decrypt-fails")
            assertThat(runCatching { EnvelopeCrypto.open(key, serverId, kind, envelope(source)) }.exceptionOrNull())
                .isInstanceOf(EnvelopeCrypto.EnvelopeException::class.java)
        }
    }

    @Test
    fun aDataMessageDecodesIntoThePayload() {
        val poke = EnvelopeCrypto.decode(data(case("poke"))) { _, _ -> key }
        val payload = ((poke as EnvelopeCrypto.Decoded.Push).push as IncomingPush.Poke).payload
        assertThat(payload.senderDisplayName).isEqualTo("Alice")
        assertThat(payload.stimulus.kind).isEqualTo(StimulusKind.ZAP)
        assertThat(payload.stimulus.repetitions).isEqualTo(2)
        assertThat(payload.serverId).isEqualTo("srv_kzdvvj2umnduyauf35o36k6kw4")

        val test = EnvelopeCrypto.decode(data(case("test-with-stimulus"))) { _, _ -> key }
        val testPayload = ((test as EnvelopeCrypto.Decoded.Push).push as IncomingPush.Test).payload
        assertThat(testPayload.stimulus?.kind).isEqualTo(StimulusKind.VIBE)
    }

    @Test
    fun theKeyIsLookedUpByServerAndKid() {
        var asked: Pair<String, String>? = null
        EnvelopeCrypto.decode(data(case("poke"))) { serverId, kid ->
            asked = serverId to kid
            key
        }
        assertThat(asked).isEqualTo("srv_kzdvvj2umnduyauf35o36k6kw4" to "AOmIZ37s-Uw")
        assertThat(EnvelopeCrypto.decode(data(case("poke"))) { _, _ -> null }).isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)
    }

    @Test
    fun aRelabelledOuterTypeOrServerIsDropped() {
        val poke = case("poke")
        val relabelled = data(poke) + ("type" to "test")
        assertThat(EnvelopeCrypto.decode(relabelled) { _, _ -> key }).isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)
        val otherServer = data(poke) + ("srv" to "srv_aaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(EnvelopeCrypto.decode(otherServer) { _, _ -> key }).isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)
    }

    @Test
    fun anInnerTypeOrServerIdThatDisagreesIsDropped() {
        val serverId = "srv_kzdvvj2umnduyauf35o36k6kw4"
        fun message(
            kind: String,
            plaintext: String,
        ) = mapOf("type" to kind, "srv" to serverId, "enc" to Json.encodeToString(Envelope.serializer(), EnvelopeCrypto.seal(key, serverId, kind, plaintext.toByteArray())))

        val wrongInnerType = case("test-with-stimulus").string("plaintext")
        assertThat(EnvelopeCrypto.decode(message("poke", wrongInnerType)) { _, _ -> key }).isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)

        val wrongInnerServer = case("poke").string("plaintext").replace(serverId, "srv_bbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertThat(EnvelopeCrypto.decode(message("poke", wrongInnerServer)) { _, _ -> key }).isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)
    }

    @Test
    fun anythingElseIsNotARelayPush() {
        assertThat(EnvelopeCrypto.decode(mapOf("type" to "chat")) { _, _ -> key }).isEqualTo(EnvelopeCrypto.Decoded.NotJolt)
        assertThat(EnvelopeCrypto.decode(emptyMap()) { _, _ -> key }).isEqualTo(EnvelopeCrypto.Decoded.NotJolt)
        assertThat(EnvelopeCrypto.decode(mapOf("type" to "poke", "srv" to "srv_x", "enc" to "{not json")) { _, _ -> key })
            .isInstanceOf(EnvelopeCrypto.Decoded.Unreadable::class.java)
    }

    @Test
    fun aFreshSealRoundTrips() {
        val fresh = EnvelopeCrypto.newKey()
        val sealed = EnvelopeCrypto.seal(fresh, "srv_x", "poke", "hello".toByteArray())
        assertThat(Base64Url.decode(sealed.n)).hasLength(12)
        assertThat(EnvelopeCrypto.open(fresh, "srv_x", "poke", sealed).toString(Charsets.UTF_8)).isEqualTo("hello")
    }
}

class ServerIdVectorTest {
    private val vector = vector("server-id.json")

    @Test
    fun theServerIdDerivesFromThePublicKey() {
        val publicKey = Base64Url.decode(vector.string("publicKey"))
        assertThat(publicKey).hasLength(32)
        assertThat(ServerId.fromPublicKey(publicKey)).isEqualTo(vector.string("serverId"))
        assertThat(ServerId.fromPublicKey(publicKey)).hasLength(30)
    }

    @Test
    fun thePublicKeyDerivesFromTheSeed() {
        // The JDK has Ed25519 but no seed-to-key call; a key generator fed the
        // seed as its "randomness" derives the same key pair.
        val seed = vector.string("ed25519SeedHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fixed =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    seed.copyInto(bytes)
                }
            }
        val generator = KeyPairGenerator.getInstance("Ed25519").apply { initialize(NamedParameterSpec.ED25519, fixed) }
        val encoded = generator.generateKeyPair().public.encoded
        // X.509 SubjectPublicKeyInfo: a 12-byte header, then the raw key.
        assertThat(Base64Url.encode(encoded.copyOfRange(encoded.size - 32, encoded.size))).isEqualTo(vector.string("publicKey"))
    }

    @Test
    fun base32IsLowercaseAndUnpadded() {
        assertThat(ServerId.base32("foobar".toByteArray())).isEqualTo("mzxw6ytboi")
        assertThat(ServerId.base32("f".toByteArray())).isEqualTo("my")
    }
}

class RelayAllowListTest {
    @Test
    fun onlyListedHttpsHostsAreAllowed() {
        val list = RelayAllowList.parse(" relay.example , push.example ")
        assertThat(list.allows("https://relay.example/")).isTrue()
        assertThat(list.allows("https://RELAY.example/v1")).isTrue()
        assertThat(list.allows("http://relay.example/")).isFalse()
        assertThat(list.allows("https://evil.example/")).isFalse()
        assertThat(list.allows("https://relay.example.evil.example/")).isFalse()
        assertThat(list.allows("not a url")).isFalse()
    }

    @Test
    fun anEmptySettingTrustsNothing() {
        val list = RelayAllowList.parse("")
        assertThat(list.isEmpty).isTrue()
        assertThat(list.allows("https://relay.example/")).isFalse()
    }

    @Test
    fun theAttestationNonceBindsChallengeTokenAndServer() {
        val nonce = Attestor.nonce("c", "t", "srv_x")
        assertThat(nonce).hasLength(43)
        assertThat(nonce).isNotEqualTo(Attestor.nonce("c", "t2", "srv_x"))
    }
}

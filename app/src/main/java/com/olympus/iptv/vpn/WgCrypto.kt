package com.olympus.iptv.vpn

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.util.encoders.Base64

internal object WgKeys {
    private val random = SecureRandom()

    fun generate(): Pair<ByteArray, ByteArray> {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(random))
        val pair = generator.generateKeyPair()
        val privateKey = (pair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (pair.public as X25519PublicKeyParameters).encoded
        return privateKey to publicKey
    }

    fun publicOf(privateKey: ByteArray): ByteArray {
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    fun shared(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        if (out.all { it == 0.toByte() }) throw IllegalArgumentException("key")
        return out
    }

    fun text(raw: ByteArray): String = Base64.toBase64String(raw)

    fun raw(text: String): ByteArray {
        val decoded = Base64.decode(text.trim())
        if (decoded.size != 32) throw IllegalArgumentException("key")
        return decoded
    }
}

internal class WgHandshake(
    private val localPrivate: ByteArray,
    private val remoteStatic: ByteArray,
    private val preshared: ByteArray
) {
    private val localPublic = WgKeys.publicOf(localPrivate)
    private val staticStatic = WgKeys.shared(localPrivate, remoteStatic)
    private var chain = ByteArray(32)
    private var hash = ByteArray(32)
    private var ephemeralPrivate = ByteArray(32)
    private var localIndex = 0
    private var remoteIndex = 0
    private var sendKey = ByteArray(32)
    private var recvKey = ByteArray(32)
    private val sendCipher = ChaCha20Poly1305()
    private val recvCipher = ChaCha20Poly1305()
    private val sendNonce = ByteArray(12)
    private val recvNonce = ByteArray(12)
    private var sendParam = KeyParameter(sendKey)
    private var recvParam = KeyParameter(recvKey)
    private var sendBound = sendKey
    private var recvBound = recvKey
    private var cookie: ByteArray? = null
    private var cookieAt = 0L
    private var packet = ByteArray(0)
    private var ready = false
    private var sendCounter = 0L
    private val replay = ReplayWindow()

    fun initiation(): ByteArray {
        val ephemeral = WgKeys.generate()
        ephemeralPrivate = ephemeral.first
        val ephemeralPublic = ephemeral.second
        hash = mix(INITIAL_HASH, remoteStatic)
        hash = mix(hash, ephemeralPublic)
        chain = kdf1(INITIAL_CHAIN, ephemeralPublic)
        val secret = WgKeys.shared(ephemeralPrivate, remoteStatic)
        val first = kdf2(chain, secret)
        chain = first.first
        val staticEncrypted = seal(first.second, ZERO_NONCE, localPublic, hash)
        hash = mix(hash, staticEncrypted)
        val second = kdf2(chain, staticStatic)
        chain = second.first
        val stamped = seal(second.second, ZERO_NONCE, tai64n(), hash)
        hash = mix(hash, stamped)
        localIndex = randomIndex()
        val message = ByteArray(148)
        put32(message, 0, 1)
        put32(message, 4, localIndex)
        ephemeralPublic.copyInto(message, 8)
        staticEncrypted.copyInto(message, 40)
        stamped.copyInto(message, 88)
        packet = message
        ready = false
        return outbound()
    }

    fun consumeResponse(message: ByteArray): Boolean {
        if (message.size != 92 || read32(message, 0) != 2 || read32(message, 8) != localIndex) return false
        if (!macOk(message, localPublic)) return false
        val ephemeral = message.copyOfRange(12, 44)
        val empty = message.copyOfRange(44, 60)
        var nextHash = mix(hash, ephemeral)
        var nextChain = kdf1(chain, ephemeral)
        nextChain = kdf1(nextChain, WgKeys.shared(ephemeralPrivate, ephemeral))
        nextChain = kdf1(nextChain, WgKeys.shared(localPrivate, ephemeral))
        val mixed = kdf3(nextChain, preshared)
        nextHash = mix(nextHash, mixed.second)
        if (open(mixed.third, ZERO_NONCE, empty, nextHash) == null) return false
        nextHash = mix(nextHash, empty)
        val keys = kdf2(mixed.first, ByteArray(0))
        sendKey = keys.first
        recvKey = keys.second
        remoteIndex = read32(message, 4)
        chain = mixed.first
        hash = nextHash
        ready = true
        sendCounter = 0L
        replay.reset()
        return true
    }

    fun consumeCookie(message: ByteArray, now: Long): Boolean {
        if (message.size != 64 || read32(message, 0) != 3 || read32(message, 4) != localIndex) return false
        if (packet.size < 32) return false
        val key = mix(LABEL_COOKIE, remoteStatic)
        val nonce = message.copyOfRange(8, 32)
        val body = message.copyOfRange(32, 64)
        val mac1 = packet.copyOfRange(packet.size - 32, packet.size - 16)
        val opened = xopen(key, nonce, body, mac1) ?: return false
        if (opened.size != 16) return false
        cookie = opened
        cookieAt = now
        return true
    }

    fun outbound(): ByteArray {
        val copy = packet.copyOf()
        for (index in copy.size - 32 until copy.size) copy[index] = 0
        val mac1Key = mix(LABEL_MAC1, remoteStatic)
        val mac1At = copy.size - 32
        val mac2At = copy.size - 16
        blakeKeyed(mac1Key, copy.copyOf(mac1At), 16).copyInto(copy, mac1At)
        val current = cookie
        if (current != null && System.currentTimeMillis() - cookieAt < 120_000L) {
            blakeKeyed(current, copy.copyOf(mac2At), 16).copyInto(copy, mac2At)
        }
        packet = copy
        return copy
    }

    fun wirePacket(): ByteArray = packet.copyOf()

    fun sealPacket(plain: ByteArray): ByteArray? {
        val out = ByteArray(16 + plain.size + 16)
        val size = sealInto(plain, plain.size, out)
        return if (size < 0) null else out.copyOf(size)
    }

    fun sealInto(plain: ByteArray, plainLength: Int, out: ByteArray): Int {
        if (!ready || plainLength < 0 || out.size < 16 + plainLength + 16) return -1
        val counter = sendCounter++
        put64(sendNonce, 4, counter)
        return try {
            val cipher = sendCipher
            cipher.init(true, AEADParameters(sendParameters(), 128, sendNonce, EMPTY))
            var written = cipher.processBytes(plain, 0, plainLength, out, 16)
            written += cipher.doFinal(out, 16 + written)
            put32(out, 0, 4)
            put32(out, 4, remoteIndex)
            put64(out, 8, counter)
            16 + written
        } catch (_: Exception) {
            -1
        }
    }

    fun openPacket(message: ByteArray): ByteArray? {
        if (message.size < 32) return null
        val out = ByteArray(message.size - 16)
        val size = openInto(message, message.size, out)
        return if (size < 0) null else out.copyOf(size)
    }

    fun openInto(message: ByteArray, messageLength: Int, out: ByteArray): Int {
        if (!ready || messageLength < 32 || read32(message, 0) != 4 || read32(message, 4) != localIndex) return -1
        val counter = read64(message, 8)
        if (!replay.accept(counter)) return -1
        put64(recvNonce, 4, counter)
        return try {
            val cipher = recvCipher
            cipher.init(false, AEADParameters(recvParameters(), 128, recvNonce, EMPTY))
            var written = cipher.processBytes(message, 16, messageLength - 16, out, 0)
            written += cipher.doFinal(out, written)
            written
        } catch (_: Exception) {
            -1
        }
    }

    private fun sendParameters(): KeyParameter {
        if (sendBound !== sendKey) {
            sendParam = KeyParameter(sendKey)
            sendBound = sendKey
        }
        return sendParam
    }

    private fun recvParameters(): KeyParameter {
        if (recvBound !== recvKey) {
            recvParam = KeyParameter(recvKey)
            recvBound = recvKey
        }
        return recvParam
    }

    companion object {
        private val INITIAL_CHAIN = blake(CONSTRUCTION.toByteArray(Charsets.US_ASCII))
        private val INITIAL_HASH = mix(INITIAL_CHAIN, IDENTIFIER.toByteArray(Charsets.US_ASCII))
        private val ZERO_NONCE = ByteArray(12)
        private val EMPTY = ByteArray(0)
        private val LABEL_MAC1 = "mac1----".toByteArray(Charsets.US_ASCII)
        private val LABEL_COOKIE = "cookie--".toByteArray(Charsets.US_ASCII)
        private const val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
        private const val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com"
        private val random = SecureRandom()

        fun respond(localPrivate: ByteArray, initiation: ByteArray, preshared: ByteArray): WgHandshake? {
            if (initiation.size != 148 || read32(initiation, 0) != 1) return null
            val localPublic = WgKeys.publicOf(localPrivate)
            if (!macOk(initiation, localPublic)) return null
            val remoteEphemeral = initiation.copyOfRange(8, 40)
            val staticBox = initiation.copyOfRange(40, 88)
            val timeBox = initiation.copyOfRange(88, 116)
            var hash = mix(INITIAL_HASH, localPublic)
            hash = mix(hash, remoteEphemeral)
            var chain = kdf1(INITIAL_CHAIN, remoteEphemeral)
            val first = kdf2(chain, WgKeys.shared(localPrivate, remoteEphemeral))
            chain = first.first
            val remoteStatic = open(first.second, ZERO_NONCE, staticBox, hash) ?: return null
            if (remoteStatic.size != 32) return null
            hash = mix(hash, staticBox)
            val second = kdf2(chain, WgKeys.shared(localPrivate, remoteStatic))
            chain = second.first
            if (open(second.second, ZERO_NONCE, timeBox, hash) == null) return null
            hash = mix(hash, timeBox)
            val ephemeral = WgKeys.generate()
            hash = mix(hash, ephemeral.second)
            chain = kdf1(chain, ephemeral.second)
            chain = kdf1(chain, WgKeys.shared(ephemeral.first, remoteEphemeral))
            chain = kdf1(chain, WgKeys.shared(ephemeral.first, remoteStatic))
            val mixed = kdf3(chain, preshared)
            hash = mix(hash, mixed.second)
            val empty = seal(mixed.third, ZERO_NONCE, ByteArray(0), hash)
            hash = mix(hash, empty)
            val keys = kdf2(mixed.first, ByteArray(0))
            val localIndex = randomIndex()
            val message = ByteArray(92)
            put32(message, 0, 2)
            put32(message, 4, localIndex)
            put32(message, 8, read32(initiation, 4))
            ephemeral.second.copyInto(message, 12)
            empty.copyInto(message, 44)
            mac(message, remoteStatic)
            val handshake = WgHandshake(localPrivate, remoteStatic, preshared)
            handshake.localIndex = localIndex
            handshake.remoteIndex = read32(initiation, 4)
            handshake.sendKey = keys.second
            handshake.recvKey = keys.first
            handshake.ready = true
            handshake.packet = message
            handshake.hash = hash
            return handshake
        }

        private fun mac(message: ByteArray, remotePublic: ByteArray) {
            val mac1Key = mix(LABEL_MAC1, remotePublic)
            val mac1At = message.size - 32
            blakeKeyed(mac1Key, message.copyOf(mac1At), 16).copyInto(message, mac1At)
        }

        private fun macOk(message: ByteArray, publicKey: ByteArray): Boolean {
            val mac1Key = mix(LABEL_MAC1, publicKey)
            val mac1At = message.size - 32
            val expected = blakeKeyed(mac1Key, message.copyOf(mac1At), 16)
            return MessageDigest.isEqual(expected, message.copyOfRange(mac1At, mac1At + 16))
        }

        private fun randomIndex(): Int {
            val value = random.nextInt()
            return if (value == 0) 1 else value
        }
    }
}

private class ReplayWindow {
    private var latest = -1L
    private val bits = java.util.BitSet(2048)

    fun reset() {
        latest = -1L
        bits.clear()
    }

    fun accept(counter: Long): Boolean {
        if (counter < 0L) return false
        if (latest < 0L || counter > latest) {
            val delta = if (latest < 0L) 0 else (counter - latest).toInt()
            if (delta in 1 until 2048) {
                for (index in 2047 downTo delta) {
                    if (bits.get(index - delta)) bits.set(index) else bits.clear(index)
                }
                for (index in 0 until delta) bits.clear(index)
            } else if (delta >= 2048) {
                bits.clear()
            }
            latest = counter
            bits.set(0)
            return true
        }
        val behind = (latest - counter).toInt()
        if (behind >= 2048 || bits.get(behind)) return false
        bits.set(behind)
        return true
    }
}

private fun mix(left: ByteArray, right: ByteArray): ByteArray {
    val digest = Blake2sDigest(32)
    digest.update(left, 0, left.size)
    digest.update(right, 0, right.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}

private fun blake(data: ByteArray): ByteArray {
    val digest = Blake2sDigest(32)
    digest.update(data, 0, data.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}

private fun blakeKeyed(key: ByteArray, data: ByteArray, size: Int): ByteArray {
    val digest = Blake2sDigest(key, size, null, null)
    digest.update(data, 0, data.size)
    val out = ByteArray(size)
    digest.doFinal(out, 0)
    return out
}

private fun kdf1(key: ByteArray, input: ByteArray): ByteArray {
    val secret = blakeKeyed(key, input, 32)
    return blakeKeyed(secret, byteArrayOf(1), 32)
}

private fun kdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
    val secret = blakeKeyed(key, input, 32)
    val first = blakeKeyed(secret, byteArrayOf(1), 32)
    val second = blakeKeyed(secret, first + byteArrayOf(2), 32)
    return first to second
}

private fun kdf3(key: ByteArray, input: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
    val secret = blakeKeyed(key, input, 32)
    val first = blakeKeyed(secret, byteArrayOf(1), 32)
    val second = blakeKeyed(secret, first + byteArrayOf(2), 32)
    val third = blakeKeyed(secret, second + byteArrayOf(3), 32)
    return Triple(first, second, third)
}

private fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, extra: ByteArray): ByteArray {
    val cipher = ChaCha20Poly1305()
    cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, extra))
    val out = ByteArray(cipher.getOutputSize(plain.size))
    var written = cipher.processBytes(plain, 0, plain.size, out, 0)
    written += cipher.doFinal(out, written)
    return out.copyOf(written)
}

private fun open(key: ByteArray, nonce: ByteArray, boxed: ByteArray, extra: ByteArray): ByteArray? {
    return try {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, extra))
        val out = ByteArray(cipher.getOutputSize(boxed.size))
        var written = cipher.processBytes(boxed, 0, boxed.size, out, 0)
        written += cipher.doFinal(out, written)
        out.copyOf(written)
    } catch (_: Exception) {
        null
    }
}

private fun xopen(key: ByteArray, nonce: ByteArray, boxed: ByteArray, extra: ByteArray): ByteArray? {
    val subkey = hchacha(key, nonce.copyOfRange(0, 16))
    val ietf = ByteArray(12)
    nonce.copyInto(ietf, 4, 16, 24)
    return open(subkey, ietf, boxed, extra)
}

private fun hchacha(key: ByteArray, nonce: ByteArray): ByteArray {
    val state = IntArray(16)
    state[0] = 0x61707865
    state[1] = 0x3320646e
    state[2] = 0x79622d32
    state[3] = 0x6b206574
    for (index in 0 until 8) state[4 + index] = read32(key, index * 4)
    for (index in 0 until 4) state[12 + index] = read32(nonce, index * 4)
    repeat(10) {
        quarter(state, 0, 4, 8, 12)
        quarter(state, 1, 5, 9, 13)
        quarter(state, 2, 6, 10, 14)
        quarter(state, 3, 7, 11, 15)
        quarter(state, 0, 5, 10, 15)
        quarter(state, 1, 6, 11, 12)
        quarter(state, 2, 7, 8, 13)
        quarter(state, 3, 4, 9, 14)
    }
    val out = ByteArray(32)
    val words = intArrayOf(0, 1, 2, 3, 12, 13, 14, 15)
    for (index in words.indices) put32(out, index * 4, state[words[index]])
    return out
}

private fun quarter(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
    state[a] += state[b]
    state[d] = rotl(state[d] xor state[a], 16)
    state[c] += state[d]
    state[b] = rotl(state[b] xor state[c], 12)
    state[a] += state[b]
    state[d] = rotl(state[d] xor state[a], 8)
    state[c] += state[d]
    state[b] = rotl(state[b] xor state[c], 7)
}

private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

private fun tai64n(): ByteArray {
    val now = System.currentTimeMillis()
    val seconds = now / 1000L + (1L shl 62)
    val nanos = ((now % 1000L) * 1_000_000L).toInt()
    val out = ByteArray(12)
    for (index in 0 until 8) out[index] = ((seconds ushr ((7 - index) * 8)) and 0xff).toByte()
    put32be(out, 8, nanos)
    return out
}

private fun put32(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
}

private fun put32be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun put64(target: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) target[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
}

private fun read32(source: ByteArray, offset: Int): Int {
    return (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
}

private fun read64(source: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) value = value or ((source[offset + index].toLong() and 0xff) shl (index * 8))
    return value
}

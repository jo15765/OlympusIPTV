package com.olympus.iptv.vpn

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bouncycastle.util.encoders.Base64

internal class ProtonProof(
    val clientEphemeral: String,
    val clientProof: String,
    val expectedServerProof: ByteArray
)

internal fun protonProof(
    version: Int,
    password: String,
    saltB64: String,
    signedModulus: String,
    serverEphemeralB64: String
): ProtonProof {
    if (version != 3 && version != 4) throw VpnAuthException("This Proton account uses a sign-in this app cannot complete.")
    val modulusBytes = Base64.decode(clearSigned(signedModulus).replace("\\s".toRegex(), ""))
    val modulus = toInt(modulusBytes)
    val serverEphemeral = Base64.decode(serverEphemeralB64)
    val serverInt = toInt(serverEphemeral)
    checkModulus(modulus, serverInt)
    val hashed = expandHash(protonBcrypt(password, Base64.decode(saltB64)).toByteArray(Charsets.US_ASCII) + modulusBytes)
    val size = 256
    val limit = modulus.subtract(BigInteger.ONE)
    val lower = BigInteger.valueOf((2048 * 2).toLong())
    val random = SecureRandom()
    val multiplier = toInt(expandHash(fromInt(size, BigInteger.TWO) + fromInt(size, modulus))).mod(modulus)
    if (multiplier <= BigInteger.ONE || multiplier >= limit) throw VpnAuthException("Proton could not start the sign-in.")
    var secret: BigInteger
    var ephemeral: ByteArray
    var scramble: BigInteger
    while (true) {
        secret = BigInteger(2048, random)
        if (secret <= lower || secret >= limit) continue
        ephemeral = fromInt(size, BigInteger.TWO.modPow(secret, modulus))
        scramble = toInt(expandHash(ephemeral + serverEphemeral))
        if (scramble.signum() != 0) break
    }
    val passwordInt = toInt(hashed)
    val powered = BigInteger.TWO.modPow(passwordInt, modulus)
    val base = serverInt.subtract(multiplier.multiply(powered)).mod(modulus)
    val exponent = scramble.multiply(passwordInt).add(secret).mod(limit)
    val session = fromInt(size, base.modPow(exponent, modulus))
    val proof = expandHash(ephemeral + serverEphemeral + session)
    val serverProof = expandHash(ephemeral + proof + session)
    return ProtonProof(Base64.toBase64String(ephemeral), Base64.toBase64String(proof), serverProof)
}

internal fun protonBcrypt(password: String, salt: ByteArray): String {
    val encoded = bcryptEncode(salt + "proton".toByteArray(Charsets.US_ASCII))
    val saltText = encoded.substring(0, 22)
    val raw = bcryptDecode(saltText).copyOf(16)
    val hashed = OpenBSDBCrypt.generate(password.toCharArray(), raw, 10)
    return "$" + "2y$" + "10$" + saltText + hashed.takeLast(31)
}

internal fun bcryptForTest(password: String, saltText: String, cost: Int): String {
    val marker = "$" + "2a$" + cost.toString().padStart(2, '0') + "$"
    val salt22 = saltText.substringAfter(marker).take(22)
    val raw = bcryptDecode(salt22).copyOf(16)
    return OpenBSDBCrypt.generate(password.toCharArray(), raw, cost)
}

private fun clearSigned(text: String): String {
    val signature = text.indexOf("-----BEGIN PGP SIGNATURE-----")
    val head = if (signature >= 0) text.substring(0, signature) else text
    val split = head.indexOf("\n\n")
    val body = if (split >= 0) head.substring(split + 2) else head
    return body.lineSequence().joinToString("\n") { line ->
        if (line.startsWith("- ")) line.substring(2) else line
    }.trim()
}

private fun expandHash(data: ByteArray): ByteArray {
    val out = ByteArray(256)
    val digest = MessageDigest.getInstance("SHA-512")
    for (index in 0 until 4) {
        digest.update(data)
        digest.update(index.toByte())
        digest.digest().copyInto(out, index * 64)
    }
    return out
}

private fun checkModulus(modulus: BigInteger, ephemeral: BigInteger) {
    val limit = modulus.subtract(BigInteger.ONE)
    if (modulus.bitLength() != 2048 || !modulus.testBit(0) || !modulus.testBit(1) || modulus.testBit(2)) {
        throw VpnAuthException("Proton could not start the sign-in.")
    }
    if (ephemeral <= BigInteger.ONE || ephemeral >= limit) throw VpnAuthException("Proton could not start the sign-in.")
    val half = modulus.shiftRight(1)
    if (!half.isProbablePrime(10)) throw VpnAuthException("Proton could not start the sign-in.")
    if (BigInteger.TWO.modPow(half, modulus) != limit) throw VpnAuthException("Proton could not start the sign-in.")
}

private fun toInt(little: ByteArray): BigInteger = BigInteger(1, little.reversedArray())

private fun fromInt(size: Int, value: BigInteger): ByteArray {
    val raw = value.toByteArray()
    val magnitude = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    val big = ByteArray(size)
    val length = magnitude.size.coerceAtMost(size)
    magnitude.copyInto(big, size - length, magnitude.size - length, magnitude.size)
    return big.reversedArray()
}

private const val BCRYPT_ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun bcryptEncode(data: ByteArray): String {
    val standard = Base64.toBase64String(data).trimEnd('=')
    return buildString(standard.length) {
        for (symbol in standard) append(BCRYPT_ALPHABET[STANDARD_ALPHABET.indexOf(symbol)])
    }
}

private fun bcryptDecode(text: String): ByteArray {
    val padded = text + "=".repeat((4 - text.length % 4) % 4)
    val standard = buildString(padded.length) {
        for (symbol in padded) {
            val index = BCRYPT_ALPHABET.indexOf(symbol)
            append(if (index >= 0) STANDARD_ALPHABET[index] else symbol)
        }
    }
    return Base64.decode(standard)
}

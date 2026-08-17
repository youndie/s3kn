package io.github.youndie.s3.sigv4

import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Lower-case hex SHA-256, the form every payload hash and the string to sign are written in.
 *
 * Public because a caller with the body in memory needs it to fill `x-amz-content-sha256`; a
 * caller streaming the body uses `UNSIGNED-PAYLOAD` instead and never calls this.
 */
public fun sha256Hex(data: ByteArray): String = SHA256().digest(data).toHex()

/** The hash of an empty body, which every request without one carries. */
public const val EMPTY_PAYLOAD_SHA256: String =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

internal fun hmacSha256(
    key: ByteArray,
    message: String,
): ByteArray = HmacSHA256(key).doFinal(message.encodeToByteArray())

internal fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            val code = byte.toInt() and 0xFF
            append(HEX[code shr 4])
            append(HEX[code and 0xF])
        }
    }

private const val HEX = "0123456789abcdef"

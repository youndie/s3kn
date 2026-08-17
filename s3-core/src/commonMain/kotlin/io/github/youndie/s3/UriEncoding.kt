package io.github.youndie.s3

/**
 * Percent-encodes an object key for use as a URL path.
 *
 * Slashes survive: in a key they separate path segments. Nothing else about the key is touched —
 * no normalisation of `.` and `..`, no collapsing of repeated slashes. That is not an omission:
 * S3 signs the path exactly as it appears on the wire, unlike every other AWS service
 * (docs/spec/reference/botocore-auth.py:538).
 *
 * The same function has to produce the path that goes into the canonical request and the path that
 * goes into the URL. Two encoders that differ by one character produce `SignatureDoesNotMatch`,
 * and the response says nothing about which character it was.
 */
public fun uriEncodeKey(key: String): String = uriEncode(key, encodeSlash = false)

/**
 * Percent-encodes a name or a value of a query parameter.
 *
 * Here a slash is data rather than a separator, so it becomes `%2F`
 * (docs/spec/reference/botocore-auth.py:268).
 */
public fun uriEncodeQueryComponent(value: String): String = uriEncode(value, encodeSlash = true)

/**
 * `UriEncode` as AWS defines it: everything outside `A-Za-z0-9-_.~` becomes `%XX` over the UTF-8
 * bytes, with upper-case hex digits. A space is `%20` and never `+`.
 */
private fun uriEncode(
    value: String,
    encodeSlash: Boolean,
): String =
    buildString(value.length) {
        // Encoding runs over UTF-8 bytes, not over Char values: a Char is half of a surrogate pair
        // for anything outside the basic plane, and half a pair has no encoding of its own.
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val character = code.toChar()
            when {
                code < 0x80 && character in UNRESERVED -> {
                    append(character)
                }

                character == '/' && !encodeSlash -> {
                    append(character)
                }

                else -> {
                    append('%')
                    append(HEX[code shr 4])
                    append(HEX[code and 0xF])
                }
            }
        }
    }

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

private const val HEX = "0123456789ABCDEF"

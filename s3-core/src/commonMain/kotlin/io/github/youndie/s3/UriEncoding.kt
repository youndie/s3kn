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
 * Reverses [uriEncodeKey] and [uriEncodeQueryComponent].
 *
 * Needed because a listing sends `encoding-type=url`, so every key S3 returns arrives encoded
 * (docs/api/protocol-s3.md, section 4.5). Decoding is done over bytes and only then turned into
 * text: one code point can arrive as four separate `%XX` groups, and decoding them one at a time
 * yields replacement characters.
 *
 * @param plusIsSpace how to read a `+`.
 *
 * URI encoding has no special meaning for `+`; form encoding reads it as a space. Both appear here,
 * because **a listing response uses the form reading**: servers write a space in a key as `+`, and
 * the reference implementation decodes those responses with `unquote_plus`
 * (`botocore/compat.py:62`, `unquote_str = unquote_plus`). Verified against a live server, which
 * returns `my+dir` for a key containing `my dir`.
 *
 * It is unambiguous either way: a literal `+` in a key is not an unreserved character, so it
 * arrives as `%2B`.
 *
 * @throws IllegalArgumentException on a `%` that is not followed by two hex digits. Passing a
 *   broken escape through would return a key that looks plausible and is wrong.
 */
public fun uriDecode(
    value: String,
    plusIsSpace: Boolean = false,
): String {
    if ('%' !in value && !(plusIsSpace && '+' in value)) return value

    // An upper bound: an unencoded non-ASCII character is several bytes, and every `%XX` group
    // shrinks three bytes into one. Sizing this by the character count would overflow on the first
    // key that mixes the two.
    val bytes = ByteArray(value.encodeToByteArray().size)
    var length = 0
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '+' && plusIsSpace) {
            bytes[length++] = ' '.code.toByte()
            index++
            continue
        }
        if (character != '%') {
            for (byte in character.toString().encodeToByteArray()) {
                bytes[length++] = byte
            }
            index++
            continue
        }

        require(index + 2 < value.length) { "Truncated percent-escape at index $index in \"$value\"" }
        val code = value.substring(index + 1, index + 3).toIntOrNull(radix = 16)
        requireNotNull(code) { "Invalid percent-escape \"${value.substring(index, index + 3)}\" in \"$value\"" }
        bytes[length++] = code.toByte()
        index += 3
    }
    return bytes.decodeToString(endIndex = length)
}

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

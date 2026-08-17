package io.github.youndie.s3.testing

internal class ParsedHttpRequest(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: String,
)

/**
 * Parses a raw HTTP request the way the test vectors write it.
 *
 * Deliberately not a general HTTP parser — a general one is what makes SDKs skip cases:
 *
 * - the request target may contain a raw space (`GET /example space/ HTTP/1.1`), so the method is
 *   taken up to the *first* space and the version from the *last* one;
 * - a header value may be folded onto following lines, indented — the obsolete syntax, which this
 *   suite still exercises;
 * - repeated header names and repeated query names are both kept, in order. Parsing either into a
 *   map is what loses `get-vanilla-query-order-value` and `get-header-value-order`.
 *
 * Whitespace inside a header value is left alone: collapsing it is the canonical form's job, and
 * doing it here would make `get-header-value-trim` prove nothing.
 */
internal fun parseHttpRequest(text: String): ParsedHttpRequest {
    val (head, body) = text.split("\n\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val lines = head.split("\n")

    val requestLine = lines.first()
    val method = requestLine.substringBefore(' ')
    val target = requestLine.substring(method.length + 1, requestLine.lastIndexOf(' '))

    return ParsedHttpRequest(
        method = method,
        path = target.substringBefore('?'),
        query = parseQuery(target.substringAfter('?', missingDelimiterValue = "")),
        headers = parseHeaders(lines.drop(1)),
        body = body,
    )
}

private fun parseHeaders(lines: List<String>): List<Pair<String, String>> {
    val headers = mutableListOf<Pair<String, String>>()
    for (line in lines) {
        if (line.isEmpty()) continue
        if (line[0] == ' ' || line[0] == '\t') {
            // A continuation of the previous value. Joined with a single space; the canonical form
            // would collapse any run of whitespace to one anyway.
            val (name, value) = headers.removeLast()
            headers += name to "$value ${line.trim()}"
        } else {
            headers += line.substringBefore(':') to line.substringAfter(':')
        }
    }
    return headers
}

private fun parseQuery(rawQuery: String): List<Pair<String, String>> {
    if (rawQuery.isEmpty()) return emptyList()
    return rawQuery.split("&").map { pair ->
        percentDecode(pair.substringBefore('=')) to
            percentDecode(pair.substringAfter('=', missingDelimiterValue = ""))
    }
}

/**
 * Percent-decoding per RFC 3986. `+` stays a plus: that is form encoding, not URI encoding, and no
 * case in the suite relies on the form reading.
 */
private fun percentDecode(text: String): String {
    if ('%' !in text) return text

    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '%' && index + 2 < text.length + 1) {
            val code = text.substring(index + 1, index + 3).toInt(radix = 16)
            bytes += code.toByte()
            index += 3
        } else {
            for (byte in character.toString().encodeToByteArray()) {
                bytes += byte
            }
            index += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}

package io.github.youndie.s3

/** The fields of an `<Error>` document that are worth carrying into an exception. */
internal class ParsedError(
    val code: String?,
    val message: String?,
    val requestId: String?,
    val hostId: String?,
    /**
     * `<CanonicalRequest>`, sent by S3 on `SignatureDoesNotMatch`.
     *
     * The single most useful thing in a signing failure: comparing it line by line with the one
     * this library built names the disagreeing line straight away
     * (docs/research/research-architecture.md, risk 4).
     */
    val canonicalRequest: String?,
)

/**
 * Pulls the fields out of an S3 error document.
 *
 * A hand-rolled reader rather than an XML library: the document is flat, its elements never repeat,
 * and this runs on a failure path where a parser that can itself throw would replace one error with
 * another. Anything it does not recognise it ignores, and a body that is not XML at all — a proxy's
 * HTML error page, an empty response — yields `null` rather than an exception
 * (docs/research/research-architecture.md, decision R8).
 */
internal fun parseErrorBody(body: String): ParsedError? {
    if ("<Error" !in body) return null

    return ParsedError(
        code = body.elementText("Code"),
        message = body.elementText("Message"),
        requestId = body.elementText("RequestId"),
        hostId = body.elementText("HostId"),
        canonicalRequest = body.elementText("CanonicalRequest"),
    )
}

/**
 * Text of the first `<name>…</name>`, with the five XML entities resolved.
 *
 * `CanonicalRequest` matters here: S3 sends it with its newlines escaped as `&#10;`, so a reader
 * that leaves entities alone produces one long line that cannot be compared with anything.
 */
private fun String.elementText(name: String): String? {
    val open = indexOf("<$name>")
    if (open < 0) return null
    val start = open + name.length + 2
    val end = indexOf("</$name>", startIndex = start)
    if (end < 0) return null

    return substring(start, end)
        .replace("&#10;", "\n")
        .replace("&#xA;", "\n")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Last, so that an ampersand inside another entity is not consumed early.
        .replace("&amp;", "&")
}

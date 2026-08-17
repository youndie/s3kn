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
 * Anything it does not recognise it ignores, and a body that is not an error document at all — a
 * proxy's HTML page, an empty response — yields `null` rather than an exception. This runs where
 * something has already gone wrong, and a second failure here would hide the first.
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

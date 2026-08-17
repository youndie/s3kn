package io.github.youndie.s3

/**
 * A request that S3 refused.
 *
 * [code] and [errorMessage] are nullable on purpose. A `HEAD` response has no body at all, so a
 * missing key arrives as a bare `404` with nothing to parse (docs/api/protocol-s3.md section 4.4);
 * some S3-compatible servers answer with an empty body on other methods too. A model that requires
 * a code turns those into a parse failure and hides what actually happened.
 *
 * [requestId] and [extendedRequestId] are carried whether or not anything else could be read: AWS
 * support will not look at a report without them.
 */
public class S3Exception(
    /** HTTP status of the response. */
    public val status: Int,
    /** S3 error code such as `NoSuchKey`, or `null` when the response carried no body. */
    public val code: String? = null,
    /** Human-readable text from the `<Message>` element, or `null` when there was no body. */
    public val errorMessage: String? = null,
    /** `x-amz-request-id`. */
    public val requestId: String? = null,
    /** `x-amz-id-2`. */
    public val extendedRequestId: String? = null,
    /**
     * The canonical request this library built and signed.
     *
     * Kept so that a `SignatureDoesNotMatch` can be diagnosed: S3 returns the canonical request it
     * built in [serverCanonicalRequest], and comparing the two line by line names the disagreement
     * immediately. Without them the response says only that the signature is wrong
     * (docs/research/research-architecture.md, risk 4).
     */
    public val sentCanonicalRequest: String? = null,
    /** The canonical request the server built, when it told us. */
    public val serverCanonicalRequest: String? = null,
    cause: Throwable? = null,
) : RuntimeException(describe(status, code, errorMessage, requestId, extendedRequestId), cause) {
    init {
        require(status in MIN_STATUS..MAX_STATUS) { "Not an HTTP status code: $status" }
    }

    private companion object {
        const val MIN_STATUS = 100
        const val MAX_STATUS = 599

        const val ABSENT = "absent"

        fun describe(
            status: Int,
            code: String?,
            errorMessage: String?,
            requestId: String?,
            extendedRequestId: String?,
        ): String =
            buildString {
                append("S3 request failed with ")
                append(status)
                if (code == null) {
                    append(" and no error body")
                } else {
                    append(" ")
                    append(code)
                }
                if (errorMessage != null) {
                    append(": ")
                    append(errorMessage)
                }
                append(" (x-amz-request-id: ")
                append(requestId ?: ABSENT)
                append(", x-amz-id-2: ")
                append(extendedRequestId ?: ABSENT)
                append(")")
            }
    }
}

/**
 * The error codes this library reacts to by name.
 *
 * Constants rather than an enum: S3-compatible servers invent codes of their own, and an enum
 * would force every unknown one through a fallback that loses the original string.
 *
 * The list is the one in docs/api/protocol-s3.md section 5; the full set lives in
 * docs/spec/s3-service-2.json and is deliberately not copied here.
 */
public object S3ErrorCode {
    public const val NO_SUCH_KEY: String = "NoSuchKey"
    public const val NO_SUCH_BUCKET: String = "NoSuchBucket"
    public const val NO_SUCH_UPLOAD: String = "NoSuchUpload"
    public const val ACCESS_DENIED: String = "AccessDenied"
    public const val SIGNATURE_DOES_NOT_MATCH: String = "SignatureDoesNotMatch"
    public const val ENTITY_TOO_SMALL: String = "EntityTooSmall"
    public const val INVALID_PART: String = "InvalidPart"
    public const val INVALID_PART_ORDER: String = "InvalidPartOrder"
    public const val MISSING_CONTENT_LENGTH: String = "MissingContentLength"
    public const val SLOW_DOWN: String = "SlowDown"
    public const val INTERNAL_ERROR: String = "InternalError"
}

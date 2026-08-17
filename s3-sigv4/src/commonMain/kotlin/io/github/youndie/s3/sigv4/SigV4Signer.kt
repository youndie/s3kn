package io.github.youndie.s3.sigv4

import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.SigningTimestamp
import io.github.youndie.s3.canonicalQueryString

/** What is being signed. Everything here goes into the signature; nothing else does. */
public class SigningRequest(
    /** Request method, upper-case. */
    public val method: String,
    /**
     * Request path.
     *
     * In [PathMode.VERBATIM] it must already be percent-encoded, because it is signed as it stands
     * and then sent as it stands.
     */
    public val path: String,
    /** Query parameters, decoded, in any order; repeated names are allowed. */
    public val query: List<Pair<String, String>> = emptyList(),
    /**
     * Headers to sign, decoded, in the order sent; repeated names are allowed.
     *
     * Whatever is here is signed. The signer applies no blacklist of its own: the two AWS
     * reference implementations disagree about which headers are unsignable, so the caller decides
     * by passing only the headers it set itself (docs/research/research-architecture.md,
     * consequence 1.3.2).
     */
    public val headers: List<Pair<String, String>> = emptyList(),
    /**
     * Value of `x-amz-content-sha256`: a hex SHA-256, [EMPTY_PAYLOAD_SHA256], or `UNSIGNED-PAYLOAD`.
     */
    public val payloadHash: String,
)

/** The canonical request, and the signed-header list that the `Authorization` header repeats. */
public class CanonicalRequest internal constructor(
    /** The seven-line canonical request, exactly as it is hashed. */
    public val text: String,
    /** `host;x-amz-content-sha256;x-amz-date` — the signed names, lower-case and sorted. */
    public val signedHeaders: String,
)

/** Everything the signing produced. The intermediate values are kept so a failure can be read. */
public class SignedRequest internal constructor(
    public val canonicalRequest: CanonicalRequest,
    public val stringToSign: String,
    /** Lower-case hex signature. */
    public val signature: String,
    /** The complete `Authorization` header value. */
    public val authorization: String,
    /** The headers as signed: what was given, plus what the signer added. */
    public val headers: List<Pair<String, String>>,
)

/**
 * Whether the signer fills in the headers it owns, or signs exactly what it was handed.
 *
 * Header authentication and query authentication put the same facts in different places: with a
 * presigned URL the date and the session token travel as query parameters, so signing them as
 * headers too would produce a signature over headers the browser will never send.
 */
public enum class HeaderPolicy {
    /** The signer writes `x-amz-date` and `x-amz-security-token`, replacing any it was given. */
    MANAGED,

    /** The signer signs exactly the headers given, and adds none. */
    AS_GIVEN,
}

/**
 * Signature Version 4, header-based.
 *
 * The algorithm is in docs/spec/reference/botocore-auth.py — canonical request at `:370`, string to
 * sign at `:405`, key derivation at `:417` — and is checked against the official vectors in
 * `SigV4VectorTest`.
 *
 * The signer owns `x-amz-date` and `x-amz-security-token`: it writes both from the timestamp and
 * the credentials it was handed, replacing anything the caller put there. A caller keeping its own
 * copy of the timestamp is exactly how a request comes to name one moment in its header and another
 * in its signature.
 */
public class SigV4Signer(
    public val region: String,
    /** `s3` for S3. The vectors use `service`. */
    public val service: String,
    public val pathMode: PathMode,
) {
    public fun sign(
        request: SigningRequest,
        credentials: S3Credentials,
        timestamp: SigningTimestamp,
        headerPolicy: HeaderPolicy = HeaderPolicy.MANAGED,
    ): SignedRequest {
        val headers =
            when (headerPolicy) {
                HeaderPolicy.MANAGED -> headersToSign(request.headers, credentials, timestamp)
                HeaderPolicy.AS_GIVEN -> request.headers
            }
        val canonical = canonicalRequest(request, headers)
        val stringToSign = stringToSign(canonical, timestamp)
        val signature = signature(credentials.secretAccessKey, timestamp, stringToSign)

        return SignedRequest(
            canonicalRequest = canonical,
            stringToSign = stringToSign,
            signature = signature,
            authorization = authorization(credentials.accessKeyId, timestamp, canonical, signature),
            headers = headers,
        )
    }

    private fun headersToSign(
        given: List<Pair<String, String>>,
        credentials: S3Credentials,
        timestamp: SigningTimestamp,
    ): List<Pair<String, String>> =
        buildList {
            given.filterNotTo(this) { (name, _) -> name.lowercase() in OWNED_HEADERS }
            add(DATE_HEADER to timestamp.amzDate)
            credentials.sessionToken?.let { add(TOKEN_HEADER to it) }
        }

    private fun canonicalRequest(
        request: SigningRequest,
        headers: List<Pair<String, String>>,
    ): CanonicalRequest {
        val sortedNames = headers.map { (name, _) -> name.lowercase() }.distinct().sorted()
        val canonicalHeaders =
            sortedNames.joinToString("\n") { name ->
                val values =
                    headers
                        .filter { (given, _) -> given.lowercase() == name }
                        .joinToString(",") { (_, value) -> trimAll(value) }
                "$name:$values"
            }
        val signedHeaders = sortedNames.joinToString(";")

        return CanonicalRequest(
            text =
                listOf(
                    request.method.uppercase(),
                    canonicalPath(request.path, pathMode),
                    canonicalQueryString(request.query),
                    canonicalHeaders,
                    "",
                    signedHeaders,
                    request.payloadHash,
                ).joinToString("\n"),
            signedHeaders = signedHeaders,
        )
    }

    private fun stringToSign(
        canonical: CanonicalRequest,
        timestamp: SigningTimestamp,
    ): String =
        listOf(
            ALGORITHM,
            timestamp.amzDate,
            credentialScope(timestamp),
            sha256Hex(canonical.text.encodeToByteArray()),
        ).joinToString("\n")

    private fun signature(
        secretAccessKey: String,
        timestamp: SigningTimestamp,
        stringToSign: String,
    ): String {
        val date = hmacSha256("AWS4$secretAccessKey".encodeToByteArray(), timestamp.scopeDate)
        val regional = hmacSha256(date, region)
        val serviced = hmacSha256(regional, service)
        val signing = hmacSha256(serviced, TERMINATOR)
        return hmacSha256(signing, stringToSign).toHex()
    }

    private fun authorization(
        accessKeyId: String,
        timestamp: SigningTimestamp,
        canonical: CanonicalRequest,
        signature: String,
    ): String =
        "$ALGORITHM Credential=$accessKeyId/${credentialScope(timestamp)}, " +
            "SignedHeaders=${canonical.signedHeaders}, " +
            "Signature=$signature"

    private fun credentialScope(timestamp: SigningTimestamp): String =
        "${timestamp.scopeDate}/$region/$service/$TERMINATOR"

    /**
     * `Trimall`: drop the whitespace around the value and collapse every run inside it to one
     * space (docs/spec/reference/botocore-auth.py:317).
     *
     * The runs collapse inside quotes as well — the suite's `get-header-value-trim` turns
     * `"a   b   c"` into `"a b c"`, which is not what a quoted string would do in RFC 9110.
     */
    private fun trimAll(value: String): String =
        value
            .split(' ', '\t', '\n', '\r')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val TERMINATOR = "aws4_request"
        const val DATE_HEADER = "X-Amz-Date"
        const val TOKEN_HEADER = "X-Amz-Security-Token"

        /** Headers the signer writes itself, so a caller's copy is dropped rather than duplicated. */
        val OWNED_HEADERS = setOf("x-amz-date", "x-amz-security-token")
    }
}

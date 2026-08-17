package io.github.youndie.s3.sigv4

import io.github.youndie.s3.S3Config
import io.github.youndie.s3.SigningTimestamp
import io.github.youndie.s3.canonicalQueryString
import io.github.youndie.s3.toSigningTimestamp
import io.github.youndie.s3.uriEncodeQueryComponent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * What the request body is, from the signature's point of view.
 *
 * The three cases are not three ways of saying the same thing: they decide what goes into
 * `x-amz-content-sha256`, and one of them is refused over plain HTTP.
 */
public sealed interface S3Payload {
    /** No body. Signed as the hash of zero bytes. */
    public data object Empty : S3Payload

    /** A body already in memory. Hashed, so the signature covers it. */
    public class InMemory(
        public val bytes: ByteArray,
    ) : S3Payload

    /**
     * A body that will be streamed, and whose hash is therefore unknown when the request is signed.
     *
     * Signed as `UNSIGNED-PAYLOAD`, which S3 permits and which is the only alternative to reading
     * the whole body into memory first (docs/research/research-architecture.md, decision R6).
     * Allowed over HTTPS only.
     */
    public data object Streamed : S3Payload
}

/** One S3 request, before it is signed. */
public class S3Operation(
    public val method: String,
    public val bucket: String,
    /** Object key; empty addresses the bucket itself, which is what a listing does. */
    public val key: String = "",
    /** Query parameters, decoded. */
    public val query: List<Pair<String, String>> = emptyList(),
    /**
     * Extra headers to sign, such as `Content-Type`. `host`, `x-amz-date`,
     * `x-amz-content-sha256` and `x-amz-security-token` are written by the signer and must not
     * appear here.
     */
    public val headers: List<Pair<String, String>> = emptyList(),
    public val payload: S3Payload = S3Payload.Empty,
)

/** A signed request: where to send it, and what to send with it. */
public class SignedS3Request internal constructor(
    /** The full URL, query included. */
    public val url: String,
    /**
     * The path, already percent-encoded, exactly as it was signed.
     *
     * Handed to the HTTP client as an *encoded* path. Anything that encodes it a second time —
     * and a URL builder given a decoded path will — changes the string the server verifies the
     * signature against, and the only symptom is `SignatureDoesNotMatch`.
     */
    public val encodedPath: String,
    /** The query string, already percent-encoded and in canonical order; empty when there is none. */
    public val encodedQuery: String,
    /** Every header to send, including `Authorization`. */
    public val headers: List<Pair<String, String>>,
    /** Kept for diagnostics: on `SignatureDoesNotMatch` this is what to compare against. */
    public val canonicalRequest: CanonicalRequest,
    public val stringToSign: String,
    public val authorization: String,
)

/**
 * Signs S3 requests, and makes presigned URLs.
 *
 * A thin layer over [SigV4Signer] that fills in what is specific to S3: the service name, the
 * verbatim path mode, the `x-amz-content-sha256` header, and addressing.
 *
 * Everything here is a pure function of the configuration, the operation and the clock. It sends
 * nothing, which is why presigning works on targets that have no HTTP engine at all.
 */
public class S3Signer(
    private val config: S3Config,
) {
    private val signer = SigV4Signer(region = config.region, service = SERVICE, pathMode = PathMode.VERBATIM)

    /** Signs a request with headers, the way an ordinary API call is authenticated. */
    public fun sign(operation: S3Operation): SignedS3Request {
        val timestamp = config.clock.now().toSigningTimestamp()
        val payloadHash = contentSha256(operation.payload)
        val path = config.encodedPathFor(operation.bucket, operation.key)
        val query = canonicalQueryString(operation.query)

        val signed =
            signer.sign(
                request =
                    SigningRequest(
                        method = operation.method,
                        path = path,
                        query = operation.query,
                        headers =
                            buildList {
                                add(HOST_HEADER to config.hostHeaderFor(operation.bucket))
                                add(CONTENT_SHA256_HEADER to payloadHash)
                                addAll(operation.headers)
                            },
                        payloadHash = payloadHash,
                    ),
                credentials = config.credentials,
                timestamp = timestamp,
            )

        return SignedS3Request(
            url =
                "${config.endpoint.scheme}://${config.hostHeaderFor(operation.bucket)}$path" +
                    if (query.isEmpty()) "" else "?$query",
            encodedPath = path,
            encodedQuery = query,
            headers = signed.headers + (AUTHORIZATION_HEADER to signed.authorization),
            canonicalRequest = signed.canonicalRequest,
            stringToSign = signed.stringToSign,
            authorization = signed.authorization,
        )
    }

    /**
     * Builds a URL that carries its own authorisation, so it can be handed to something that knows
     * nothing about credentials.
     *
     * The body is unknown at this point, so it is signed as `UNSIGNED-PAYLOAD` and no content hash
     * appears anywhere (docs/spec/reference/botocore-auth.py:810). Only `host` is signed, which is
     * what makes the link usable from a plain browser.
     *
     * @param expires how long the link stays valid; at most seven days, which is the ceiling SigV4
     *   itself imposes.
     */
    public fun presign(
        method: String,
        bucket: String,
        key: String = "",
        expires: Duration = 1.hours,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        require(expires > Duration.ZERO) { "Presigned URL expiry must be positive, got $expires" }
        require(expires <= MAX_EXPIRY) {
            "Presigned URL expiry must be at most $MAX_EXPIRY (604800 seconds), got $expires"
        }

        val timestamp = config.clock.now().toSigningTimestamp()
        val host = config.hostHeaderFor(bucket)
        val path = config.encodedPathFor(bucket, key)
        val authParameters = authQueryParameters(timestamp, expires)

        val signed =
            signer.sign(
                request =
                    SigningRequest(
                        method = method,
                        path = path,
                        query = query + authParameters,
                        headers = listOf(HOST_HEADER to host),
                        payloadHash = UNSIGNED_PAYLOAD,
                    ),
                credentials = config.credentials,
                timestamp = timestamp,
                // Only `host` is signed. The date and the session token are already in the query,
                // and a browser following this link will send neither as a header.
                headerPolicy = HeaderPolicy.AS_GIVEN,
            )

        // The wire order is the operation's parameters first, then the authentication ones, then
        // the signature — the order the reference implementation writes, and the vectors compare
        // whole URLs. The canonical form sorts them; only the order differs, never the encoding.
        val wireQuery = (query + authParameters).joinToString("&") { (name, value) -> encodePair(name, value) }
        return "${config.endpoint.scheme}://$host$path?$wireQuery&$SIGNATURE_PARAMETER=${signed.signature}"
    }

    private fun authQueryParameters(
        timestamp: SigningTimestamp,
        expires: Duration,
    ): List<Pair<String, String>> =
        buildList {
            add("X-Amz-Algorithm" to "AWS4-HMAC-SHA256")
            add(
                "X-Amz-Credential" to
                    "${config.credentials.accessKeyId}/${timestamp.scopeDate}/${config.region}/$SERVICE/aws4_request",
            )
            add("X-Amz-Date" to timestamp.amzDate)
            add("X-Amz-Expires" to expires.inWholeSeconds.toString())
            add("X-Amz-SignedHeaders" to "host")
            config.credentials.sessionToken?.let { add("X-Amz-Security-Token" to it) }
        }

    /**
     * The value of `x-amz-content-sha256`.
     *
     * @throws IllegalArgumentException when a streamed body would be left unsigned over plain HTTP,
     *   where nothing else protects it.
     */
    private fun contentSha256(payload: S3Payload): String =
        when (payload) {
            S3Payload.Empty -> {
                EMPTY_PAYLOAD_SHA256
            }

            is S3Payload.InMemory -> {
                sha256Hex(payload.bytes)
            }

            S3Payload.Streamed -> {
                require(config.endpoint.isSecure) {
                    "Refusing to send a streamed body over http with an unsigned payload: without " +
                        "TLS nothing protects it in transit. Use https, or pass the body as " +
                        "S3Payload.InMemory so it can be hashed."
                }
                UNSIGNED_PAYLOAD
            }
        }

    private fun encodePair(
        name: String,
        value: String,
    ): String = "${uriEncodeQueryComponent(name)}=${uriEncodeQueryComponent(value)}"

    private companion object {
        const val SERVICE = "s3"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        const val HOST_HEADER = "Host"
        const val CONTENT_SHA256_HEADER = "X-Amz-Content-SHA256"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val SIGNATURE_PARAMETER = "X-Amz-Signature"

        /** Seven days, the ceiling SigV4 puts on a presigned URL. */
        val MAX_EXPIRY = 604_800.seconds
    }
}

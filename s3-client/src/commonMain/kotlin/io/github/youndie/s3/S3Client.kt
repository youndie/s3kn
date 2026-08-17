package io.github.youndie.s3

import io.github.youndie.s3.sigv4.S3Operation
import io.github.youndie.s3.sigv4.S3Signer
import io.github.youndie.s3.sigv4.SignedS3Request
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess

/**
 * What `HEAD` reports about an object.
 *
 * Not a copy of every header S3 can return — the full list is in `docs/spec/s3-service-2.json`,
 * `shapes.HeadObjectOutput`. These are the ones a caller acts on.
 */
public class S3ObjectMetadata(
    public val contentLength: Long?,
    /** Quoted, as it arrives: `"9a0364b9e99bb480dd25e1f0284c8555"`. */
    public val eTag: String?,
    public val lastModified: String?,
    public val contentType: String?,
    /** `x-amz-meta-*` headers, with the prefix stripped and names lower-cased. */
    public val userMetadata: Map<String, String>,
)

/**
 * An S3 client.
 *
 * The [HttpClient] comes from the caller, engine and all. That is deliberate: on Kotlin/Native the
 * only engine that speaks HTTPS is `ktor-client-curl`, on the JVM there are several, and a library
 * that picked one would be wrong on some target. It also means timeouts, retries and logging stay
 * ordinary Ktor plugins rather than a second set of settings here.
 *
 * The client does not own the [HttpClient] and does not close it.
 */
public class S3Client(
    public val config: S3Config,
    private val http: HttpClient,
) {
    private val signer = S3Signer(config)

    /**
     * Reads an object's metadata without its body.
     *
     * @throws S3Exception when the object or the bucket is not there, or the request is refused.
     *   A `HEAD` response has no body at all, so such an exception carries a status and the request
     *   identifiers but no S3 error code — there is none to read
     *   (docs/api/protocol-s3.md, section 4.4).
     */
    public suspend fun head(
        bucket: String,
        key: String,
    ): S3ObjectMetadata {
        val response = execute(S3Operation(method = "HEAD", bucket = bucket, key = key))
        return S3ObjectMetadata(
            contentLength = response.headers["Content-Length"]?.toLongOrNull(),
            eTag = response.headers["ETag"],
            lastModified = response.headers["Last-Modified"],
            contentType = response.headers["Content-Type"],
            userMetadata =
                response.headers
                    .entries()
                    .filter { it.key.startsWith(USER_METADATA_PREFIX, ignoreCase = true) }
                    .associate {
                        it.key.lowercase().removePrefix(USER_METADATA_PREFIX) to it.value.joinToString(",")
                    },
        )
    }

    /**
     * Signs the operation, sends it, and turns anything that is not a success into [S3Exception].
     *
     * The URL handed to Ktor is the one the signature was computed over, string for string. Ktor
     * parses it without decoding — `URLParser` fills `encodedParameters` with `decode = false` and
     * keeps the path segments as they are — so nothing between here and the socket re-encodes it.
     * Anything that did would break every key containing a space, a plus or a non-ASCII character,
     * and would break it as `SignatureDoesNotMatch`, which names no encoding
     * (docs/research/research-architecture.md, decision R4).
     */
    private suspend fun execute(operation: S3Operation): HttpResponse {
        val signed = signer.sign(operation)
        val response =
            http.request(signed.url) {
                method = HttpMethod.parse(operation.method)
                signed.headers.forEach { (name, value) -> header(name, value) }
            }

        if (!response.status.isSuccess()) {
            throw errorFrom(response, operation, signed)
        }
        return response
    }

    private suspend fun errorFrom(
        response: HttpResponse,
        operation: S3Operation,
        signed: SignedS3Request,
    ): S3Exception {
        // A HEAD response has no body by definition. Asking for one anyway is not an error, but
        // there is nothing to gain and a stalled read to lose.
        val error = if (operation.method == "HEAD") null else parseErrorBody(response.bodyAsText())

        return S3Exception(
            status = response.status.value,
            code = error?.code,
            errorMessage = error?.message,
            requestId = error?.requestId ?: response.headers["x-amz-request-id"],
            extendedRequestId = error?.hostId ?: response.headers["x-amz-id-2"],
            sentCanonicalRequest = signed.canonicalRequest.text,
            serverCanonicalRequest = error?.canonicalRequest,
        )
    }

    private companion object {
        const val USER_METADATA_PREFIX = "x-amz-meta-"
    }
}

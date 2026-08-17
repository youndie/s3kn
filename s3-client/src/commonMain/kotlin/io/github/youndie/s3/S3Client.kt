package io.github.youndie.s3

import io.github.youndie.s3.sigv4.S3Operation
import io.github.youndie.s3.sigv4.S3Payload
import io.github.youndie.s3.sigv4.S3Signer
import io.github.youndie.s3.sigv4.SignedS3Request
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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

/** An object being read, valid only while the block that received it is running. */
public class S3Object(
    public val contentLength: Long?,
    public val contentType: String?,
    public val eTag: String?,
    /**
     * The body, streamed.
     *
     * Live only inside the `get` block: once it returns, the connection is released and the
     * channel is closed. Anything that must outlive the call has to be copied out of it.
     */
    public val body: ByteReadChannel,
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
     * Stores an object whose body is already in memory.
     *
     * The body is hashed, so the signature covers it.
     *
     * @return the `ETag` S3 assigned, quoted as it arrives.
     */
    public suspend fun put(
        bucket: String,
        key: String,
        body: ByteArray,
        contentType: String? = null,
    ): String? {
        val response =
            execute(
                S3Operation(
                    method = "PUT",
                    bucket = bucket,
                    key = key,
                    headers = contentTypeHeader(contentType),
                    payload = S3Payload.InMemory(body),
                ),
            ) { setBody(body) }
        return response.headers["ETag"]
    }

    /**
     * Stores an object whose body is streamed.
     *
     * [contentLength] is required and not a convenience. Without it the engine falls back to
     * chunked transfer encoding, and S3 answers `411 MissingContentLength`
     * (docs/spec/s3-service-2.json:4768). That failure appears only with a real stream, never with
     * a `ByteArray`, so an optional parameter would hide it until production.
     *
     * The body cannot be hashed before it is read, so it is signed as `UNSIGNED-PAYLOAD`. Over
     * plain HTTP that is refused unless the configuration allows it.
     */
    public suspend fun put(
        bucket: String,
        key: String,
        body: ByteReadChannel,
        contentLength: Long,
        contentType: String? = null,
    ): String? {
        require(contentLength >= 0) { "Content length must not be negative, got $contentLength" }
        val response =
            execute(
                S3Operation(
                    method = "PUT",
                    bucket = bucket,
                    key = key,
                    headers = contentTypeHeader(contentType),
                    payload = S3Payload.Streamed,
                ),
            ) {
                header("Content-Length", contentLength.toString())
                setBody(body)
            }
        return response.headers["ETag"]
    }

    /**
     * Reads an object, handing its body to [consume] as a stream.
     *
     * The body is never held whole in memory: a five-gigabyte object is read as it arrives. That is
     * also why the channel is valid only inside [consume] — the connection is released when it
     * returns.
     *
     * @param range byte range to read; the response is then `206 Partial Content`.
     */
    public suspend fun <T> get(
        bucket: String,
        key: String,
        range: LongRange? = null,
        consume: suspend (S3Object) -> T,
    ): T {
        val signed =
            signer.sign(
                S3Operation(
                    method = "GET",
                    bucket = bucket,
                    key = key,
                    headers = range?.let { listOf("Range" to "bytes=${it.first}-${it.last}") } ?: emptyList(),
                ),
            )

        return http
            .prepareRequest(signed.url) {
                method = HttpMethod.Get
                signed.headers.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw errorFrom(response, "GET", signed)
                }
                consume(
                    S3Object(
                        contentLength = response.headers["Content-Length"]?.toLongOrNull(),
                        contentType = response.headers["Content-Type"],
                        eTag = response.headers["ETag"],
                        body = response.bodyAsChannel(),
                    ),
                )
            }
    }

    /**
     * Removes an object.
     *
     * Removing something that is not there succeeds. That is S3's behaviour rather than an
     * oversight here: the operation means "make sure this key is gone"
     * (docs/api/protocol-s3.md, section 4.3).
     */
    public suspend fun delete(
        bucket: String,
        key: String,
    ) {
        execute(S3Operation(method = "DELETE", bucket = bucket, key = key))
    }

    /**
     * Lists one page of a bucket's contents.
     *
     * Use [list] unless you are driving the paging yourself; this is what it is built on.
     *
     * @param prefix restrict to keys starting with it.
     * @param delimiter roll keys sharing a segment up into [S3ListPage.commonPrefixes].
     * @param maxKeys upper bound on this page; S3 caps it at 1000 regardless.
     * @param continuationToken [S3ListPage.nextContinuationToken] of the previous page.
     * @param startAfter begin after this key, as a one-shot alternative to a token.
     */
    public suspend fun listPage(
        bucket: String,
        prefix: String? = null,
        delimiter: String? = null,
        maxKeys: Int? = null,
        continuationToken: String? = null,
        startAfter: String? = null,
    ): S3ListPage {
        val query =
            buildList {
                add("list-type" to "2")
                // Always, never on request. A key may hold any Unicode character, including ones an
                // XML 1.0 parser cannot represent at all, and without this the document itself is
                // malformed — in the user's bucket, not in our tests
                // (docs/spec/s3-service-2.json, shapes.EncodingType).
                add("encoding-type" to "url")
                prefix?.let { add("prefix" to it) }
                delimiter?.let { add("delimiter" to it) }
                maxKeys?.let { add("max-keys" to it.toString()) }
                continuationToken?.let { add("continuation-token" to it) }
                startAfter?.let { add("start-after" to it) }
            }

        val response = execute(S3Operation(method = "GET", bucket = bucket, query = query))
        return parseListBucketResult(response.bodyAsText())
    }

    /**
     * Lists a bucket page by page, fetching each one only when it is asked for.
     *
     * A bucket can hold millions of keys, so the whole listing is never assembled: the flow emits a
     * page, and the next request happens only if the collector asks for more. Stopping early — a
     * `first`, a `take`, a `return` out of `collect` — stops the requests.
     */
    public fun list(
        bucket: String,
        prefix: String? = null,
        delimiter: String? = null,
        maxKeys: Int? = null,
        startAfter: String? = null,
    ): Flow<S3ListPage> =
        flow {
            var token: String? = null
            do {
                val page =
                    listPage(
                        bucket = bucket,
                        prefix = prefix,
                        delimiter = delimiter,
                        maxKeys = maxKeys,
                        continuationToken = token,
                        // Only the first request carries it; afterwards the token is the position.
                        startAfter = startAfter.takeIf { token == null },
                    )
                emit(page)
                token = page.nextContinuationToken
            } while (page.isTruncated && token != null)
        }

    /**
     * Starts a multipart upload.
     *
     * Nothing is stored until [completeMultipartUpload]; until then the parts already sent occupy
     * storage and are billed, so a started upload has to end in a completion or an
     * [abortMultipartUpload].
     */
    public suspend fun createMultipartUpload(
        bucket: String,
        key: String,
        contentType: String? = null,
    ): S3MultipartUpload {
        val response =
            execute(
                S3Operation(
                    method = "POST",
                    bucket = bucket,
                    key = key,
                    query = listOf("uploads" to ""),
                    headers = contentTypeHeader(contentType),
                ),
            )
        return S3MultipartUpload(bucket, key, parseUploadId(response.bodyAsText()))
    }

    /** Uploads one part held in memory. */
    public suspend fun uploadPart(
        upload: S3MultipartUpload,
        partNumber: Int,
        body: ByteArray,
    ): S3CompletedPart {
        requirePartNumber(partNumber)
        val response = execute(partOperation(upload, partNumber, S3Payload.InMemory(body))) { setBody(body) }
        return completedPart(partNumber, response)
    }

    /** Uploads one part from a stream. As with [put], the length is required, not optional. */
    public suspend fun uploadPart(
        upload: S3MultipartUpload,
        partNumber: Int,
        body: ByteReadChannel,
        contentLength: Long,
    ): S3CompletedPart {
        requirePartNumber(partNumber)
        require(contentLength >= 0) { "Content length must not be negative, got $contentLength" }
        val response =
            execute(partOperation(upload, partNumber, S3Payload.Streamed)) {
                header("Content-Length", contentLength.toString())
                setBody(body)
            }
        return completedPart(partNumber, response)
    }

    /**
     * Finishes a multipart upload, assembling the parts into one object.
     *
     * @return the object's `ETag`.
     * @throws S3Exception when the assembly failed — **including when the status was `200`**. S3
     *   answers before it knows the outcome, so the body decides
     *   (docs/spec/s3-service-2.json:32).
     */
    public suspend fun completeMultipartUpload(
        upload: S3MultipartUpload,
        parts: List<S3CompletedPart>,
    ): String? {
        require(parts.isNotEmpty()) { "A multipart upload needs at least one part" }
        val body = completeMultipartUploadBody(parts)
        val response =
            execute(
                S3Operation(
                    method = "POST",
                    bucket = upload.bucket,
                    key = upload.key,
                    query = listOf("uploadId" to upload.uploadId),
                    headers = listOf("Content-Type" to "application/xml"),
                    payload = S3Payload.InMemory(body.encodeToByteArray()),
                ),
            ) { setBody(body.encodeToByteArray()) }
        return parseCompleteMultipartUpload(response.bodyAsText())
    }

    /** Gives up on a multipart upload and releases the parts already stored. */
    public suspend fun abortMultipartUpload(upload: S3MultipartUpload) {
        execute(
            S3Operation(
                method = "DELETE",
                bucket = upload.bucket,
                key = upload.key,
                query = listOf("uploadId" to upload.uploadId),
            ),
        )
    }

    /**
     * Uploads a stream of unknown length as one object, in parts, several at a time.
     *
     * The stream is read one part ahead of what is being sent, so at most [concurrency] parts are
     * held in memory at once — [partSize] × [concurrency] bytes, and no more, however large the
     * object is.
     *
     * If anything fails or the caller is cancelled, the upload is aborted before the failure
     * propagates. A multipart upload nobody finishes keeps its parts, and they are billed until
     * somebody notices.
     *
     * @param partSize size of every part but the last; S3 requires at least
     *   [S3MultipartLimits.MIN_PART_SIZE].
     * @param concurrency how many parts to send at once.
     */
    public suspend fun putMultipart(
        bucket: String,
        key: String,
        body: ByteReadChannel,
        partSize: Long = DEFAULT_PART_SIZE,
        concurrency: Int = DEFAULT_CONCURRENCY,
        contentType: String? = null,
    ): String? {
        require(partSize >= S3MultipartLimits.MIN_PART_SIZE) {
            "Part size must be at least ${S3MultipartLimits.MIN_PART_SIZE} bytes, got $partSize"
        }
        require(concurrency >= 1) { "Concurrency must be at least 1, got $concurrency" }

        val upload = createMultipartUpload(bucket, key, contentType)
        try {
            val permits = Semaphore(concurrency)
            val parts =
                coroutineScope {
                    val uploads = mutableListOf<kotlinx.coroutines.Deferred<S3CompletedPart>>()
                    var partNumber = S3MultipartLimits.MIN_PART_NUMBER
                    while (true) {
                        // Read before taking a permit: reading is what decides whether there is
                        // another part at all, and it is sequential either way.
                        val chunk = body.readPart(partSize)
                        if (chunk.isEmpty() && partNumber > S3MultipartLimits.MIN_PART_NUMBER) break
                        requirePartNumber(partNumber)
                        val number = partNumber++
                        uploads += async { permits.withPermit { uploadPart(upload, number, chunk) } }
                        if (chunk.size < partSize) break
                    }
                    uploads.awaitAll()
                }
            return completeMultipartUpload(upload, parts)
        } catch (failure: Throwable) {
            // NonCancellable because the usual reason to be here is cancellation, and an abort that
            // is itself cancelled leaves exactly the parts it was meant to remove.
            withContext(NonCancellable) { runCatching { abortMultipartUpload(upload) } }
            throw failure
        }
    }

    private fun partOperation(
        upload: S3MultipartUpload,
        partNumber: Int,
        payload: S3Payload,
    ): S3Operation =
        S3Operation(
            method = "PUT",
            bucket = upload.bucket,
            key = upload.key,
            query = listOf("partNumber" to partNumber.toString(), "uploadId" to upload.uploadId),
            payload = payload,
        )

    private fun completedPart(
        partNumber: Int,
        response: HttpResponse,
    ): S3CompletedPart {
        // The ETag of a part arrives as a header, not in a body — the response has none
        // (docs/spec/s3-service-2.json, shapes.UploadPartOutput.members.ETag).
        val eTag =
            response.headers["ETag"]
                ?: throw S3Exception(
                    status = response.status.value,
                    errorMessage = "Part $partNumber was stored without an ETag header, so it cannot be completed",
                )
        return S3CompletedPart(partNumber, eTag)
    }

    private fun contentTypeHeader(contentType: String?): List<Pair<String, String>> =
        contentType?.let { listOf("Content-Type" to it) } ?: emptyList()

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
    private suspend fun execute(
        operation: S3Operation,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val signed = signer.sign(operation)
        val response =
            http.request(signed.url) {
                method = HttpMethod.parse(operation.method)
                signed.headers.forEach { (name, value) -> header(name, value) }
                configure()
            }

        if (!response.status.isSuccess()) {
            throw errorFrom(response, operation.method, signed)
        }
        return response
    }

    private suspend fun errorFrom(
        response: HttpResponse,
        method: String,
        signed: SignedS3Request,
    ): S3Exception {
        // A HEAD response has no body by definition. Asking for one anyway is not an error, but
        // there is nothing to gain and a stalled read to lose.
        val error = if (method == "HEAD") null else parseErrorBody(response.bodyAsText())

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

        /** Eight mebibytes: comfortably above the five-mebibyte floor, small enough to retry. */
        const val DEFAULT_PART_SIZE = 8L * 1024 * 1024
        const val DEFAULT_CONCURRENCY = 4
    }
}

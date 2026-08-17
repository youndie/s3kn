package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Multipart upload, without a server.
 *
 * Two of these cannot be checked against a real one at all: a `200` that carries an error inside it
 * cannot be asked for on demand, and neither can a failure halfway through the parts.
 *
 * Contract: docs/api/protocol-s3.md, section 4.6.
 */
class MultipartUploadTest {
    @Test
    fun `starts an upload with the uploads parameter and reads the id`() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                client(
                    MockEngine { request ->
                        captured = request
                        respond(
                            "<InitiateMultipartUploadResult><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>",
                        )
                    },
                )

            val upload = client.createMultipartUpload("photos", "big.bin")

            assertEquals("upload-1", upload.uploadId)
            assertEquals("POST", captured?.method?.value)
            assertEquals("uploads=", captured?.url?.encodedQuery)
        }

    @Test
    fun `takes the etag of a part from the header because there is no body`() =
        runTest {
            // docs/spec/s3-service-2.json, shapes.UploadPartOutput.members.ETag: location = header.
            // A client looking for it in the body finds nothing and cannot complete the upload.
            var captured: HttpRequestData? = null
            val client =
                client(
                    MockEngine { request ->
                        captured = request
                        respond(content = "", headers = headersOf("ETag", "\"part-etag\""))
                    },
                )

            val part = client.uploadPart(upload(), partNumber = 3, body = "x".encodeToByteArray())

            assertEquals(3, part.partNumber)
            assertEquals("\"part-etag\"", part.eTag)
            assertTrue("partNumber=3" in captured?.url?.encodedQuery.orEmpty())
            assertTrue("uploadId=upload-1" in captured?.url?.encodedQuery.orEmpty())
        }

    @Test
    fun `refuses a part number outside the range S3 allows`() =
        runTest {
            // Caught here rather than at the server, after the bytes have already gone out
            // (docs/spec/s3-service-2.json:1604).
            val client = client(MockEngine { respond("") })

            assertFailsWith<IllegalArgumentException> { client.uploadPart(upload(), 0, ByteArray(0)) }
            assertFailsWith<IllegalArgumentException> { client.uploadPart(upload(), 10_001, ByteArray(0)) }
        }

    @Test
    fun `lists the parts in ascending order however they were given`() {
        // Out of order is `InvalidPartOrder`, and the caller collecting parts concurrently has no
        // reason to have them sorted already.
        val body =
            completeMultipartUploadBody(
                listOf(S3CompletedPart(3, "\"c\""), S3CompletedPart(1, "\"a\""), S3CompletedPart(2, "\"b\"")),
            )

        assertEquals(
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>\"a\"</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>\"b\"</ETag></Part>" +
                "<Part><PartNumber>3</PartNumber><ETag>\"c\"</ETag></Part>" +
                "</CompleteMultipartUpload>",
            body,
        )
    }

    @Test
    fun `treats an error inside a 200 response as a failure`() =
        runTest {
            // The quirk that makes this operation different from every other one: S3 answers 200
            // before it knows the outcome, so "a 200 OK response can contain either a success or an
            // error" (docs/spec/s3-service-2.json:32). Deciding by status reports a failed upload as
            // a successful one, and the object simply is not there afterwards.
            val client =
                client(
                    MockEngine {
                        respond(
                            status = HttpStatusCode.OK,
                            content =
                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                    "<Error><Code>InternalError</Code>" +
                                    "<Message>We encountered an internal error. Please try again.</Message>" +
                                    "<RequestId>REQ-1</RequestId></Error>",
                        )
                    },
                )

            val failure =
                assertFailsWith<S3Exception> {
                    client.completeMultipartUpload(upload(), listOf(S3CompletedPart(1, "\"a\"")))
                }

            assertEquals("InternalError", failure.code)
            assertEquals(200, failure.status)
            assertEquals("REQ-1", failure.requestId)
        }

    @Test
    fun `reads the etag out of a successful completion`() =
        runTest {
            val client =
                client(
                    MockEngine {
                        respond(
                            "<CompleteMultipartUploadResult><Location>http://example.com/big.bin</Location>" +
                                "<ETag>\"whole-object\"</ETag></CompleteMultipartUploadResult>",
                        )
                    },
                )

            val eTag = client.completeMultipartUpload(upload(), listOf(S3CompletedPart(1, "\"a\"")))

            assertEquals("\"whole-object\"", eTag)
        }

    @Test
    fun `refuses to complete an upload with no parts`() =
        runTest {
            val client = client(MockEngine { respond("") })

            assertFailsWith<IllegalArgumentException> { client.completeMultipartUpload(upload(), emptyList()) }
        }

    @Test
    fun `aborts with the upload id and accepts an empty response`() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                client(
                    MockEngine { request ->
                        captured = request
                        respond(content = "", status = HttpStatusCode.NoContent)
                    },
                )

            client.abortMultipartUpload(upload())

            assertEquals("DELETE", captured?.method?.value)
            assertEquals("uploadId=upload-1", captured?.url?.encodedQuery)
        }

    @Test
    fun `refuses a part size below what S3 accepts`() =
        runTest {
            // Rejecting it here saves the caller from finding out at completion time, after every
            // byte has already been uploaded.
            val client = client(MockEngine { respond("") })

            assertFailsWith<IllegalArgumentException> {
                client.putMultipart("photos", "big.bin", ByteReadChannel("x"), partSize = 1024)
            }
        }

    @Test
    fun `splits a stream into parts and completes the upload`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client(
                    MockEngine { request ->
                        requests += request
                        multipartResponse(request)
                    },
                )
            val partSize = S3MultipartLimits.MIN_PART_SIZE
            val body = ByteArray((partSize * 2 + 100).toInt()) { it.toByte() }

            val eTag = client.putMultipart("photos", "big.bin", ByteReadChannel(body), partSize = partSize)

            assertEquals("\"whole-object\"", eTag)
            val parts = requests.filter { "partNumber" in it.url.encodedQuery }
            assertEquals(3, parts.size)
            assertEquals(listOf(1, 2, 3), parts.map { partNumberOf(it) }.sorted())
        }

    @Test
    fun `aborts the upload when a part fails`() =
        runTest {
            // A multipart upload nobody finishes keeps its parts, and they are billed until somebody
            // notices. Anything that can fail therefore ends in a completion or an abort.
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client(
                    MockEngine { request ->
                        requests += request
                        if ("partNumber=2" in request.url.encodedQuery) {
                            respond(content = "", status = HttpStatusCode.InternalServerError)
                        } else {
                            multipartResponse(request)
                        }
                    },
                )
            val partSize = S3MultipartLimits.MIN_PART_SIZE
            val body = ByteArray((partSize * 2 + 1).toInt())

            assertFailsWith<S3Exception> {
                client.putMultipart("photos", "big.bin", ByteReadChannel(body), partSize = partSize, concurrency = 1)
            }

            assertTrue(
                requests.any { it.method.value == "DELETE" && "uploadId" in it.url.encodedQuery },
                "expected an abort, got ${requests.map { "${it.method.value} ${it.url.encodedQuery}" }}",
            )
        }

    @Test
    fun `aborts the upload when the caller is cancelled`() =
        runTest {
            // The abort runs under NonCancellable, so it survives the cancellation that triggered
            // it. Without that it would be cancelled too, leaving exactly the parts it exists to
            // remove.
            val aborted = CompletableDeferred<Unit>()
            val firstPartStarted = CompletableDeferred<Unit>()
            val client =
                client(
                    MockEngine { request ->
                        when {
                            request.method.value == "DELETE" -> {
                                aborted.complete(Unit)
                                respond(content = "", status = HttpStatusCode.NoContent)
                            }

                            "partNumber" in request.url.encodedQuery -> {
                                firstPartStarted.complete(Unit)
                                // Never answers, so the upload is still in flight when cancelled.
                                CompletableDeferred<Unit>().await()
                                error("unreachable")
                            }

                            else -> {
                                multipartResponse(request)
                            }
                        }
                    },
                )

            val scope = CoroutineScope(Dispatchers.Default)
            val job =
                scope.launch {
                    client.putMultipart(
                        bucket = "photos",
                        key = "big.bin",
                        body = ByteReadChannel(ByteArray((S3MultipartLimits.MIN_PART_SIZE * 2).toInt())),
                        partSize = S3MultipartLimits.MIN_PART_SIZE,
                    )
                }
            firstPartStarted.await()
            job.cancel()

            // Real time, not the scheduler's: the upload runs on Dispatchers.Default, so a
            // virtual-time timeout fires before it has done anything.
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { aborted.await() } }
        }

    private fun partNumberOf(request: HttpRequestData): Int =
        request.url.encodedQuery
            .substringAfter("partNumber=")
            .substringBefore('&')
            .toInt()

    private fun MockRequestHandleScope.multipartResponse(request: HttpRequestData) =
        when {
            "uploads" in request.url.encodedQuery -> {
                respond("<InitiateMultipartUploadResult><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")
            }

            "partNumber" in request.url.encodedQuery -> {
                respond(content = "", headers = headersOf("ETag", "\"p${partNumberOf(request)}\""))
            }

            request.method.value == "DELETE" -> {
                respond(content = "", status = HttpStatusCode.NoContent)
            }

            else -> {
                respond("<CompleteMultipartUploadResult><ETag>\"whole-object\"</ETag></CompleteMultipartUploadResult>")
            }
        }

    private fun upload(): S3MultipartUpload = S3MultipartUpload("photos", "big.bin", "upload-1")

    private fun client(engine: MockEngine): S3Client =
        S3Client(
            config =
                S3Config(
                    endpoint = S3Endpoint.parse("http://localhost:9000"),
                    region = "us-east-1",
                    credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
                    addressingStyle = AddressingStyle.PATH,
                    clock =
                        object : Clock {
                            override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
                        },
                ),
            http = HttpClient(engine),
        )
}

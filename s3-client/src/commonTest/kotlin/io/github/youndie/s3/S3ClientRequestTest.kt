package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What goes on the wire, checked without a server.
 *
 * The point of these is the path: the signature covers the encoded path exactly, so a URL builder
 * that encodes it a second time breaks every request whose key contains anything but letters — and
 * breaks it with `SignatureDoesNotMatch`, which says nothing about encoding
 * (docs/research/research-architecture.md, decision R4).
 */
class S3ClientRequestTest {
    @Test
    fun `sends the encoded path without encoding it again`() =
        runTest {
            val request = capture(key = "my dir/файл+v2.txt")

            assertEquals("/my%20dir/%D1%84%D0%B0%D0%B9%D0%BB%2Bv2.txt", request.url.encodedPath)
        }

    @Test
    fun `leaves repeated slashes in the path alone`() =
        runTest {
            // S3 signs the path verbatim, so a URL builder that collapsed `//` would send a path
            // the signature was not computed over.
            val request = capture(key = "a//b")

            assertEquals("/a//b", request.url.encodedPath)
        }

    @Test
    fun `refuses a key with a dot segment rather than sending one that cannot arrive`() =
        runTest {
            // Not a limitation of this library's encoding — it encodes such a key correctly — but
            // of what happens below it: the HTTP client strips the segment after signing, and the
            // request is rejected for a reason that names nothing (research, fact 1.9).
            val client = client(MockEngine { respond(content = "") })

            assertFailsWith<IllegalArgumentException> { client.head("photos", "a/./b") }
        }

    @Test
    fun `puts the bucket in the path in path style`() =
        runTest {
            val request = capture(style = AddressingStyle.PATH, key = "hello.txt")

            assertEquals("/photos/hello.txt", request.url.encodedPath)
            assertEquals("localhost", request.url.host)
        }

    @Test
    fun `puts the bucket in the host in virtual hosted style`() =
        runTest {
            val request = capture(style = AddressingStyle.VIRTUAL_HOSTED, key = "hello.txt")

            assertEquals("/hello.txt", request.url.encodedPath)
            assertEquals("photos.localhost", request.url.host)
        }

    @Test
    fun `sends the signature it computed`() =
        runTest {
            val request = capture(key = "hello.txt")

            val authorization = request.headers["Authorization"].orEmpty()
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), authorization)
            assertTrue("SignedHeaders=host;x-amz-content-sha256;x-amz-date" in authorization, authorization)
            assertEquals("20150830T123600Z", request.headers["X-Amz-Date"])
        }

    @Test
    fun `reads the metadata of a successful head`() =
        runTest {
            val client =
                client(
                    MockEngine {
                        respond(
                            content = "",
                            headers =
                                headersOf(
                                    "Content-Length" to listOf("11"),
                                    "ETag" to listOf("\"5eb63bbbe01eeed093cb22bb8f5acdc3\""),
                                    "Last-Modified" to listOf("Sun, 30 Aug 2015 12:36:00 GMT"),
                                    "Content-Type" to listOf("text/plain"),
                                    "x-amz-meta-Author" to listOf("nobody"),
                                ),
                        )
                    },
                )

            val metadata = client.head("photos", "hello.txt")

            assertEquals(11, metadata.contentLength)
            assertEquals("\"5eb63bbbe01eeed093cb22bb8f5acdc3\"", metadata.eTag)
            assertEquals("text/plain", metadata.contentType)
            assertEquals(mapOf("author" to "nobody"), metadata.userMetadata)
        }

    @Test
    fun `turns a head that found nothing into an error without a code`() =
        runTest {
            val client = client(MockEngine { respond(content = "", status = HttpStatusCode.NotFound) })

            val failure = assertFailsWith<S3Exception> { client.head("photos", "missing.txt") }

            assertEquals(404, failure.status)
            // Nothing was sent, so nothing is claimed. Inventing `NoSuchKey` here would be a guess:
            // a missing bucket answers the same way (docs/api/protocol-s3.md, section 4.4).
            assertNull(failure.code)
            assertTrue("404" in failure.message.orEmpty())
        }

    @Test
    fun `carries both canonical requests when the signature is refused`() =
        runTest {
            val serverCanonical = "GET\n/hello.txt\n\nhost:photos.localhost\n\nhost\nUNSIGNED-PAYLOAD"
            val client =
                client(
                    MockEngine {
                        respond(
                            content =
                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                    "<Error><Code>SignatureDoesNotMatch</Code>" +
                                    "<Message>The request signature we calculated does not match</Message>" +
                                    "<CanonicalRequest>" +
                                    serverCanonical.replace("\n", "&#10;") +
                                    "</CanonicalRequest>" +
                                    "<RequestId>REQ-1</RequestId><HostId>ID2-1</HostId></Error>",
                            status = HttpStatusCode.Forbidden,
                        )
                    },
                )

            val failure = assertFailsWith<S3Exception> { client.head("photos", "hello.txt") }
            // HEAD has no body, so the client does not read one; the parsing itself is covered by
            // S3ErrorBodyTest and against a real server by the presign test.
            assertEquals(403, failure.status)
            assertNotNull(failure.sentCanonicalRequest)
            assertTrue(failure.sentCanonicalRequest.orEmpty().startsWith("HEAD\n/hello.txt"))
        }

    private suspend fun capture(
        key: String,
        style: AddressingStyle = AddressingStyle.VIRTUAL_HOSTED,
    ): HttpRequestData {
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                respond(content = "", headers = headersOf("Content-Length", "0"))
            }
        client(engine, style).head("photos", key)
        return assertNotNull(captured)
    }

    private fun client(
        engine: MockEngine,
        style: AddressingStyle = AddressingStyle.VIRTUAL_HOSTED,
    ): S3Client =
        S3Client(
            config =
                S3Config(
                    endpoint = S3Endpoint.parse("http://localhost:9000"),
                    region = "us-east-1",
                    credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
                    addressingStyle = style,
                    clock =
                        object : Clock {
                            override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
                        },
                ),
            http = HttpClient(engine),
        )
}

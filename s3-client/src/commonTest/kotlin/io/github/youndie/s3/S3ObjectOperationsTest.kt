package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `put`, `get` and `delete`, checked without a server.
 *
 * The live versions are in [S3ClientE2eTest]; these pin down the parts a server cannot show — what
 * is refused before anything is sent, and which header carries what.
 *
 * Contract: docs/api/protocol-s3.md, sections 4.1–4.3.
 */
class S3ObjectOperationsTest {
    @Test
    fun `hashes a body it was handed in memory`() =
        runTest {
            val request = capture { client -> client.put("photos", "hello.txt", "hello".encodeToByteArray()) }

            assertEquals("PUT", request.method.value)
            assertEquals(
                // sha256("hello"): the signature covers the body, because it could.
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                request.headers["x-amz-content-sha256"],
            )
        }

    @Test
    fun `sends the content type it was given and none when it was not`() =
        runTest {
            val typed =
                capture { client ->
                    client.put("photos", "hello.txt", "hello".encodeToByteArray(), contentType = "text/plain")
                }
            val untyped = capture { client -> client.put("photos", "hello.txt", "hello".encodeToByteArray()) }

            assertTrue("content-type" in typed.headers["Authorization"].orEmpty())
            assertTrue("content-type" !in untyped.headers["Authorization"].orEmpty())
        }

    @Test
    fun `returns the etag of a stored object`() =
        runTest {
            val client =
                client(
                    MockEngine {
                        respond(content = "", headers = headersOf("ETag", "\"5eb63bbbe01eeed093cb22bb8f5acdc3\""))
                    },
                )

            val eTag = client.put("photos", "hello.txt", "hello".encodeToByteArray())

            assertEquals("\"5eb63bbbe01eeed093cb22bb8f5acdc3\"", eTag)
        }

    @Test
    fun `signs a streamed body as unsigned and states its length`() =
        runTest {
            // The length is the point. Without it the engine falls back to chunked encoding and S3
            // answers 411 MissingContentLength — a failure that never shows up with a ByteArray.
            val request =
                capture(endpoint = "https://s3.us-east-1.amazonaws.com") { client ->
                    client.put("photos", "big.bin", ByteReadChannel("streamed"), contentLength = 8)
                }

            assertEquals("UNSIGNED-PAYLOAD", request.headers["x-amz-content-sha256"])
            assertEquals("8", request.headers["Content-Length"])
        }

    @Test
    fun `refuses a negative content length before sending anything`() =
        runTest {
            val client = client(MockEngine { respond("") }, endpoint = "https://s3.us-east-1.amazonaws.com")

            assertFailsWith<IllegalArgumentException> {
                client.put("photos", "big.bin", ByteReadChannel("x"), contentLength = -1)
            }
        }

    @Test
    fun `streams a body out of get without holding it whole`() =
        runTest {
            val client =
                client(
                    MockEngine {
                        respond(
                            content = "hello there",
                            headers = headersOf("Content-Length" to listOf("11"), "ETag" to listOf("\"abc\"")),
                        )
                    },
                )

            val body = client.get("photos", "hello.txt") { it.body.readRemaining().readByteArray() }

            assertEquals("hello there", body.decodeToString())
        }

    @Test
    fun `asks for a byte range as the protocol spells it`() =
        runTest {
            val request =
                capture { client -> client.get("photos", "hello.txt", range = 0L..4L) { it.contentLength } }

            assertEquals("bytes=0-4", request.headers["Range"])
            // The range is signed along with everything else, so it has to be in SignedHeaders too.
            assertTrue("range" in request.headers["Authorization"].orEmpty())
        }

    @Test
    fun `reads the error document of a failed get`() =
        runTest {
            // Unlike HEAD, a failed GET does have a body, and it names what went wrong.
            val client =
                client(
                    MockEngine {
                        respondError(
                            status = HttpStatusCode.NotFound,
                            content =
                                "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message>" +
                                    "<RequestId>REQ-1</RequestId></Error>",
                        )
                    },
                )

            val failure = assertFailsWith<S3Exception> { client.get("photos", "missing.txt") { it.contentLength } }

            assertEquals(404, failure.status)
            assertEquals("NoSuchKey", failure.code)
            assertEquals("The specified key does not exist.", failure.errorMessage)
            assertEquals("REQ-1", failure.requestId)
        }

    @Test
    fun `sends delete as delete`() =
        runTest {
            val request = capture { client -> client.delete("photos", "hello.txt") }

            assertEquals("DELETE", request.method.value)
            assertEquals("/hello.txt", request.url.encodedPath)
        }

    @Test
    fun `accepts the empty body of a successful delete`() =
        runTest {
            // S3 answers 204 with nothing at all; a client that insists on a body would fail here.
            val client = client(MockEngine { respond(content = "", status = HttpStatusCode.NoContent) })

            client.delete("photos", "hello.txt")
        }

    private suspend fun capture(
        endpoint: String = "http://localhost:9000",
        call: suspend (S3Client) -> Unit,
    ): HttpRequestData {
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                respond(content = "", headers = headersOf("Content-Length", "0"))
            }
        call(client(engine, endpoint))
        return assertNotNull(captured)
    }

    private fun client(
        engine: MockEngine,
        endpoint: String = "http://localhost:9000",
    ): S3Client =
        S3Client(
            config =
                S3Config(
                    endpoint = S3Endpoint.parse(endpoint),
                    region = "us-east-1",
                    credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
                    addressingStyle = AddressingStyle.VIRTUAL_HOSTED,
                    clock =
                        object : Clock {
                            override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
                        },
                ),
            http = HttpClient(engine),
        )
}

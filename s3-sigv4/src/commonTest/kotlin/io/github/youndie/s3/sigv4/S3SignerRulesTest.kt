package io.github.youndie.s3.sigv4

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The rules around the S3 signer that the vectors cannot state: what happens by default, and what
 * is refused.
 *
 * A vector can only show what a correct call produces. The refusals here are the other half —
 * the cases where the honest answer is an error rather than a signature that will be rejected by
 * the server, or worse, accepted while carrying an unprotected body.
 */
class S3SignerRulesTest {
    @Test
    fun `hashes a body that is in memory`() {
        val signed = signer().sign(operation(payload = S3Payload.InMemory("hello".encodeToByteArray())))

        // sha256("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            signed.header("x-amz-content-sha256"),
        )
    }

    @Test
    fun `uses the constant hash for an empty body`() {
        val signed = signer().sign(operation(payload = S3Payload.Empty))

        assertEquals(EMPTY_PAYLOAD_SHA256, signed.header("x-amz-content-sha256"))
    }

    @Test
    fun `leaves a streamed body unsigned over https`() {
        // Hashing a stream means reading it all before the first byte goes out, which for a
        // multi-gigabyte object means buffering it. S3 allows the constant instead, and TLS is
        // what keeps the body protected (docs/research/research-architecture.md, decision R6).
        val signed = signer().sign(operation(payload = S3Payload.Streamed))

        assertEquals("UNSIGNED-PAYLOAD", signed.header("x-amz-content-sha256"))
    }

    @Test
    fun `refuses to leave a streamed body unsigned over plain http`() {
        // Without TLS the signature over the headers protects nothing about the body, and an
        // unsigned payload would let anything be substituted in transit. Refusing is the only
        // honest answer; silently buffering gigabytes would be the other option.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                signer(endpoint = "http://localhost:9000").sign(operation(payload = S3Payload.Streamed))
            }

        assertTrue("http" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `hashes an in-memory body over plain http as well`() {
        val signed =
            signer(endpoint = "http://localhost:9000")
                .sign(operation(payload = S3Payload.InMemory("hello".encodeToByteArray())))

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            signed.header("x-amz-content-sha256"),
        )
    }

    @Test
    fun `signs the host it will actually send to`() {
        val virtual = signer().sign(operation())
        val path = signer(style = AddressingStyle.PATH).sign(operation())

        assertEquals("photos.s3.us-east-1.amazonaws.com", virtual.header("host"))
        assertEquals("s3.us-east-1.amazonaws.com", path.header("host"))
    }

    @Test
    fun `presigns for one hour unless told otherwise`() {
        val url = signer().presign(method = "GET", bucket = "photos", key = "hello.txt")

        assertTrue("X-Amz-Expires=3600" in url, url)
    }

    @Test
    fun `presigns for at most seven days`() {
        val signer = signer()

        signer.presign("GET", "photos", "hello.txt", expires = 7.days)
        val failure =
            assertFailsWith<IllegalArgumentException> {
                signer.presign("GET", "photos", "hello.txt", expires = 7.days + 1.seconds)
            }

        assertTrue("604800" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `refuses an expiry that is not positive`() {
        val signer = signer()

        assertFailsWith<IllegalArgumentException> { signer.presign("GET", "photos", "hello.txt", expires = 0.seconds) }
        assertFailsWith<IllegalArgumentException> {
            signer.presign("GET", "photos", "hello.txt", expires = (-1).seconds)
        }
    }

    @Test
    fun `keeps the signature out of what it signs`() {
        // `X-Amz-Signature` is appended after the canonical query is built; a signature that
        // covered itself could not exist (docs/spec/reference/botocore-auth.py:787).
        val url = signer().presign("GET", "photos", "hello.txt", expires = 1.hours)

        assertEquals(1, url.split("X-Amz-Signature=").size - 1)
        assertTrue(url.indexOf("X-Amz-Signature=") > url.indexOf("X-Amz-SignedHeaders="), url)
    }

    @Test
    fun `carries no content hash in a presigned url`() {
        // The body is unknown when the link is made, so there is nothing to hash and the parameter
        // has no place in the query (docs/spec/reference/botocore-auth.py:810).
        val url = signer().presign("PUT", "photos", "hello.txt")

        assertTrue("x-amz-content-sha256" !in url.lowercase(), url)
    }

    private fun signer(
        endpoint: String = "https://s3.us-east-1.amazonaws.com",
        style: AddressingStyle = AddressingStyle.VIRTUAL_HOSTED,
    ): S3Signer =
        S3Signer(
            S3Config(
                endpoint = S3Endpoint.parse(endpoint),
                region = "us-east-1",
                credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
                addressingStyle = style,
                clock = fixedClock,
            ),
        )

    private fun operation(payload: S3Payload = S3Payload.Empty): S3Operation =
        S3Operation(method = "PUT", bucket = "photos", key = "hello.txt", payload = payload)

    private fun SignedS3Request.header(name: String): String =
        headers.first { (given, _) -> given.lowercase() == name }.second

    private companion object {
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
            }
    }
}

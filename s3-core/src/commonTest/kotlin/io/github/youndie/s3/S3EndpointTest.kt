package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The endpoint the client talks to, and the `host` value derived from it.
 *
 * `host` is a signed header in every S3 request, so getting it wrong is not a connection problem —
 * it is a `SignatureDoesNotMatch` that says nothing about the port.
 *
 * Rules: docs/spec/reference/botocore-auth.py:81.
 */
class S3EndpointTest {
    @Test
    fun `keeps a plain host as it is`() {
        assertEquals("s3.us-east-1.amazonaws.com", hostHeaderOf("https://s3.us-east-1.amazonaws.com"))
    }

    @Test
    fun `lower-cases the host`() {
        assertEquals("s3.us-east-1.amazonaws.com", hostHeaderOf("https://S3.US-EAST-1.AmazonAWS.com"))
    }

    @Test
    fun `keeps a non-default port`() {
        assertEquals("localhost:9000", hostHeaderOf("http://localhost:9000"))
        assertEquals("example.com:8443", hostHeaderOf("https://example.com:8443"))
    }

    @Test
    fun `drops a port that is the default for the scheme`() {
        assertEquals("example.com", hostHeaderOf("https://example.com:443"))
        assertEquals("example.com", hostHeaderOf("http://example.com:80"))
    }

    @Test
    fun `keeps a port that is default for the other scheme`() {
        // 443 is the default for https and an ordinary port for http. Comparing against a single
        // number instead of against the scheme's own default silently drops it here.
        assertEquals("example.com:443", hostHeaderOf("http://example.com:443"))
        assertEquals("example.com:80", hostHeaderOf("https://example.com:80"))
    }

    @Test
    fun `leaves userinfo out of the host`() {
        assertEquals("example.com", hostHeaderOf("https://user:pass@example.com"))
    }

    @Test
    fun `wraps an ipv6 address in brackets and keeps them`() {
        assertEquals("[::1]:9000", hostHeaderOf("http://[::1]:9000"))
        assertEquals("[2001:db8::1]", hostHeaderOf("https://[2001:db8::1]"))
    }

    @Test
    fun `ignores a path and a query on the endpoint`() {
        assertEquals("example.com", hostHeaderOf("https://example.com/"))
        assertEquals("example.com:9000", hostHeaderOf("http://example.com:9000/ignored?also=ignored"))
    }

    @Test
    fun `reports whether the endpoint is secure`() {
        assertTrue(S3Endpoint.parse("https://example.com").isSecure)
        assertTrue(!S3Endpoint.parse("http://example.com").isSecure)
    }

    @Test
    fun `rejects an endpoint without a scheme`() {
        assertFailsWith<IllegalArgumentException> { S3Endpoint.parse("s3.amazonaws.com") }
    }

    @Test
    fun `rejects a scheme that is neither http nor https`() {
        assertFailsWith<IllegalArgumentException> { S3Endpoint.parse("ftp://example.com") }
    }

    @Test
    fun `rejects an endpoint without a host`() {
        assertFailsWith<IllegalArgumentException> { S3Endpoint.parse("https://") }
    }

    @Test
    fun `rejects a port that is not a number`() {
        assertFailsWith<IllegalArgumentException> { S3Endpoint.parse("https://example.com:nine") }
    }

    private fun hostHeaderOf(url: String): String = S3Endpoint.parse(url).hostHeader
}

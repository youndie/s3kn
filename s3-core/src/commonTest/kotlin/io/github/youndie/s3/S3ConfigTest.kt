package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Everything a request needs to know before it can be signed.
 *
 * The addressing style is a stated setting rather than something guessed from the host: it decides
 * the `host` header, and `host` is signed (docs/research/research-architecture.md, open question 3).
 */
class S3ConfigTest {
    @Test
    fun `path style addresses the bucket through the endpoint host`() {
        val config = config(AddressingStyle.PATH, "http://localhost:9000")

        assertEquals("localhost:9000", config.hostHeaderFor("photos"))
    }

    @Test
    fun `virtual hosted style puts the bucket in front of the endpoint host`() {
        val config = config(AddressingStyle.VIRTUAL_HOSTED, "https://s3.us-east-1.amazonaws.com")

        assertEquals("photos.s3.us-east-1.amazonaws.com", config.hostHeaderFor("photos"))
    }

    @Test
    fun `virtual hosted style keeps a non-default port after the bucket`() {
        val config = config(AddressingStyle.VIRTUAL_HOSTED, "http://localhost:9000")

        assertEquals("photos.localhost:9000", config.hostHeaderFor("photos"))
    }

    @Test
    fun `defaults to virtual hosted style the way the AWS SDKs do`() {
        val config =
            S3Config(
                endpoint = S3Endpoint.parse("https://s3.us-east-1.amazonaws.com"),
                region = "us-east-1",
                credentials = credentials,
            )

        assertEquals(AddressingStyle.VIRTUAL_HOSTED, config.addressingStyle)
    }

    @Test
    fun `keeps the secret key out of the text form of the credentials`() {
        val text = credentials.toString()

        assertTrue("wJalrXUtnFEMI" !in text, "the secret key must not be printable: $text")
        assertTrue(SESSION_TOKEN !in text, "the session token must not be printable: $text")
        assertTrue(ACCESS_KEY in text, "the access key identifies the credentials and may show")
    }

    @Test
    fun `keeps the secret key out of the text form of the config`() {
        val text = config(AddressingStyle.PATH, "http://localhost:9000").toString()

        assertTrue("wJalrXUtnFEMI" !in text, "the secret key must not be printable: $text")
    }

    @Test
    fun `has no session token unless one is given`() {
        assertNull(S3Credentials(ACCESS_KEY, SECRET_KEY).sessionToken)
    }

    @Test
    fun `rejects blank credentials`() {
        assertFailsWith<IllegalArgumentException> { S3Credentials("", SECRET_KEY) }
        assertFailsWith<IllegalArgumentException> { S3Credentials(ACCESS_KEY, " ") }
    }

    @Test
    fun `rejects a blank region because it is part of the credential scope`() {
        assertFailsWith<IllegalArgumentException> {
            S3Config(
                endpoint = S3Endpoint.parse("https://example.com"),
                region = "",
                credentials = credentials,
            )
        }
    }

    @Test
    fun `takes the clock it is given so a test can fix the moment`() {
        val fixed =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1440938160)
            }
        val config =
            S3Config(
                endpoint = S3Endpoint.parse("https://example.com"),
                region = "us-east-1",
                credentials = credentials,
                clock = fixed,
            )

        assertSame(fixed, config.clock)
        val timestamp = config.clock.now().toSigningTimestamp()
        assertEquals("20150830T123600Z", timestamp.amzDate)
    }

    private fun config(
        style: AddressingStyle,
        endpoint: String,
    ): S3Config =
        S3Config(
            endpoint = S3Endpoint.parse(endpoint),
            region = "us-east-1",
            credentials = credentials,
            addressingStyle = style,
        )

    private companion object {
        const val ACCESS_KEY = "AKIDEXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val SESSION_TOKEN = "AQoDYXdzEPT-session-token"

        val credentials = S3Credentials(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN)
    }
}

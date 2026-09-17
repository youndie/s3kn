package io.github.youndie.s3.cli

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The one command that sends nothing (docs/features/feature-cli.md, section 5).
 *
 * There is no engine in this test and no server behind it, and that is the assertion: presigning is
 * a pure function of the configuration, the operation and the clock, so a URL can be built on a
 * target that has no HTTP engine at all.
 */
class PresignTest {
    @Test
    fun `prints a url without touching the network`() {
        val url = presignUrl(config(), command(expiresInSeconds = 900))

        assertTrue(url.startsWith("https://s3.example.com/backups/prod/d.dump?"), url)
        assertTrue(url.contains("X-Amz-Expires=900"), url)
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), url)
        assertTrue(url.contains("X-Amz-SignedHeaders=host"), url)
        assertTrue(url.contains("X-Amz-Signature="), url)
    }

    @Test
    fun `signs the method it was asked for`() {
        val get = presignUrl(config(), command(method = "GET"))
        val put = presignUrl(config(), command(method = "PUT"))

        // The method is part of the canonical request, so a link signed for GET cannot be used to
        // upload: the two signatures differ even though everything else is identical.
        assertEquals(get.substringBefore("X-Amz-Signature="), put.substringBefore("X-Amz-Signature="))
        assertTrue(get != put, "the method has to change the signature")
    }

    @Test
    fun `puts the bucket in the host when told to address it that way`() {
        val url = presignUrl(config(AddressingStyle.VIRTUAL_HOSTED), command())

        assertTrue(url.startsWith("https://backups.s3.example.com/prod/d.dump?"), url)
    }

    private fun command(
        method: String = "GET",
        expiresInSeconds: Int = 900,
    ): Command.Presign =
        Command.Presign(
            bucket = "backups",
            key = "prod/d.dump",
            method = method,
            expires = (expiresInSeconds / 60).minutes,
        )

    private fun config(style: AddressingStyle = AddressingStyle.PATH): S3Config =
        S3Config(
            endpoint = S3Endpoint.parse("https://s3.example.com"),
            region = "us-east-1",
            credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
            addressingStyle = style,
            clock = fixedClock,
        )

    private companion object {
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
            }
    }
}

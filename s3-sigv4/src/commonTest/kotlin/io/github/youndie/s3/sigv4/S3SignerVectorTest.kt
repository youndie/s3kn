package io.github.youndie.s3.sigv4

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import io.github.youndie.s3.canonicalQueryString
import io.github.youndie.s3.testing.S3HeaderVector
import io.github.youndie.s3.testing.S3SigningVectors
import io.github.youndie.s3.toSigningTimestamp
import io.github.youndie.s3.uriEncodeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * S3's own signing rules, against vectors generated from botocore.
 *
 * AWS publishes no vectors for either of the two things checked here — the path signed verbatim,
 * and the presigned URL — so these come from the reference implementation instead, produced by
 * `docs/spec/s3-signing-vectors/generate.py`. Weaker than the official suite, and still an
 * independent implementation rather than a restatement of this library's behaviour.
 */
class S3SignerVectorTest {
    @Test
    fun `encodes keys the same way the reference implementation does`() {
        // The rule this library states is `botocore-auth.py:268`; the expectations here come from
        // Python's own `quote`, so agreeing with them is not agreeing with our reading of it.
        val disagreements =
            S3SigningVectors.keyEncodings().filter { (raw, encoded) -> uriEncodeKey(raw) != encoded }

        assertTrue(
            disagreements.isEmpty(),
            disagreements.joinToString("\n") { (raw, encoded) ->
                "$raw: expected $encoded, got ${uriEncodeKey(raw)}"
            },
        )
    }

    @Test
    fun `builds the canonical request of every header case`() {
        checkHeaderCases { vector, signed -> signed.canonicalRequest.text to vector.canonicalRequest }
    }

    @Test
    fun `builds the string to sign of every header case`() {
        checkHeaderCases { vector, signed -> signed.stringToSign to vector.stringToSign }
    }

    @Test
    fun `builds the authorization header of every header case`() {
        checkHeaderCases { vector, signed -> signed.authorization to vector.authorization }
    }

    @Test
    fun `sets the content hash of every header case`() {
        checkHeaderCases { vector, signed ->
            signed.header("x-amz-content-sha256") to vector.contentSha256
        }
    }

    @Test
    fun `builds the url of every header case`() {
        checkHeaderCases { vector, signed -> signed.url to expectedUrl(vector) }
    }

    @Test
    fun `builds every presigned url`() {
        val failures =
            S3SigningVectors.presignCaseNames().mapNotNull { name ->
                val vector = S3SigningVectors.presignCase(name)
                val actual =
                    signerFor(vector.style, vector.sessionToken).presign(
                        method = vector.method,
                        bucket = vector.bucket,
                        key = vector.key,
                        expires = vector.expiresSeconds.seconds,
                        query = vector.query,
                    )
                if (actual == vector.url) null else "$name\n  expected: ${vector.url}\n  actual:   $actual"
            }

        assertTrue(failures.isEmpty(), "${failures.size} presigned urls disagree:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `keeps the path verbatim so a dot segment survives`() {
        // The whole reason PathMode exists. The generic signer turns `/a/./b` into `/a/b`; S3 must
        // not (docs/spec/reference/botocore-auth.py:538), and the vector says so independently.
        //
        // Checked through the low-level signer on purpose: S3Signer refuses such a key outright,
        // because no HTTP client will deliver it (research, fact 1.9). The rule below is still the
        // right one — it is what makes the refusal a deliberate choice rather than a bug.
        val vector = S3SigningVectors.headerCase("key-with-dot-segment")
        val signed =
            SigV4Signer("us-east-1", "s3", PathMode.VERBATIM).sign(
                SigningRequest(
                    method = "GET",
                    path = "/a/./b",
                    headers = listOf("Host" to "photos.s3.us-east-1.amazonaws.com"),
                    payloadHash = EMPTY_PAYLOAD_SHA256,
                ),
                credentials(null),
                fixedClock.now().toSigningTimestamp(),
            )

        assertEquals("/a/./b", vector.canonicalRequest.lines()[1])
        assertEquals("/a/./b", signed.canonicalRequest.text.lines()[1])
    }

    @Test
    fun `differs from the generic mode on a path the generic mode would normalise`() {
        val request =
            SigningRequest(
                method = "GET",
                path = "/a/./b//c",
                headers = listOf("Host" to "example.com"),
                payloadHash = EMPTY_PAYLOAD_SHA256,
            )
        val timestamp = fixedClock.now().toSigningTimestamp()

        val verbatim =
            SigV4Signer("us-east-1", "s3", PathMode.VERBATIM)
                .sign(request, credentials(null), timestamp)
                .canonicalRequest.text
                .lines()[1]
        val normalized =
            SigV4Signer("us-east-1", "s3", PathMode.NORMALIZED)
                .sign(request, credentials(null), timestamp)
                .canonicalRequest.text
                .lines()[1]

        assertEquals("/a/./b//c", verbatim)
        assertEquals("/a/b/c", normalized)
    }

    private fun checkHeaderCases(produce: (S3HeaderVector, SignedS3Request) -> Pair<String, String>) {
        val failures =
            S3SigningVectors.headerCaseNames().filterNot { it in REFUSED_KEYS }.mapNotNull { name ->
                val vector = S3SigningVectors.headerCase(name)
                val (actual, expected) = produce(vector, sign(vector))
                if (actual == expected) {
                    null
                } else {
                    "$name\n  expected: ${expected.replace("\n", "\\n")}\n  actual:   ${actual.replace("\n", "\\n")}"
                }
            }

        assertTrue(failures.isEmpty(), "${failures.size} header cases disagree:\n" + failures.joinToString("\n"))
    }

    private fun sign(vector: S3HeaderVector): SignedS3Request =
        signerFor(vector.style, vector.sessionToken).sign(
            S3Operation(
                method = vector.method,
                bucket = vector.bucket,
                key = vector.key,
                query = vector.query,
                payload =
                    when {
                        vector.unsigned -> S3Payload.Streamed
                        vector.body.isEmpty() -> S3Payload.Empty
                        else -> S3Payload.InMemory(vector.body.encodeToByteArray())
                    },
            ),
        )

    private fun expectedUrl(vector: S3HeaderVector): String {
        val endpoint = "s3.us-east-1.amazonaws.com"
        val pathStyle = vector.style == "path"
        val host = if (pathStyle) endpoint else "${vector.bucket}.$endpoint"
        val path =
            if (pathStyle) {
                "/${vector.bucket}/${uriEncodeKey(vector.key)}"
            } else {
                "/${uriEncodeKey(vector.key)}"
            }
        val query = if (vector.query.isEmpty()) "" else "?" + canonicalQueryString(vector.query)
        return "https://$host$path$query"
    }

    private fun signerFor(
        style: String,
        sessionToken: String?,
    ): S3Signer =
        S3Signer(
            S3Config(
                endpoint = S3Endpoint.parse(S3SigningVectors.ENDPOINT),
                region = "us-east-1",
                credentials = credentials(sessionToken),
                addressingStyle =
                    if (style == "path") AddressingStyle.PATH else AddressingStyle.VIRTUAL_HOSTED,
                clock = fixedClock,
            ),
        )

    private fun credentials(sessionToken: String?): S3Credentials =
        S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", sessionToken)

    private fun SignedS3Request.header(name: String): String =
        headers.first { (given, _) -> given.lowercase() == name }.second

    private companion object {
        /**
         * Cases whose key S3Signer refuses to sign at all, because no HTTP client would deliver it
         * (research, fact 1.9). The vectors stay: they are the evidence that the underlying rule is
         * right, and the refusal is a decision on top of it, not a workaround for a bug.
         */
        val REFUSED_KEYS = setOf("key-with-dot-segment")

        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
            }
    }
}

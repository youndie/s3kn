package io.github.youndie.s3.sigv4

import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.testing.SigV4TestCase
import io.github.youndie.s3.testing.SigV4TestSuite
import io.github.youndie.s3.toSigningTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The acceptance of this milestone: the official AWS vectors, all thirty-four of them.
 *
 * Every case is checked at all three steps, so a failure says which one broke rather than only
 * that the signature differs. The vectors are the generic SigV4, where the path is normalised and
 * re-encoded (docs/spec/reference/botocore-auth.py:385); S3 skips both, and that difference is what
 * [PathMode] carries.
 *
 * Four of these cases are skipped by botocore's own runner — `normalize-path/get-space` because a
 * general HTTP parser cannot read the request line, and the three `get-vanilla-query-order-*`
 * because it parses the query into a map. Neither limitation is in the algorithm, so all
 * thirty-four run here.
 *
 * Vectors: docs/spec/aws-sig-v4-test-suite.
 */
class SigV4VectorTest {
    @Test
    fun `builds the canonical request of every case in the suite`() {
        checkEveryCase("canonical request") { case ->
            sign(case).canonicalRequest.text to case.canonicalRequest
        }
    }

    @Test
    fun `builds the string to sign of every case in the suite`() {
        checkEveryCase("string to sign") { case ->
            sign(case).stringToSign to case.stringToSign
        }
    }

    @Test
    fun `builds the authorization header of every case in the suite`() {
        checkEveryCase("authorization header") { case ->
            sign(case).authorization to case.authorization
        }
    }

    @Test
    fun `covers all thirty-four cases including the four other SDKs skip`() {
        val names = SigV4TestSuite.caseNames()

        assertEquals(34, names.size)
        assertTrue("normalize-path/get-space" in names)
        assertTrue("get-vanilla-query-order-key" in names)
        assertTrue("get-vanilla-query-order-key-case" in names)
        assertTrue("get-vanilla-query-order-value" in names)
    }

    @Test
    fun `signs the session token when the credentials carry one`() {
        // The header is absent from the request and still ends up signed: the signer adds it.
        val case = SigV4TestSuite.case("get-vanilla-with-session-token")

        assertTrue("x-amz-security-token" !in case.request.lowercase())
        assertTrue("x-amz-security-token" in sign(case).canonicalRequest.signedHeaders)
    }

    @Test
    fun `leaves the session token out when the credentials carry none`() {
        // post-sts-header-after is the case whose token is attached after signing, so it is not
        // part of the signature at all.
        val case = SigV4TestSuite.case("post-sts-token/post-sts-header-after")

        assertEquals("host;x-amz-date", sign(case).canonicalRequest.signedHeaders)
    }

    private fun checkEveryCase(
        step: String,
        produce: (SigV4TestCase) -> Pair<String, String>,
    ) {
        val failures =
            SigV4TestSuite.caseNames().mapNotNull { name ->
                val case = SigV4TestSuite.case(name)
                val (actual, expected) = produce(case)
                if (actual == expected) {
                    null
                } else {
                    "$name\n  expected: ${expected.replace("\n", "\\n")}\n  actual:   ${actual.replace("\n", "\\n")}"
                }
            }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of 34 cases disagree on the $step:\n" + failures.joinToString("\n"),
        )
    }

    private fun sign(case: SigV4TestCase): SignedRequest =
        signer.sign(
            request =
                SigningRequest(
                    method = case.method,
                    path = case.path,
                    query = case.query,
                    headers = case.headers,
                    payloadHash = sha256Hex(case.body.encodeToByteArray()),
                ),
            credentials =
                S3Credentials(
                    accessKeyId = SigV4TestSuite.ACCESS_KEY,
                    secretAccessKey = SigV4TestSuite.SECRET_KEY,
                    sessionToken = case.sessionToken,
                ),
            timestamp = Instant.fromEpochSeconds(SUITE_EPOCH_SECONDS).toSigningTimestamp(),
        )

    private companion object {
        /** `20150830T123600Z`, the moment every case in the suite is signed at. */
        const val SUITE_EPOCH_SECONDS = 1_440_938_160L

        val signer =
            SigV4Signer(
                region = SigV4TestSuite.REGION,
                service = SigV4TestSuite.SERVICE,
                pathMode = PathMode.NORMALIZED,
            )
    }
}

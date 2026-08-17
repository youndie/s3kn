package io.github.youndie.s3.sigv4

import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.toSigningTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The two lines of the canonical request the suite exercises only indirectly.
 *
 * The whole-suite check in [SigV4VectorTest] proves the algorithm; these prove the individual
 * rules, so that a failure points at a rule rather than at thirty-four cases at once.
 *
 * Rules: docs/spec/reference/botocore-auth.py:301 for the headers, `:385` for the path,
 * and botocore's `remove_dot_segments` for the normalisation the latter calls.
 */
class CanonicalFormTest {
    @Test
    fun `lower-cases header names and sorts them`() {
        val canonical = canonicalise(headers = listOf("Zeta" to "1", "Host" to "example.com", "Alpha" to "2"))

        // x-amz-date sorts in among them because the signer adds it — see the test below.
        assertEquals("alpha;host;x-amz-date;zeta", canonical.signedHeaders)
        assertEquals(
            listOf("alpha:2", "host:example.com", "x-amz-date:20150830T123600Z", "zeta:1"),
            canonical.text.lines().subList(3, 7),
        )
    }

    @Test
    fun `trims a header value and collapses runs of whitespace inside it`() {
        // The suite's get-header-value-trim expects `"a   b   c"` to become `"a b c"` — the runs
        // collapse even inside the quotes, which is not what RFC 9110 would do with a quoted string.
        val canonical = canonicalise(headers = listOf("Host" to "example.com", "My-Header" to """  "a   b   c"  """))

        assertEquals("""my-header:"a b c"""", canonical.text.lines()[4])
    }

    @Test
    fun `joins repeated header names with commas in the order they were given`() {
        val canonical =
            canonicalise(
                headers =
                    listOf(
                        "Host" to "example.com",
                        "My-Header" to "value4",
                        "My-Header" to "value1",
                        "My-Header" to "value3",
                    ),
            )

        assertEquals("my-header:value4,value1,value3", canonical.text.lines()[4])
        assertEquals("host;my-header;x-amz-date", canonical.signedHeaders)
    }

    @Test
    fun `adds the date header from the timestamp it signs with`() {
        // Nothing else may supply x-amz-date: a header and a signature naming different moments is
        // rejected, and the caller holding a second copy of the timestamp is how that happens.
        val canonical = canonicalise(headers = listOf("Host" to "example.com"))

        assertEquals("x-amz-date:20150830T123600Z", canonical.text.lines()[4])
        assertEquals("host;x-amz-date", canonical.signedHeaders)
    }

    @Test
    fun `replaces a date header the caller supplied`() {
        val canonical =
            canonicalise(headers = listOf("Host" to "example.com", "X-Amz-Date" to "19700101T000000Z"))

        assertEquals(1, canonical.text.lines().count { it.startsWith("x-amz-date:") })
        assertEquals("x-amz-date:20150830T123600Z", canonical.text.lines()[4])
    }

    @Test
    fun `normalises dot segments and repeated slashes in the generic mode`() {
        assertEquals("/", canonicalPathOf("/example/..", PathMode.NORMALIZED))
        assertEquals("/", canonicalPathOf("/example1/example2/../..", PathMode.NORMALIZED))
        assertEquals("/", canonicalPathOf("//", PathMode.NORMALIZED))
        assertEquals("/", canonicalPathOf("/./", PathMode.NORMALIZED))
        assertEquals("/example", canonicalPathOf("/./example", PathMode.NORMALIZED))
        assertEquals("/example/", canonicalPathOf("//example//", PathMode.NORMALIZED))
        assertEquals("/", canonicalPathOf("", PathMode.NORMALIZED))
    }

    @Test
    fun `encodes the path after normalising it in the generic mode`() {
        assertEquals("/example%20space/", canonicalPathOf("/example space/", PathMode.NORMALIZED))
        assertEquals("/example/%24delete", canonicalPathOf("/example/\$delete", PathMode.NORMALIZED))
        assertEquals("/%E1%88%B4", canonicalPathOf("/ሴ", PathMode.NORMALIZED))
    }

    @Test
    fun `takes the path as it stands in the verbatim mode`() {
        // What S3 needs, and the whole reason the mode exists
        // (docs/spec/reference/botocore-auth.py:538). Exercised against the vectors in M3.
        assertEquals("/./", canonicalPathOf("/./", PathMode.VERBATIM))
        assertEquals("//example//", canonicalPathOf("//example//", PathMode.VERBATIM))
        assertEquals("/example%20space/", canonicalPathOf("/example%20space/", PathMode.VERBATIM))
        assertEquals("/", canonicalPathOf("", PathMode.VERBATIM))
    }

    private fun canonicalPathOf(
        path: String,
        mode: PathMode,
    ): String =
        SigV4Signer(region = "us-east-1", service = "service", pathMode = mode)
            .sign(
                request = SigningRequest(method = "GET", path = path, payloadHash = EMPTY_BODY_SHA256),
                credentials = credentials,
                timestamp = timestamp,
            ).canonicalRequest.text
            .lines()[1]

    private fun canonicalise(headers: List<Pair<String, String>>): CanonicalRequest =
        SigV4Signer(region = "us-east-1", service = "service", pathMode = PathMode.NORMALIZED)
            .sign(
                request =
                    SigningRequest(
                        method = "GET",
                        path = "/",
                        headers = headers,
                        payloadHash = EMPTY_BODY_SHA256,
                    ),
                credentials = credentials,
                timestamp = timestamp,
            ).canonicalRequest

    private companion object {
        const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
        val timestamp = Instant.fromEpochSeconds(1_440_938_160L).toSigningTimestamp()
    }
}

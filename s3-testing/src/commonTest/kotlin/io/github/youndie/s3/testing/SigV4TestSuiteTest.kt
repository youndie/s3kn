package io.github.youndie.s3.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that a test can reach the vendored specification on every target this project builds —
 * including linuxX64, where there is no classpath to read resources from.
 *
 * Vectors: docs/spec/aws-sig-v4-test-suite/get-vanilla.
 */
class SigV4TestSuiteTest {
    @Test
    fun `reads the get-vanilla case from the vendored suite`() {
        val case = SigV4TestSuite.case("get-vanilla")

        assertEquals("get-vanilla", case.name)
        assertEquals(
            listOf(
                "GET / HTTP/1.1",
                "Host:example.amazonaws.com",
                "X-Amz-Date:20150830T123600Z",
            ),
            case.request.split("\n"),
        )
    }

    @Test
    fun `keeps the canonical request byte for byte`() {
        val case = SigV4TestSuite.case("get-vanilla")

        assertEquals(
            listOf(
                "GET",
                "/",
                "",
                "host:example.amazonaws.com",
                "x-amz-date:20150830T123600Z",
                "",
                "host;x-amz-date",
                // sha256 of an empty body, the constant every empty-payload request carries.
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            case.canonicalRequest.split("\n"),
        )
        assertTrue(!case.canonicalRequest.endsWith("\n"), "the published files end without a newline")
    }

    @Test
    fun `finds a case that lives in a subdirectory`() {
        val case = SigV4TestSuite.case("normalize-path/get-slash-dot-slash")

        assertTrue(case.request.startsWith("GET /./ HTTP/1.1"))
        // The generic SigV4 normalises `/./` away. S3 does not — see docs/api/protocol-s3.md,
        // section 2, and docs/spec/reference/botocore-auth.py:538.
        assertEquals("/", case.canonicalRequest.split("\n")[1])
    }
}

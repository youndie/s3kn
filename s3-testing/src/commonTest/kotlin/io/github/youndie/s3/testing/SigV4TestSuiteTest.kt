package io.github.youndie.s3.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader for the vendored vectors, and the parser for the raw HTTP requests they ship.
 *
 * The parser is fixture code, but it is the reason four of the thirty-four cases are famously
 * skipped by SDKs: a general-purpose HTTP parser chokes on a request target containing a space,
 * and a query parsed into a map loses repeated names. Both are handled here, so all thirty-four
 * cases can run in M2.
 *
 * Vectors: docs/spec/aws-sig-v4-test-suite.
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
    fun `reads the string to sign and the authorization header`() {
        val case = SigV4TestSuite.case("get-vanilla")

        assertEquals(
            listOf(
                "AWS4-HMAC-SHA256",
                "20150830T123600Z",
                "20150830/us-east-1/service/aws4_request",
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            ),
            case.stringToSign.split("\n"),
        )
        assertTrue(case.authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/"))
    }

    @Test
    fun `finds a case that lives in a subdirectory`() {
        val case = SigV4TestSuite.case("normalize-path/get-slash-dot-slash")

        assertTrue(case.request.startsWith("GET /./ HTTP/1.1"))
        // The generic SigV4 normalises `/./` away. S3 does not — see docs/api/protocol-s3.md,
        // section 2, and docs/spec/reference/botocore-auth.py:538.
        assertEquals("/", case.canonicalRequest.split("\n")[1])
    }

    @Test
    fun `enumerates every case in the suite including the nested ones`() {
        val names = SigV4TestSuite.caseNames()

        assertEquals(34, names.size, names.toString())
        assertTrue("get-vanilla" in names)
        assertTrue("normalize-path/get-space" in names)
        assertTrue("post-sts-token/post-sts-header-before" in names)
        assertEquals(names.sorted(), names, "the order must be stable so failures are comparable")
    }

    @Test
    fun `splits the request line even when the target contains a space`() {
        // The case a general-purpose HTTP parser rejects, which is why botocore skips it
        // (docs/spec/reference/botocore-auth.py is fine; its test runner is not).
        val case = SigV4TestSuite.case("normalize-path/get-space")

        assertEquals("GET", case.method)
        assertEquals("/example space/", case.path)
        assertEquals(emptyList(), case.query)
    }

    @Test
    fun `keeps a non-ascii target as text`() {
        val case = SigV4TestSuite.case("get-utf8")

        assertEquals("/ሴ", case.path)
    }

    @Test
    fun `folds a header value continued on the next line`() {
        val case = SigV4TestSuite.case("get-header-value-multiline")

        assertEquals(
            listOf(
                "Host" to "example.amazonaws.com",
                "My-Header1" to "value1 value2 value3",
                "X-Amz-Date" to "20150830T123600Z",
            ),
            case.headers,
        )
    }

    @Test
    fun `keeps repeated headers in the order they were sent`() {
        val case = SigV4TestSuite.case("get-header-value-order")

        assertEquals(
            listOf("value4", "value1", "value3", "value2"),
            case.headers.filter { it.first == "My-Header1" }.map { it.second },
        )
    }

    @Test
    fun `keeps the raw spacing of a header value for the signer to trim`() {
        // Trimming belongs to the canonical form, not to the parser: `"a   b   c"` has to arrive
        // intact so the signer is the one proving it collapses the runs.
        val case = SigV4TestSuite.case("get-header-value-trim")

        assertEquals(""" "a   b   c"""", case.headers.first { it.first == "My-Header2" }.second)
    }

    @Test
    fun `reads the body that follows the blank line`() {
        val case = SigV4TestSuite.case("post-x-www-form-urlencoded")

        assertEquals("Param1=value1", case.body)
        assertEquals("", SigV4TestSuite.case("post-vanilla").body)
    }

    @Test
    fun `decodes percent escapes in the query`() {
        val case = SigV4TestSuite.case("get-vanilla-query-order-encoded")

        assertEquals(
            listOf("Param-3" to "Value3", "Param" to "Value2", "ሴ" to "Value1"),
            case.query,
        )
    }

    @Test
    fun `keeps repeated query names that a map would collapse`() {
        val case = SigV4TestSuite.case("get-vanilla-query-order-value")

        assertEquals(listOf("Param1" to "value2", "Param1" to "value1"), case.query)
    }

    @Test
    fun `takes the session token from the expected canonical request`() {
        // Circular on purpose, and the reference implementation does the same
        // (botocore/tests/unit/auth/test_sigv4.py, SignatureTestCase). Two cases carry a token and
        // the two tokens differ, so there is nothing to hardcode; and one case — post-sts-header-after
        // — deliberately has credentials without a token, which is only visible in the expected
        // output.
        assertNotNull(SigV4TestSuite.case("get-vanilla-with-session-token").sessionToken)
        assertNotNull(SigV4TestSuite.case("post-sts-token/post-sts-header-before").sessionToken)
        assertNull(SigV4TestSuite.case("post-sts-token/post-sts-header-after").sessionToken)
        assertNull(SigV4TestSuite.case("get-vanilla").sessionToken)
    }

    @Test
    fun `reads the session token whole even though it contains slashes and equals signs`() {
        val token = SigV4TestSuite.case("post-sts-token/post-sts-header-before").sessionToken

        assertEquals(336, token?.length)
        assertTrue(token.orEmpty().endsWith("=="))
    }
}

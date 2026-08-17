package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a failed request turns into.
 *
 * The awkward part of the model is that an S3 error code is optional. `HEAD` has no response body
 * by definition, so a missing key arrives as a bare `404` with no `<Code>NoSuchKey</Code>` to read
 * (docs/api/protocol-s3.md section 4.4). A model that demands a code turns that into a parser
 * failure instead of a not-found.
 */
class S3ExceptionTest {
    @Test
    fun `carries the code and the message when the response had a body`() {
        val error =
            S3Exception(
                status = 404,
                code = "NoSuchKey",
                errorMessage = "The specified key does not exist.",
                requestId = "656c76696e6727732072657175657374",
                extendedRequestId = "Uuag1LuByRx9e6j5Onimru9pO4ZVKnJ2Qz7",
            )

        assertEquals(404, error.status)
        assertEquals("NoSuchKey", error.code)
        assertEquals("The specified key does not exist.", error.errorMessage)
        assertTrue("NoSuchKey" in error.message.orEmpty())
        assertTrue("404" in error.message.orEmpty())
    }

    @Test
    fun `is constructible without a code because a HEAD response has no body`() {
        val error = S3Exception(status = 404, requestId = "656c76696e67")

        assertNull(error.code)
        assertNull(error.errorMessage)
        assertEquals(404, error.status)
        assertTrue("404" in error.message.orEmpty())
    }

    @Test
    fun `always shows the request identifiers because AWS support asks for them`() {
        val error =
            S3Exception(
                status = 500,
                code = "InternalError",
                requestId = "REQ-1",
                extendedRequestId = "ID2-1",
            )

        assertTrue("REQ-1" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("ID2-1" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `says the identifiers are missing rather than printing null`() {
        val error = S3Exception(status = 403)

        assertTrue("null" !in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `rejects a status outside the range of an HTTP status code`() {
        val thrown =
            runCatching { S3Exception(status = 0) }
                .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException, "got $thrown")
    }

    @Test
    fun `names the codes the protocol document calls out`() {
        // A `when` over these should compile against constants rather than typed-out strings:
        // `NoSuchkey` looks right in review and never matches.
        assertEquals("NoSuchKey", S3ErrorCode.NO_SUCH_KEY)
        assertEquals("NoSuchBucket", S3ErrorCode.NO_SUCH_BUCKET)
        assertEquals("NoSuchUpload", S3ErrorCode.NO_SUCH_UPLOAD)
        assertEquals("AccessDenied", S3ErrorCode.ACCESS_DENIED)
        assertEquals("SignatureDoesNotMatch", S3ErrorCode.SIGNATURE_DOES_NOT_MATCH)
        assertEquals("EntityTooSmall", S3ErrorCode.ENTITY_TOO_SMALL)
        assertEquals("InvalidPart", S3ErrorCode.INVALID_PART)
        assertEquals("InvalidPartOrder", S3ErrorCode.INVALID_PART_ORDER)
        assertEquals("MissingContentLength", S3ErrorCode.MISSING_CONTENT_LENGTH)
        assertEquals("SlowDown", S3ErrorCode.SLOW_DOWN)
        assertEquals("InternalError", S3ErrorCode.INTERNAL_ERROR)
    }
}

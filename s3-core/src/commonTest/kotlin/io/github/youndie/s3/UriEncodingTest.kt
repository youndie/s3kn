package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one encoder that both the signer and the URL builder go through.
 *
 * S3 signs the path exactly as it appears on the wire — it neither normalises it nor encodes it a
 * second time (docs/spec/reference/botocore-auth.py:538). A second, slightly different encoder
 * anywhere in the library therefore shows up as `SignatureDoesNotMatch` with no explanation.
 *
 * Rules: docs/spec/reference/botocore-auth.py:268, expectations: docs/api/protocol-s3.md section 2.
 */
class UriEncodingTest {
    @Test
    fun `leaves unreserved characters alone`() {
        assertEquals("hello.txt", uriEncodeKey("hello.txt"))
        assertEquals("a~b", uriEncodeKey("a~b"))
        assertEquals("A-Z_a-z.0-9~", uriEncodeKey("A-Z_a-z.0-9~"))
    }

    // No `%` in a test name: Kotlin/Native rejects it the same way it rejects a comma, while the
    // JVM compiles it happily. Caught only by building the native target.
    @Test
    fun `encodes a space as percent-20 and never as plus`() {
        assertEquals("my%20dir/file.txt", uriEncodeKey("my dir/file.txt"))
    }

    @Test
    fun `encodes a plus sign so it cannot be read back as a space`() {
        assertEquals("a%2Bb", uriEncodeKey("a+b"))
    }

    @Test
    fun `keeps the slash in a key because it separates path segments`() {
        assertEquals("a//b", uriEncodeKey("a//b"))
        assertEquals("%D0%BA%D0%BB%D1%8E%D1%87/%D1%84%D0%B0%D0%B9%D0%BB", uriEncodeKey("ключ/файл"))
    }

    @Test
    fun `does not normalise dot segments`() {
        // The generic SigV4 turns `/./` into `/`; S3 does not, so neither does this.
        assertEquals("a/./b", uriEncodeKey("a/./b"))
        assertEquals("a/../b", uriEncodeKey("a/../b"))
    }

    @Test
    fun `encodes non-ascii as utf-8 bytes in upper-case hex`() {
        assertEquals("%D1%84%D0%B0%D0%B9%D0%BB.txt", uriEncodeKey("файл.txt"))
    }

    @Test
    fun `encodes a character outside the basic plane as four bytes`() {
        // A surrogate pair in the Kotlin string, one code point, four UTF-8 bytes. An encoder that
        // walks Char by Char produces two replacement characters here and signs something else.
        assertEquals("%F0%9F%99%82", uriEncodeKey("🙂"))
    }

    @Test
    fun `encodes an empty key to an empty string`() {
        assertEquals("", uriEncodeKey(""))
    }

    @Test
    fun `encodes the slash in a query component because there it is data`() {
        assertEquals("a%2Fb", uriEncodeQueryComponent("a/b"))
        assertEquals("prefix%2F", uriEncodeQueryComponent("prefix/"))
        assertEquals("a%20b", uriEncodeQueryComponent("a b"))
    }
}

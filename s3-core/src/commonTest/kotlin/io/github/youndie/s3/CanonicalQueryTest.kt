package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The third line of the canonical request.
 *
 * Rules: docs/spec/reference/botocore-auth.py:268. The expectations below are lifted from the
 * official vectors in docs/spec/aws-sig-v4-test-suite, whose case names are quoted per test — the
 * whole cases run through the signer in M2, these check the one line they all share.
 */
class CanonicalQueryTest {
    @Test
    fun `sorts parameters by encoded name`() {
        // get-vanilla-query-order-key-case: Param2=value2&Param1=value1
        assertEquals(
            "Param1=value1&Param2=value2",
            canonicalQueryString(listOf("Param2" to "value2", "Param1" to "value1")),
        )
    }

    @Test
    fun `sorts repeated names by encoded value`() {
        // get-vanilla-query-order-value: Param1=value2&Param1=value1
        assertEquals(
            "Param1=value1&Param1=value2",
            canonicalQueryString(listOf("Param1" to "value2", "Param1" to "value1")),
        )
    }

    @Test
    fun `orders by bytes and not case-insensitively`() {
        // 'B' is 0x42 and 'a' is 0x61, so the upper-case name comes first. A case-insensitive sort
        // would swap these two and produce a signature the server does not agree with.
        assertEquals("B=2&a=1", canonicalQueryString(listOf("a" to "1", "B" to "2")))
    }

    @Test
    fun `leaves unreserved characters alone`() {
        // get-vanilla-query-unreserved
        val unreserved = "-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        assertEquals("$unreserved=$unreserved", canonicalQueryString(listOf(unreserved to unreserved)))
    }

    @Test
    fun `encodes a non-ascii name as utf-8 bytes`() {
        // get-vanilla-utf8-query: ሴ=bar
        assertEquals("%E1%88%B4=bar", canonicalQueryString(listOf("ሴ" to "bar")))
    }

    @Test
    fun `sorts an encoded name ahead of a plain one because percent is 0x25`() {
        // Derived from get-vanilla-query-order-encoded, whose canonical query is
        // %E1%88%B4=Value1&Param=Value2&Param-3=Value3.
        assertEquals(
            "%E1%88%B4=Value1&Param=Value2&Param-3=Value3",
            canonicalQueryString(listOf("Param-3" to "Value3", "Param" to "Value2", "ሴ" to "Value1")),
        )
    }

    @Test
    fun `renders a parameter without a value as name and equals sign`() {
        // How `?uploads` and `?acl` reach the canonical request. The same string goes on the wire,
        // so that the signed form and the sent form cannot drift apart.
        assertEquals("uploads=", canonicalQueryString(listOf("uploads" to "")))
    }

    @Test
    fun `encodes a slash in a value because there it is data`() {
        assertEquals("prefix=a%2Fb", canonicalQueryString(listOf("prefix" to "a/b")))
    }

    @Test
    fun `renders no parameters as an empty line`() {
        assertEquals("", canonicalQueryString(emptyList()))
    }
}

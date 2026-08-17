package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The other half of [uriEncodeKey], needed because a listing asks S3 to encode the keys it returns.
 *
 * `encoding-type=url` is sent on every listing, so every key that comes back has been through this
 * (docs/api/protocol-s3.md, section 4.5). A decoder that disagrees with the encoder by one
 * character hands the caller a key it cannot then fetch.
 */
class UriDecodingTest {
    @Test
    fun `leaves text that was never encoded alone`() {
        assertEquals("hello.txt", uriDecode("hello.txt"))
        assertEquals("a~b-c_d.e", uriDecode("a~b-c_d.e"))
        assertEquals("", uriDecode(""))
    }

    @Test
    fun `decodes what the encoder produced`() {
        val keys =
            listOf(
                "hello.txt",
                "my dir/file.txt",
                "a+b",
                "a~b",
                "файл.txt",
                "🙂",
                "a//b",
                "100% sure",
                "test_file(3).png",
            )

        assertEquals(keys, keys.map { uriDecode(uriEncodeKey(it)) })
    }

    @Test
    fun `reassembles a character from several encoded bytes`() {
        // Decoding byte by byte into text would produce replacement characters here: one code point
        // arrives as four separate `%XX` groups.
        assertEquals("🙂", uriDecode("%F0%9F%99%82"))
        assertEquals("файл.txt", uriDecode("%D1%84%D0%B0%D0%B9%D0%BB.txt"))
    }

    @Test
    fun `leaves a plus sign alone unless asked to read it as a space`() {
        // Two readings, and both are needed. URI encoding gives `+` no meaning; form encoding reads
        // it as a space, and a listing response uses the form reading — the reference
        // implementation decodes those with `unquote_plus` (`botocore/compat.py:62`), and a live
        // server does return `my+dir` for a key holding `my dir`.
        assertEquals("a+b", uriDecode("a+b"))
        assertEquals("a b", uriDecode("a+b", plusIsSpace = true))
    }

    @Test
    fun `keeps an encoded plus a plus even when reading plus as a space`() {
        // What makes the form reading unambiguous: `+` is not unreserved, so a literal one always
        // arrives escaped.
        assertEquals("a+b", uriDecode(uriEncodeKey("a+b"), plusIsSpace = true))
    }

    @Test
    fun `accepts lower-case hex even though the encoder writes upper-case`() {
        assertEquals("a b", uriDecode("a%20b"))
        assertEquals("a b", uriDecode("a%2ob".replace('o', '0')))
    }

    @Test
    fun `rejects an escape that is cut short or not hex`() {
        // Silently keeping a broken escape would hand back a key that looks plausible and is wrong.
        assertFailsWith<IllegalArgumentException> { uriDecode("a%2") }
        assertFailsWith<IllegalArgumentException> { uriDecode("a%") }
        assertFailsWith<IllegalArgumentException> { uriDecode("a%zzb") }
    }
}

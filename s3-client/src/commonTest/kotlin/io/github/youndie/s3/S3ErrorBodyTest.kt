package io.github.youndie.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading an `<Error>` document.
 *
 * This runs on a failure path, so the thing it must never do is fail: a body that is not an S3
 * error — a proxy's HTML page, an empty response — has to come back as "nothing here" rather than
 * as a second exception hiding the first.
 *
 * Shape: docs/api/protocol-s3.md, section 5.
 */
class S3ErrorBodyTest {
    @Test
    fun `reads the fields of an error document`() {
        val parsed =
            parseErrorBody(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Error>
                  <Code>NoSuchKey</Code>
                  <Message>The specified key does not exist.</Message>
                  <Key>missing.txt</Key>
                  <RequestId>656c76696e6727732072657175657374</RequestId>
                  <HostId>Uuag1LuByRx9e6j5Onimru9pO4ZVKnJ2Qz7</HostId>
                </Error>
                """.trimIndent(),
            )

        assertEquals("NoSuchKey", parsed?.code)
        assertEquals("The specified key does not exist.", parsed?.message)
        assertEquals("656c76696e6727732072657175657374", parsed?.requestId)
        assertEquals("Uuag1LuByRx9e6j5Onimru9pO4ZVKnJ2Qz7", parsed?.hostId)
        assertNull(parsed?.canonicalRequest)
    }

    @Test
    fun `unescapes the newlines of the canonical request the server built`() {
        // S3 sends it with the newlines as `&#10;`. Leaving them escaped gives one long line that
        // cannot be diffed against anything, which is the entire point of carrying it.
        val parsed =
            parseErrorBody(
                "<Error><Code>SignatureDoesNotMatch</Code>" +
                    "<CanonicalRequest>GET&#10;/hello.txt&#10;&#10;host:example.com</CanonicalRequest>" +
                    "</Error>",
            )

        assertEquals(
            listOf("GET", "/hello.txt", "", "host:example.com"),
            parsed?.canonicalRequest?.lines(),
        )
    }

    @Test
    fun `resolves the ampersand last so an escaped entity survives`() {
        // `&amp;#10;` means the literal text `&#10;`, not a newline. Replacing `&amp;` first would
        // turn it into one.
        val parsed = parseErrorBody("<Error><Message>a &amp;#10; b &lt;c&gt; &quot;d&quot;</Message></Error>")

        assertEquals("a &#10; b <c> \"d\"", parsed?.message)
    }

    @Test
    fun `returns nothing for a body that is not an error document`() {
        assertNull(parseErrorBody(""))
        assertNull(parseErrorBody("<html><body>502 Bad Gateway</body></html>"))
        assertNull(parseErrorBody("not xml at all"))
    }

    @Test
    fun `returns nothing for the fields an error document leaves out`() {
        val parsed = parseErrorBody("<Error><Code>SlowDown</Code></Error>")

        assertEquals("SlowDown", parsed?.code)
        assertNull(parsed?.message)
        assertNull(parsed?.requestId)
    }

    @Test
    fun `survives an element that is opened and never closed`() {
        val parsed = parseErrorBody("<Error><Code>Truncated")

        assertNull(parsed?.code)
    }
}

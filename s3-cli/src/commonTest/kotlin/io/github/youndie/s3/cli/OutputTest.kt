package io.github.youndie.s3.cli

import io.github.youndie.s3.S3ObjectMetadata
import io.github.youndie.s3.S3ObjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the commands print (docs/features/feature-cli.md, section 7). */
class OutputTest {
    @Test
    fun `prints a listing line with the size in bytes`() {
        val line =
            listingLine(
                S3ObjectSummary(
                    key = "prod/d.dump",
                    size = 12_345_678,
                    eTag = "\"abc\"",
                    lastModified = "2026-08-14T03:00:12.000Z",
                    storageClass = "STANDARD",
                ),
            )

        // Bytes, not mebibytes: what reads this is `awk` in a backup job, and a rounded number
        // cannot be compared with the size of a file.
        assertEquals("2026-08-14T03:00:12.000Z  12345678  prod/d.dump", line)
    }

    @Test
    fun `prints a listing line for an object the server dated with nothing`() {
        val line = listingLine(S3ObjectSummary("k", 0, null, null, null))

        assertEquals("-  0  k", line)
    }

    @Test
    fun `marks a rolled-up prefix as one`() {
        // A prefix is not an object: a script that asks for it as a key gets a 404.
        assertEquals("PRE  prod/", prefixLine("prod/"))
    }

    @Test
    fun `prints what stat knows aligned by name`() {
        val lines =
            statLines(
                bucket = "backups",
                key = "prod/d.dump",
                metadata =
                    S3ObjectMetadata(
                        contentLength = 12_345_678,
                        eTag = "\"abc\"",
                        lastModified = "Thu, 14 Aug 2026 03:00:12 GMT",
                        contentType = "application/octet-stream",
                        userMetadata = mapOf("taken-by" to "cron", "database" to "shildik"),
                    ),
            )

        assertEquals(
            listOf(
                "key            s3://backups/prod/d.dump",
                "size           12345678",
                "last-modified  Thu, 14 Aug 2026 03:00:12 GMT",
                "etag           \"abc\"",
                "content-type   application/octet-stream",
                // User metadata sorted, so two runs of the same command print the same lines: the
                // order headers arrive in is not something a server promises.
                "meta-database  shildik",
                "meta-taken-by  cron",
            ),
            lines,
        )
    }

    @Test
    fun `prints a dash for what the server did not say`() {
        val lines = statLines("b", "k", S3ObjectMetadata(null, null, null, null, emptyMap()))

        assertTrue(lines.any { it.startsWith("size") && it.endsWith("-") }, lines.toString())
    }
}

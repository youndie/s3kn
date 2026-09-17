package io.github.youndie.s3.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * One side of a copy, as `docs/features/feature-cli.md` section 3 describes it.
 *
 * The case that matters most is the last one: a key is taken verbatim. Anything that trimmed,
 * normalised or re-encoded it here would be a second encoder, and the library has exactly one
 * on purpose (docs/research/research-architecture.md, decision R4).
 */
class LocationTest {
    @Test
    fun `reads a bucket and a key`() {
        assertEquals(Location.Remote("backups", "prod/d.dump"), parseLocation("s3://backups/prod/d.dump"))
    }

    @Test
    fun `reads a bucket on its own`() {
        assertEquals(Location.Remote("backups", ""), parseLocation("s3://backups"))
        assertEquals(Location.Remote("backups", ""), parseLocation("s3://backups/"))
    }

    @Test
    fun `reads a dash as the standard stream`() {
        assertEquals(Location.Standard, parseLocation("-"))
    }

    @Test
    fun `reads anything else as a path`() {
        assertEquals(Location.Local("/dump/d.dump"), parseLocation("/dump/d.dump"))
        assertEquals(Location.Local("./relative"), parseLocation("./relative"))
        // No filesystem is consulted: a path that does not exist parses exactly like one that does,
        // so the same command line means the same thing on every machine.
        assertEquals(Location.Local("s3-not-a-scheme"), parseLocation("s3-not-a-scheme"))
    }

    @Test
    fun `keeps the key exactly as written`() {
        val location = parseLocation("s3://backups/dumps/a b+c/д.dump") as Location.Remote

        assertEquals("dumps/a b+c/д.dump", location.key)
    }

    @Test
    fun `refuses a scheme with no bucket`() {
        assertFailsWith<CliUsageException> { parseLocation("s3:///prod/d.dump") }
    }

    @Test
    fun `refuses a bucket where an object is required`() {
        val failure = assertFailsWith<CliUsageException> { parseObjectLocation("s3://backups", "stat") }

        assertEquals(true, failure.message?.contains("addresses the bucket itself"))
    }

    @Test
    fun `refuses a local path where an object is required`() {
        assertFailsWith<CliUsageException> { parseObjectLocation("/dump/d.dump", "stat") }
    }
}

package io.github.youndie.s3.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The command line, as `docs/features/feature-cli.md` sections 2 and 3 describe it.
 *
 * Several of these tests are about refusals rather than results, and that is the point: a tool that
 * accepts a command line it does not understand does the wrong thing quietly, inside a job nobody
 * watches until the backup is needed.
 */
class ArgumentsTest {
    @Test
    fun `reads a copy up with its defaults`() {
        val command = parse("cp", "/dump/d.dump", "s3://backups/prod/d.dump")

        assertIs<Command.Copy>(command)
        assertEquals(Location.Local("/dump/d.dump"), command.source)
        assertEquals(Location.Remote("backups", "prod/d.dump"), command.destination)
        assertEquals(DEFAULT_PART_SIZE, command.partSize)
        assertEquals(DEFAULT_CONCURRENCY, command.concurrency)
        assertNull(command.contentType)
    }

    @Test
    fun `reads a copy down`() {
        val command = parse("cp", "s3://backups/prod/d.dump", "/dump/d.dump")

        assertIs<Command.Copy>(command)
        assertEquals(Location.Remote("backups", "prod/d.dump"), command.source)
        assertEquals(Location.Local("/dump/d.dump"), command.destination)
    }

    @Test
    fun `reads a copy from standard input`() {
        val command = parse("cp", "-", "s3://backups/prod/d.dump")

        assertIs<Command.Copy>(command)
        assertEquals(Location.Standard, command.source)
    }

    @Test
    fun `refuses a copy with no s3 side`() {
        val failure = assertFailsWith<CliUsageException> { parse("cp", "/a", "/b") }

        assertEquals(true, failure.message?.contains("neither of these is"))
    }

    @Test
    fun `refuses a copy with two s3 sides`() {
        val failure = assertFailsWith<CliUsageException> { parse("cp", "s3://b/one", "s3://b/two") }

        // CopyObject is not in the library, and pretending otherwise would mean downloading and
        // re-uploading behind the caller's back.
        assertEquals(true, failure.message?.contains("CopyObject"))
    }

    @Test
    fun `refuses a copy that names a bucket instead of an object`() {
        assertFailsWith<CliUsageException> { parse("cp", "/dump/d.dump", "s3://backups") }
    }

    @Test
    fun `refuses a copy with one side missing`() {
        assertFailsWith<CliUsageException> { parse("cp", "s3://backups/prod/d.dump") }
    }

    @Test
    fun `reads a part size written with a suffix`() {
        // A power of two, not a power of ten: `--part-size 5M` meaning 5 000 000 would sit just
        // under the floor S3 imposes on every part but the last.
        assertEquals(16L * 1024 * 1024, copy("--part-size", "16MiB").partSize)
        assertEquals(16L * 1024 * 1024, copy("--part-size", "16M").partSize)
        assertEquals(8L * 1024 * 1024, copy("--part-size", "8388608").partSize)
    }

    @Test
    fun `reads an option written with an equals sign`() {
        assertEquals("text/plain", copy("--content-type=text/plain").contentType)
    }

    @Test
    fun `refuses a part size that is not a number`() {
        assertFailsWith<CliUsageException> { copy("--part-size", "big") }
        assertFailsWith<CliUsageException> { copy("--concurrency", "0") }
    }

    @Test
    fun `reads global settings whatever their position`() {
        val before = parseArguments(listOf("--endpoint", "https://one", "ls", "s3://b"))
        val after = parseArguments(listOf("ls", "s3://b", "--endpoint", "https://one"))

        assertEquals("https://one", before.overrides.endpoint)
        assertEquals("https://one", after.overrides.endpoint)
    }

    @Test
    fun `reads a listing and its recursive form`() {
        val plain = parse("ls", "s3://backups/prod")
        val recursive = parse("ls", "-r", "s3://backups/prod")

        assertIs<Command.Listing>(plain)
        assertEquals("backups", plain.bucket)
        assertEquals("prod", plain.prefix)
        assertEquals(false, plain.recursive)
        assertEquals(true, (recursive as Command.Listing).recursive)
    }

    @Test
    fun `refuses an option the command does not have`() {
        val failure = assertFailsWith<CliUsageException> { parse("stat", "--recursive", "s3://b/k") }

        // Ignoring it would mean `cp --recursive dir s3://b/k` copies one file and reports success.
        assertEquals(true, failure.message?.contains("no option"))
    }

    @Test
    fun `refuses an unknown option and an unknown command`() {
        assertFailsWith<CliUsageException> { parse("cp", "--turbo", "/a", "s3://b/k") }
        assertFailsWith<CliUsageException> { parse("sync", "/a", "s3://b/k") }
    }

    @Test
    fun `refuses an option left without a value`() {
        assertFailsWith<CliUsageException> { parse("ls", "s3://b", "--endpoint") }
    }

    @Test
    fun `reads a presign with its expiry`() {
        val command = parse("presign", "--expires", "15m", "s3://backups/prod/d.dump")

        assertIs<Command.Presign>(command)
        assertEquals(15.minutes, command.expires)
        assertEquals("GET", command.method)
    }

    @Test
    fun `defaults a presign to an hour`() {
        assertEquals(1.hours, (parse("presign", "s3://b/k") as Command.Presign).expires)

        val bare = parse("presign", "--expires", "3600", "s3://b/k") as Command.Presign
        assertEquals(3600L, bare.expires.inWholeSeconds)
    }

    @Test
    fun `refuses a method it cannot sign`() {
        assertFailsWith<CliUsageException> { parse("presign", "--method", "PATCH", "s3://b/k") }
    }

    @Test
    fun `answers help and version before anything else`() {
        assertIs<Command.Help>(parse("--help"))
        assertIs<Command.Help>(parse("help"))
        assertIs<Command.Version>(parse("--version"))
        // Even when the rest of the line is nonsense: somebody asking for help is not asking for a
        // lecture about their arguments.
        assertIs<Command.Help>(parse("cp", "--help"))
    }

    @Test
    fun `refuses an empty command line`() {
        assertFailsWith<CliUsageException> { parseArguments(emptyList()) }
    }

    private fun parse(vararg argv: String): Command = parseArguments(argv.toList()).command

    private fun copy(vararg options: String): Command.Copy =
        parse("cp", *options, "/dump/d.dump", "s3://backups/prod/d.dump") as Command.Copy
}

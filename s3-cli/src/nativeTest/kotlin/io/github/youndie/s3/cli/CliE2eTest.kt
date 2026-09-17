package io.github.youndie.s3.cli

import io.github.youndie.s3.testing.E2E
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import platform.posix.setenv
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The binary's own path, end to end, against a real server.
 *
 * [runCli] is what `main` calls with one line in between, so these exercise argument parsing, the
 * environment, the engine, signing and the response — not a re-implementation of them that happens
 * to agree today. The library's own suite cannot find what these find: it never reads a file, never
 * reads the environment and never decides between a single `PUT` and a multipart upload.
 *
 * Run against MinIO from `docker-compose.yml`:
 *
 *     docker compose up -d --wait minio
 *     docker compose run --rm create-buckets
 *     S3_E2E_ENDPOINT=http://127.0.0.1:9000 ./gradlew :s3-cli:linuxX64Test
 *
 * Without `S3_E2E_ENDPOINT` these skip; in CI `S3_E2E_REQUIRED=1` makes a missing endpoint a
 * failure, because a skipped test reads exactly like a passing one.
 */
class CliE2eTest {
    @Test
    fun `copies a file up and back down again`() {
        val prepared = prepare() ?: return
        val content = ByteArray(2048) { (it % 251).toByte() }
        val source = temporaryFile("source", content)
        val target = temporaryPath("target")
        val key = "cli/roundtrip-${prepared.stamp}.bin"

        assertEquals(EXIT_OK, runCli(listOf("cp", source.toString(), "s3://${E2E.bucket}/$key")))
        assertEquals(EXIT_OK, runCli(listOf("stat", "s3://${E2E.bucket}/$key")))
        assertEquals(EXIT_OK, runCli(listOf("cp", "s3://${E2E.bucket}/$key", target.toString())))

        // Byte for byte. A dump that arrives two bytes short restores halfway and then fails with a
        // message about memory, which is how this was noticed in the first place.
        assertContentEquals(content, read(target))
        assertEquals(EXIT_OK, runCli(listOf("rm", "s3://${E2E.bucket}/$key")))
    }

    @Test
    fun `copies a file larger than one part`() {
        val prepared = prepare() ?: return
        // Six mebibytes against a five-mebibyte part: two parts, the second a remainder. This is
        // the path a real dump takes, and nothing in the library's suite reaches it from a file.
        val content = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        val source = temporaryFile("large", content)
        val target = temporaryPath("large-target")
        val key = "cli/multipart-${prepared.stamp}.bin"

        assertEquals(
            EXIT_OK,
            runCli(listOf("cp", "--part-size", "5M", source.toString(), "s3://${E2E.bucket}/$key")),
        )
        assertEquals(EXIT_OK, runCli(listOf("cp", "s3://${E2E.bucket}/$key", target.toString())))

        assertContentEquals(content, read(target))
        assertEquals(EXIT_OK, runCli(listOf("rm", "s3://${E2E.bucket}/$key")))
    }

    @Test
    fun `lists what it has just uploaded`() {
        val prepared = prepare() ?: return
        val source = temporaryFile("listed", "listed".encodeToByteArray())
        val key = "cli/listed-${prepared.stamp}.txt"

        assertEquals(EXIT_OK, runCli(listOf("cp", source.toString(), "s3://${E2E.bucket}/$key")))
        assertEquals(EXIT_OK, runCli(listOf("ls", "-r", "s3://${E2E.bucket}/cli/")))
        assertEquals(EXIT_OK, runCli(listOf("ls", "s3://${E2E.bucket}/")))
        assertEquals(EXIT_OK, runCli(listOf("rm", "s3://${E2E.bucket}/$key")))
    }

    @Test
    fun `reports a missing object as a failure`() {
        prepare() ?: return

        // Not a usage failure: the command line is fine, the object is not there. The two exit
        // codes are read by different people (docs/features/feature-cli.md, section 2).
        assertEquals(EXIT_FAILED, runCli(listOf("stat", "s3://${E2E.bucket}/cli/never-existed")))
    }

    @Test
    fun `reports a bad endpoint as a failure and not as a usage error`() {
        prepare() ?: return

        val code = runCli(listOf("--endpoint", "http://127.0.0.1:1", "stat", "s3://${E2E.bucket}/anything"))

        assertEquals(EXIT_FAILED, code)
    }

    @Test
    fun `refuses a copy with no s3 side before opening anything`() {
        prepare() ?: return

        assertEquals(EXIT_USAGE, runCli(listOf("cp", "/dump/a", "/dump/b")))
    }

    @Test
    fun `prints a presigned url without an endpoint being reachable`() {
        prepare() ?: return

        // No request is sent, so an endpoint that refuses connections changes nothing.
        val code =
            runCli(
                listOf("--endpoint", "http://127.0.0.1:1", "presign", "--expires", "15m", "s3://${E2E.bucket}/k"),
            )

        assertEquals(EXIT_OK, code)
    }

    private class Prepared(
        /** Makes this run's keys its own, so two runs against one bucket cannot collide. */
        val stamp: String,
    )

    /**
     * Puts the credentials where the binary reads them, or says there is no server to talk to.
     *
     * `setenv` rather than a test-only way in: the binary reads `getenv`, and a hook that bypassed
     * it would leave the one line that matters — the one a manifest gets wrong — untested.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun prepare(): Prepared? {
        val endpoint = E2E.endpointOrSkip() ?: return null
        setenv("S3_ENDPOINT", endpoint, 1)
        setenv("AWS_ACCESS_KEY_ID", E2E.accessKey, 1)
        setenv("AWS_SECRET_ACCESS_KEY", E2E.secretKey, 1)
        setenv("AWS_REGION", E2E.region, 1)
        // MinIO is reached as 127.0.0.1, which no bucket can be a DNS label of.
        setenv("S3_ADDRESSING", "path", 1)
        return Prepared(token())
    }

    private fun temporaryPath(name: String): Path = Path(SystemTemporaryDirectory, "s3kn-cli-$name-${token()}")

    /** A name nobody else is using. Random rather than a timestamp: the clock is not ours to read. */
    private fun token(): String = Random.nextUInt().toString(radix = 16)

    private fun temporaryFile(
        name: String,
        content: ByteArray,
    ): Path {
        val path = temporaryPath(name)
        SystemFileSystem.sink(path).buffered().use { it.write(content) }
        return path
    }

    private fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
}

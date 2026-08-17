package io.github.youndie.s3

import io.github.youndie.s3.testing.E2E
import io.github.youndie.s3.testing.environmentVariable
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Multipart upload against a real server.
 *
 * Two things here exist only because a live server can answer them and a document cannot: what the
 * minimum part size really is, and whether uploading parts at once is any faster than one at a
 * time.
 *
 * Run against MinIO from `docker-compose.yml`; without `S3_E2E_ENDPOINT` these skip.
 */
class S3MultipartE2eTest {
    @Test
    fun `uploads an object in parts and reads it back whole`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/multipart/whole.bin"
            val body = ByteArray((PART_SIZE * 2 + 1024).toInt()) { (it % 251).toByte() }

            val eTag =
                fixture.client.putMultipart(
                    bucket = E2E.bucket,
                    key = key,
                    body = ByteReadChannel(body),
                    partSize = PART_SIZE,
                    concurrency = 2,
                )

            assertNotNull(eTag)
            // A multipart object's ETag is not the MD5 of its contents, so it carries a `-N` suffix
            // saying how many parts went into it. Worth pinning down: code that compares an ETag
            // with a content hash silently stops matching once an upload becomes multipart.
            assertTrue(eTag.contains("-3"), eTag)
            assertEquals(body.size.toLong(), fixture.client.head(E2E.bucket, key).contentLength)
            assertContentEquals(body, fixture.client.get(E2E.bucket, key) { it.body.readRemaining().readByteArray() })
        }

    @Test
    fun `refuses to assemble parts that are too small`() =
        runTest {
            // The limit the API model does not state — it points at the user guide instead
            // (docs/spec/s3-service-2.json:1604). Asked of the server rather than quoted.
            val fixture = fixture() ?: return@runTest
            val key = "e2e/multipart/too-small.bin"
            val upload = fixture.client.createMultipartUpload(E2E.bucket, key)
            val small = ByteArray(1024 * 1024)

            val parts =
                listOf(
                    fixture.client.uploadPart(upload, 1, small),
                    fixture.client.uploadPart(upload, 2, small),
                )

            val failure =
                assertFailsWith<S3Exception> { fixture.client.completeMultipartUpload(upload, parts) }
            assertEquals("EntityTooSmall", failure.code, failure.message)

            fixture.client.abortMultipartUpload(upload)
        }

    @Test
    fun `forgets the parts of an aborted upload`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/multipart/aborted.bin"
            val upload = fixture.client.createMultipartUpload(E2E.bucket, key)
            fixture.client.uploadPart(upload, 1, ByteArray(PART_SIZE.toInt()))

            fixture.client.abortMultipartUpload(upload)

            // Nothing was assembled, so the object never existed.
            assertEquals(404, assertFailsWith<S3Exception> { fixture.client.head(E2E.bucket, key) }.status)
            // And the upload is gone, so completing it now cannot work either.
            assertFailsWith<S3Exception> {
                fixture.client.completeMultipartUpload(upload, listOf(S3CompletedPart(1, "\"x\"")))
            }
        }

    @Test
    fun `measures whether uploading parts at once helps`() =
        runTest {
            // Risk 5 of the research: the curl engine does all its I/O on one thread, so parts sent
            // at once may queue behind each other rather than overlap. Off by default — it moves
            // real megabytes and the numbers depend on the machine — and it asserts only a
            // direction, never a duration: a threshold in milliseconds measures the runner.
            val fixture = fixture() ?: return@runTest
            if (environmentVariable("S3_E2E_BENCH") != "1") return@runTest

            val body = ByteArray((PART_SIZE * 4).toInt()) { (it % 251).toByte() }
            val timings =
                listOf(1, 4).associateWith { concurrency ->
                    val mark = TimeSource.Monotonic.markNow()
                    fixture.client.putMultipart(
                        bucket = E2E.bucket,
                        key = "e2e/multipart/bench-$concurrency.bin",
                        body = ByteReadChannel(body),
                        partSize = PART_SIZE,
                        concurrency = concurrency,
                    )
                    mark.elapsedNow()
                }

            timings.forEach { (concurrency, elapsed) ->
                println("multipart: ${body.size / 1024 / 1024} MiB, concurrency $concurrency -> $elapsed")
            }
            assertTrue(
                timings.getValue(4) < timings.getValue(1) * 2,
                "four at a time took more than twice as long as one at a time: $timings",
            )
        }

    private fun assertContentEquals(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.size, actual.size, "sizes differ")
        val firstDifference = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals(null, firstDifference, "bytes differ at $firstDifference")
    }

    private class Fixture(
        val client: S3Client,
    )

    private fun fixture(): Fixture? {
        val endpoint = E2E.endpointOrSkip() ?: return null
        return Fixture(
            S3Client(
                config =
                    S3Config(
                        endpoint = S3Endpoint.parse(endpoint),
                        region = E2E.region,
                        credentials = S3Credentials(E2E.accessKey, E2E.secretKey),
                        addressingStyle = AddressingStyle.PATH,
                        allowUnsignedPayloadOverHttp = true,
                    ),
                http = realHttpClient(),
            ),
        )
    }

    private companion object {
        /** The smallest S3 accepts for a non-final part, so a test can make three parts cheaply. */
        val PART_SIZE = S3MultipartLimits.MIN_PART_SIZE
    }
}

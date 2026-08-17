package io.github.youndie.s3

import io.github.youndie.s3.sigv4.S3Signer
import io.github.youndie.s3.testing.E2E
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The first requests this library actually sends.
 *
 * `head` is the cheapest operation there is, and it exercises the whole chain at once:
 * configuration, addressing, key encoding, signing, the engine, the connection, and the reading of
 * an error that has no body. Until it passes, a failure anywhere else is indistinguishable from a
 * signing bug.
 *
 * Objects are put in place through presigned URLs rather than through the client, because `put`
 * does not exist yet — which makes the presigning of M3 load-bearing rather than decorative.
 *
 * Run against MinIO from `docker-compose.yml`:
 *
 *     docker compose up -d --wait
 *     S3_E2E_ENDPOINT=http://127.0.0.1:9000 ./gradlew :s3-client:linuxX64Test
 *
 * Without `S3_E2E_ENDPOINT` these skip. In CI `S3_E2E_REQUIRED=1` turns a missing endpoint into a
 * failure, because a skipped test reads exactly like a passing one.
 */
class S3ClientE2eTest {
    @Test
    fun `reads back the metadata of an object it just uploaded`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/hello.txt"
            fixture.upload(key, "hello")

            val metadata = fixture.client.head(E2E.bucket, key)

            assertEquals(5, metadata.contentLength)
            assertNotNull(metadata.eTag)
        }

    @Test
    fun `reports a missing key as a 404 that carries no error code`() =
        runTest {
            val fixture = fixture() ?: return@runTest

            val failure = assertFailsWith<S3Exception> { fixture.client.head(E2E.bucket, "e2e/definitely-missing") }

            assertEquals(404, failure.status)
            // A HEAD response has no body, so there is no `<Code>` to read. Anything claiming one
            // here would be a guess (docs/api/protocol-s3.md, section 4.4).
            assertEquals(null, failure.code)
        }

    @Test
    fun `reports a missing bucket the same way as a missing key`() =
        runTest {
            // Worth pinning down rather than assuming: with no body there is nothing to tell the
            // two apart, so a caller cannot treat 404 as "the object is gone" on its own.
            val fixture = fixture() ?: return@runTest

            val failure = assertFailsWith<S3Exception> { fixture.client.head("s3kn-no-such-bucket", "any") }

            assertEquals(404, failure.status)
            assertEquals(null, failure.code)
        }

    @Test
    fun `handles the keys that break a naive encoder`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            // `a//b` and `a/./b` are missing on purpose, and not because they work. MinIO refuses
            // both outright — `XMinioInvalidObjectName` and `XMinioInvalidResourceName` — while
            // real S3 accepts them, so this server cannot check them either way. Worse, on the
            // curl engine `a/./b` never even reaches the server intact: see the research, fact 1.9.
            val keys =
                listOf(
                    "e2e/my dir/file.txt",
                    "e2e/a+b",
                    "e2e/a~b",
                    "e2e/файл.txt",
                    "e2e/🙂",
                    "e2e/100% sure",
                    "e2e/a b c/d~e+f",
                )

            val failures =
                keys.mapNotNull { key ->
                    runCatching {
                        fixture.upload(key, key)
                        val metadata = fixture.client.head(E2E.bucket, key)
                        assertEquals(key.encodeToByteArray().size.toLong(), metadata.contentLength)
                    }.exceptionOrNull()?.let { "$key -> $it" }
                }

            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    @Test
    fun `serves an object through a presigned get`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/presigned.txt"
            fixture.upload(key, "presigned body")

            val url = fixture.signer.presign("GET", E2E.bucket, key, expires = 5.minutes)
            val response = fixture.http.get(url)

            assertTrue(response.status.isSuccess(), "${response.status}: ${response.bodyAsText()}")
            assertEquals("presigned body", response.bodyAsText())
        }

    @Test
    fun `returns a diagnosable error when a signature is tampered with`() =
        runTest {
            // The one path that produces a `SignatureDoesNotMatch` body without needing an
            // operation that has one: flip the last character of a presigned signature.
            val fixture = fixture() ?: return@runTest
            val url = fixture.signer.presign("GET", E2E.bucket, "e2e/hello.txt", expires = 5.minutes)
            val tampered = url.dropLast(1) + if (url.last() == 'a') 'b' else 'a'

            val response = fixture.http.get(tampered)
            val body = response.bodyAsText()
            val parsed = parseErrorBody(body)

            assertEquals(403, response.status.value)
            assertEquals("SignatureDoesNotMatch", parsed?.code, body)
            assertNotNull(parsed?.requestId, body)
        }

    private class Fixture(
        val client: S3Client,
        val signer: S3Signer,
        val http: io.ktor.client.HttpClient,
    ) {
        suspend fun upload(
            key: String,
            body: String,
        ) {
            val url = signer.presign("PUT", E2E.bucket, key, expires = 5.minutes)
            val response = http.put(url) { setBody(body) }
            check(response.status.isSuccess()) { "upload of $key failed: ${response.status} ${response.bodyAsText()}" }
        }
    }

    private fun fixture(): Fixture? {
        val endpoint = E2E.endpointOrSkip() ?: return null
        val config =
            S3Config(
                endpoint = S3Endpoint.parse(endpoint),
                region = E2E.region,
                credentials = S3Credentials(E2E.accessKey, E2E.secretKey),
                // 127.0.0.1 cannot carry a bucket as a DNS label, so a local server is path-style.
                addressingStyle = AddressingStyle.PATH,
            )
        val http = realHttpClient()
        return Fixture(S3Client(config, http), S3Signer(config), http)
    }
}

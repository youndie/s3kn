package io.github.youndie.s3

import io.github.youndie.s3.sigv4.S3Signer
import io.github.youndie.s3.testing.E2E
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
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
 * Some objects are put in place through presigned URLs rather than through `put`, which keeps the
 * presigning exercised by something other than a test written for it.
 *
 * Run against MinIO from `docker-compose.yml`:
 *
 *     docker compose up -d --wait minio
 *     docker compose run --rm create-buckets
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

    @Test
    fun `stores an object and reads it back`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/put-get.txt"

            val eTag = fixture.client.put(E2E.bucket, key, "stored by put".encodeToByteArray(), "text/plain")
            val body = fixture.client.get(E2E.bucket, key) { it.body.readRemaining().readByteArray() }

            assertNotNull(eTag)
            assertEquals("stored by put", body.decodeToString())
            assertEquals("text/plain", fixture.client.head(E2E.bucket, key).contentType)
        }

    @Test
    fun `stores an object streamed with a stated length`() =
        runTest {
            // The path that matters for large objects, and the one that cannot be checked with a
            // ByteArray: the body is signed as UNSIGNED-PAYLOAD and the length has to be stated,
            // or the engine falls back to chunked and S3 answers 411.
            val fixture = fixture() ?: return@runTest
            val key = "e2e/streamed.bin"
            val body = "streamed through a channel"

            fixture.client.put(
                bucket = E2E.bucket,
                key = key,
                body = ByteReadChannel(body),
                contentLength = body.encodeToByteArray().size.toLong(),
            )

            assertEquals(
                body,
                fixture.client
                    .get(E2E.bucket, key) { it.body.readRemaining().readByteArray() }
                    .decodeToString(),
            )
        }

    @Test
    fun `reads part of an object with a range`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/ranged.txt"
            fixture.client.put(E2E.bucket, key, "0123456789".encodeToByteArray())

            val part =
                fixture.client.get(E2E.bucket, key, range = 2L..5L) {
                    it.body.readRemaining().readByteArray()
                }

            assertEquals("2345", part.decodeToString())
        }

    @Test
    fun `names the error of a get that found nothing`() =
        runTest {
            // Unlike HEAD, a failed GET carries a body, so this is where the error code comes from.
            val fixture = fixture() ?: return@runTest

            val failure =
                assertFailsWith<S3Exception> {
                    fixture.client.get(E2E.bucket, "e2e/definitely-missing") { it.contentLength }
                }

            assertEquals(404, failure.status)
            assertEquals("NoSuchKey", failure.code)
            assertNotNull(failure.errorMessage)
            assertNotNull(failure.requestId)
        }

    @Test
    fun `removes an object and then reports it gone`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val key = "e2e/to-delete.txt"
            fixture.client.put(E2E.bucket, key, "temporary".encodeToByteArray())

            fixture.client.delete(E2E.bucket, key)

            assertEquals(404, assertFailsWith<S3Exception> { fixture.client.head(E2E.bucket, key) }.status)
        }

    @Test
    fun `treats removing something that is not there as success`() =
        runTest {
            // Contradicts the intuition that deleting a missing thing is an error, which is exactly
            // why it is pinned down here (docs/api/protocol-s3.md, section 4.3).
            val fixture = fixture() ?: return@runTest

            fixture.client.delete(E2E.bucket, "e2e/never-existed")
        }

    @Test
    fun `minio answers 411 when a body arrives without a stated length`() =
        runTest {
            // Why `contentLength` is a required parameter of `put` rather than a convenience. Sent
            // here deliberately without one, through a presigned URL, so a server's own answer is
            // on record instead of a claim quoted from the API model.
            //
            // **Whose answer, though.** This suite runs against MinIO (docker-compose.yml), and
            // 411 is MinIO's. S3 is not known to agree: `ceph/s3-tests` sends the same shape —
            // botocore drops Content-Length entirely once Transfer-Encoding is added before
            // signing — and expects 200, unmarked as failing on AWS. So this pins the behaviour of
            // the server it talks to, and the required parameter is justified by being portable
            // rather than by what S3 does. Pointed at a server that follows the suite, this case
            // is expected to fail; that is a disagreement about servers, not about this client.
            val fixture = fixture() ?: return@runTest
            val url = fixture.signer.presign("PUT", E2E.bucket, "e2e/no-length.bin", expires = 5.minutes)

            val response = fixture.http.put(url) { setBody(ByteReadChannel("no length stated")) }

            assertEquals(411, response.status.value, response.bodyAsText())
            assertEquals("MissingContentLength", parseErrorBody(response.bodyAsText())?.code)
        }

    @Test
    fun `lists what it stored under a prefix`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val prefix = "e2e/list/plain/"
            listOf("a.txt", "b.txt", "c.txt").forEach {
                fixture.client.put(E2E.bucket, prefix + it, it.encodeToByteArray())
            }

            val page = fixture.client.listPage(E2E.bucket, prefix = prefix)

            assertEquals(listOf("a.txt", "b.txt", "c.txt").map { prefix + it }, page.objects.map { it.key })
            assertEquals(3, page.keyCount)
            assertTrue(!page.isTruncated)
        }

    @Test
    fun `rolls keys up into common prefixes when given a delimiter`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val prefix = "e2e/list/tree/"
            listOf("top.txt", "one/a.txt", "one/b.txt", "two/c.txt").forEach {
                fixture.client.put(E2E.bucket, prefix + it, it.encodeToByteArray())
            }

            val page = fixture.client.listPage(E2E.bucket, prefix = prefix, delimiter = "/")

            assertEquals(listOf(prefix + "top.txt"), page.objects.map { it.key })
            assertEquals(listOf(prefix + "one/", prefix + "two/"), page.commonPrefixes)
        }

    @Test
    fun `walks a listing that does not fit in one page`() =
        runTest {
            val fixture = fixture() ?: return@runTest
            val prefix = "e2e/list/paged/"
            val keys = (1..5).map { "$prefix$it.txt" }
            keys.forEach { fixture.client.put(E2E.bucket, it, "x".encodeToByteArray()) }

            val pages = fixture.client.list(E2E.bucket, prefix = prefix, maxKeys = 2).toList()

            assertTrue(pages.size >= 3, "expected more than one page, got ${'$'}{pages.size}")
            assertEquals(keys.sorted(), pages.flatMap { page -> page.objects.map { it.key } }.sorted())
        }

    @Test
    fun `lists a key whose name XML alone could not carry`() =
        runTest {
            // Why `encoding-type=url` is unconditional: an object key may hold any Unicode
            // character, and some cannot appear in an XML 1.0 document at all
            // (docs/spec/s3-service-2.json, shapes.EncodingType).
            val fixture = fixture() ?: return@runTest
            val prefix = "e2e/list/awkward/"
            // Two of these are interesting before Unicode even enters into it. `<`, `>`, `&` and
            // `"` have to survive being written into an XML document and read back out. And the
            // pair `my dir` / `a+b` is what makes the decoding unambiguous: a space comes back as
            // `+`, so a literal `+` had better come back as `%2B` — asserted here rather than
            // assumed.
            val keys = listOf("${prefix}my dir/файл.txt", "$prefix🙂", "${prefix}a<b>&c\"d\"", "${prefix}a+b")
            keys.forEach { fixture.client.put(E2E.bucket, it, "x".encodeToByteArray()) }

            val page = fixture.client.listPage(E2E.bucket, prefix = prefix)

            assertEquals(keys.sorted(), page.objects.map { it.key }.sorted())
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
                // The local server speaks plain HTTP, and a streamed body cannot be hashed. Saying
                // so is the point: the same setting is what a developer running MinIO needs, and it
                // has to be stated rather than assumed.
                allowUnsignedPayloadOverHttp = true,
            )
        val http = realHttpClient()
        return Fixture(S3Client(config, http), S3Signer(config), http)
    }
}

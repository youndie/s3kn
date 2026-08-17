package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a listing puts on the wire, and how the pages are walked.
 *
 * Contract: docs/api/protocol-s3.md, section 4.5.
 */
class S3ListRequestTest {
    @Test
    fun `asks for version two of the listing and for encoded keys`() =
        runTest {
            // `encoding-type=url` is sent unconditionally. Without it a key holding a byte that
            // XML 1.0 cannot represent breaks the document itself, and it breaks it in the user's
            // bucket rather than in a test (docs/spec/s3-service-2.json, shapes.EncodingType).
            val request = capture { client -> client.listPage("photos") }

            assertEquals("encoding-type=url&list-type=2", request.url.encodedQuery)
        }

    @Test
    fun `passes the listing parameters through encoded`() =
        runTest {
            val request =
                capture { client ->
                    client.listPage(
                        bucket = "photos",
                        prefix = "a b/",
                        delimiter = "/",
                        maxKeys = 10,
                        startAfter = "a b/c",
                    )
                }

            val query = request.url.encodedQuery
            assertTrue("prefix=a%20b%2F" in query, query)
            assertTrue("delimiter=%2F" in query, query)
            assertTrue("max-keys=10" in query, query)
            assertTrue("start-after=a%20b%2Fc" in query, query)
        }

    @Test
    fun `walks the pages until the listing says it is done`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client(
                    MockEngine { request ->
                        requests += request
                        respond(page(requests.size), headers = headersOf("Content-Type", "application/xml"))
                    },
                )

            val pages = client.list("photos").toList()

            assertEquals(3, pages.size)
            assertEquals(listOf("k1", "k2", "k3"), pages.flatMap { page -> page.objects.map { it.key } })
            // The first request carries no token; each later one carries the previous page's.
            assertTrue("continuation-token" !in requests[0].url.encodedQuery)
            assertTrue("continuation-token=token1" in requests[1].url.encodedQuery)
            assertTrue("continuation-token=token2" in requests[2].url.encodedQuery)
        }

    @Test
    fun `stops requesting pages when the collector stops asking`() =
        runTest {
            // The reason a listing is a flow: a bucket with a million keys must not be fetched to
            // answer a question about its first page.
            var requestCount = 0
            val client =
                client(
                    MockEngine { request ->
                        requestCount++
                        respond(page(requestCount), headers = headersOf("Content-Type", "application/xml"))
                    },
                )

            val pages = client.list("photos").take(1).toList()

            assertEquals(1, pages.size)
            assertEquals(1, requestCount)
        }

    @Test
    fun `sends start-after only on the first page`() =
        runTest {
            // Afterwards the token is the position. Sending both would be contradictory, and S3
            // would have to pick one.
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client(
                    MockEngine { request ->
                        requests += request
                        respond(page(requests.size), headers = headersOf("Content-Type", "application/xml"))
                    },
                )

            client.list("photos", startAfter = "a/first").toList()

            assertTrue("start-after=a%2Ffirst" in requests[0].url.encodedQuery)
            assertTrue(requests.drop(1).none { "start-after" in it.url.encodedQuery })
        }

    private fun page(number: Int): String =
        if (number < 3) {
            "<ListBucketResult><KeyCount>1</KeyCount><IsTruncated>true</IsTruncated>" +
                "<NextContinuationToken>token$number</NextContinuationToken>" +
                "<Contents><Key>k$number</Key><Size>1</Size></Contents></ListBucketResult>"
        } else {
            "<ListBucketResult><KeyCount>1</KeyCount><IsTruncated>false</IsTruncated>" +
                "<Contents><Key>k$number</Key><Size>1</Size></Contents></ListBucketResult>"
        }

    private suspend fun capture(call: suspend (S3Client) -> Unit): HttpRequestData {
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                respond("<ListBucketResult><KeyCount>0</KeyCount></ListBucketResult>")
            }
        call(client(engine))
        return requireNotNull(captured)
    }

    private fun client(engine: MockEngine): S3Client =
        S3Client(
            config =
                S3Config(
                    endpoint = S3Endpoint.parse("http://localhost:9000"),
                    region = "us-east-1",
                    credentials = S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
                    addressingStyle = AddressingStyle.PATH,
                    clock =
                        object : Clock {
                            override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160L)
                        },
                ),
            http = HttpClient(engine),
        )
}

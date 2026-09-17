package io.github.youndie.s3.cli

import io.github.youndie.s3.AddressingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Environment into configuration, as `docs/features/feature-cli.md` section 3 tabulates it.
 *
 * The environment arrives as a function rather than being read from the process, so these exercise
 * the same code the binary runs instead of a copy of it that is right today.
 */
class SettingsTest {
    @Test
    fun `builds a configuration from the environment alone`() {
        val config =
            buildConfig(
                Overrides(),
                environmentOf(
                    "S3_ENDPOINT" to "https://s3.example.com",
                    "AWS_ACCESS_KEY_ID" to "key",
                    "AWS_SECRET_ACCESS_KEY" to "secret",
                ),
            )

        assertEquals("https://s3.example.com", config.endpoint.origin)
        assertEquals("us-east-1", config.region)
        // Path style by default: the endpoints this tool exists for are MinIO and gateways
        // addressed by a bare host, and neither can carry a bucket as a DNS label.
        assertEquals(AddressingStyle.PATH, config.addressingStyle)
        assertEquals("key", config.credentials.accessKeyId)
        assertNull(config.credentials.sessionToken)
        // A streamed body is never sent, so the escape hatch for signing one stays shut.
        assertEquals(false, config.allowUnsignedPayloadOverHttp)
    }

    @Test
    fun `accepts the endpoint under either name`() {
        val config =
            buildConfig(
                Overrides(),
                environmentOf(
                    "AWS_ENDPOINT_URL" to "https://s3.example.com",
                    "AWS_ACCESS_KEY_ID" to "key",
                    "AWS_SECRET_ACCESS_KEY" to "secret",
                ),
            )

        assertEquals("https://s3.example.com", config.endpoint.origin)
    }

    @Test
    fun `lets a flag win over the environment`() {
        val config =
            buildConfig(
                Overrides(endpoint = "https://other.example.com", region = "eu-central-1", addressing = "virtual"),
                environmentOf(
                    "S3_ENDPOINT" to "https://s3.example.com",
                    "AWS_REGION" to "us-east-1",
                    "AWS_ACCESS_KEY_ID" to "key",
                    "AWS_SECRET_ACCESS_KEY" to "secret",
                ),
            )

        assertEquals("https://other.example.com", config.endpoint.origin)
        assertEquals("eu-central-1", config.region)
        assertEquals(AddressingStyle.VIRTUAL_HOSTED, config.addressingStyle)
    }

    @Test
    fun `carries a session token when there is one`() {
        val config =
            buildConfig(
                Overrides(),
                environmentOf(
                    "S3_ENDPOINT" to "https://s3.example.com",
                    "AWS_ACCESS_KEY_ID" to "key",
                    "AWS_SECRET_ACCESS_KEY" to "secret",
                    "AWS_SESSION_TOKEN" to "token",
                ),
            )

        assertEquals("token", config.credentials.sessionToken)
    }

    @Test
    fun `names the variable that is missing`() {
        val failure =
            assertFailsWith<CliUsageException> {
                buildConfig(
                    Overrides(),
                    environmentOf(
                        "S3_ENDPOINT" to "https://s3.example.com",
                        "AWS_ACCESS_KEY_ID" to "key",
                    ),
                )
            }

        // The name of the variable, not "configuration error": the fix is one line in a manifest,
        // and whoever reads the job's log should not have to guess which line.
        assertEquals(true, failure.message?.contains("AWS_SECRET_ACCESS_KEY"))
    }

    @Test
    fun `refuses an endpoint with no scheme`() {
        val failure =
            assertFailsWith<CliUsageException> {
                buildConfig(
                    Overrides(endpoint = "s3.example.com"),
                    environmentOf("AWS_ACCESS_KEY_ID" to "key", "AWS_SECRET_ACCESS_KEY" to "secret"),
                )
            }

        assertEquals(true, failure.message?.contains("s3.example.com"))
    }

    @Test
    fun `refuses an addressing style it does not know`() {
        assertFailsWith<CliUsageException> {
            buildConfig(
                Overrides(addressing = "dns"),
                environmentOf(
                    "S3_ENDPOINT" to "https://s3.example.com",
                    "AWS_ACCESS_KEY_ID" to "key",
                    "AWS_SECRET_ACCESS_KEY" to "secret",
                ),
            )
        }
    }

    @Test
    fun `treats an empty variable as unset`() {
        // What a secretKeyRef to a key that is not there leaves behind. Accepting it would turn a
        // broken manifest into SignatureDoesNotMatch, which names nothing.
        val failure =
            assertFailsWith<CliUsageException> {
                buildConfig(
                    Overrides(),
                    environmentOf(
                        "S3_ENDPOINT" to "https://s3.example.com",
                        "AWS_ACCESS_KEY_ID" to "key",
                        "AWS_SECRET_ACCESS_KEY" to "",
                    ),
                )
            }

        assertEquals(true, failure.message?.contains("AWS_SECRET_ACCESS_KEY"))
    }

    /**
     * The environment a test hands over, verbatim.
     *
     * Verbatim matters: an earlier version of this helper dropped empty values itself, which made
     * the test below pass whatever the production code did — it was asserting the helper.
     */
    private fun environmentOf(vararg entries: Pair<String, String>): (String) -> String? {
        val values = entries.toMap()
        return { name -> values[name] }
    }
}

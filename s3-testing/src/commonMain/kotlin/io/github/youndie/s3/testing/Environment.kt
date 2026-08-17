package io.github.youndie.s3.testing

/**
 * Reads an environment variable, or `null` when it is unset or empty.
 *
 * Tests that need a real server are switched on this way. There is no common way to read the
 * environment in Kotlin Multiplatform, which is why this exists at all.
 */
public expect fun environmentVariable(name: String): String?

/**
 * Endpoint of the S3 server the protocol tests run against — see `docker-compose.yml` for how to
 * start it, then `S3_E2E_ENDPOINT=http://127.0.0.1:9000`.
 *
 * When it is unset the tests that need a server skip themselves, which is fine on a laptop and not
 * fine in CI: a skipped test reads exactly like a passing one. So CI sets `S3_E2E_REQUIRED=1`, and
 * then a missing endpoint is a failure rather than a silence.
 */
public object E2E {
    public val endpoint: String? get() = environmentVariable("S3_E2E_ENDPOINT")

    public val accessKey: String get() = environmentVariable("S3_E2E_ACCESS_KEY") ?: "s3kn-test-access-key"

    public val secretKey: String get() = environmentVariable("S3_E2E_SECRET_KEY") ?: "s3kn-test-secret-key"

    public val bucket: String get() = environmentVariable("S3_E2E_BUCKET") ?: "s3kn-test"

    public val region: String get() = environmentVariable("S3_E2E_REGION") ?: "us-east-1"

    private val required: Boolean get() = environmentVariable("S3_E2E_REQUIRED") == "1"

    /**
     * The endpoint, or `null` when these tests are switched off.
     *
     * @throws IllegalStateException when `S3_E2E_REQUIRED=1` and there is no endpoint, so that a
     *   misconfigured CI job fails instead of quietly running nothing.
     */
    public fun endpointOrSkip(): String? {
        val endpoint = endpoint
        check(!(endpoint == null && required)) {
            "S3_E2E_REQUIRED=1 but S3_E2E_ENDPOINT is unset: the tests that need a server would " +
                "have been skipped without saying so. See docker-compose.yml for how to start it."
        }
        return endpoint
    }
}

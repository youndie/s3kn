package io.github.youndie.s3.cli

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint

/**
 * Everything a request needs, assembled from the environment and the flags that override it.
 *
 * A function of its inputs rather than a reader of the process environment, so that the tests
 * exercise the same code the binary runs instead of a copy of it: [environment] is `getenv` in the
 * binary and a map in a test.
 *
 * @param environment reads a variable; `getenv` in the binary, a map in a test.
 * @throws CliUsageException when something required is missing, naming the variable.
 */
internal fun buildConfig(
    overrides: Overrides,
    environment: (String) -> String?,
): S3Config {
    // A BLANK VALUE IS NO VALUE, and the rule lives here rather than in whatever reads the process
    // environment. An empty `AWS_SECRET_ACCESS_KEY` is what a `secretKeyRef` to a key that is not
    // there leaves behind: taking it as a value turns a broken manifest into
    // `SignatureDoesNotMatch`, which names neither the variable nor the manifest.
    val variable: (String) -> String? = { name -> environment(name)?.takeIf { it.isNotBlank() } }

    val endpoint =
        overrides.endpoint
            ?: variable("S3_ENDPOINT")
            ?: variable("AWS_ENDPOINT_URL")
            ?: throw CliUsageException(
                "No endpoint: set S3_ENDPOINT (or AWS_ENDPOINT_URL), or pass --endpoint. " +
                    "It needs a scheme, as in https://s3.example.com",
            )

    val accessKeyId =
        variable("AWS_ACCESS_KEY_ID")
            ?: throw CliUsageException("AWS_ACCESS_KEY_ID is not set. Credentials are read from the environment only.")
    val secretAccessKey =
        variable("AWS_SECRET_ACCESS_KEY")
            ?: throw CliUsageException(
                "AWS_SECRET_ACCESS_KEY is not set. Credentials are read from the environment only.",
            )

    val addressingText = overrides.addressing ?: variable("S3_ADDRESSING") ?: "path"
    val addressingStyle =
        when (addressingText.lowercase()) {
            // `path` is the default, and it is a choice rather than a guess. The library refuses to
            // infer the style from the endpoint because getting it wrong produces
            // `SignatureDoesNotMatch`, which names nothing (research, decision R3). A tool cannot
            // refuse to have a default, so it takes the one that works for MinIO and for every
            // gateway addressed by an IP or a bare host — and says so in --help.
            "path" -> {
                AddressingStyle.PATH
            }

            "virtual", "virtual-hosted" -> {
                AddressingStyle.VIRTUAL_HOSTED
            }

            else -> {
                throw CliUsageException(
                    "Addressing style `$addressingText` is neither `path` nor `virtual` " +
                        "(S3_ADDRESSING, or --addressing)",
                )
            }
        }

    return S3Config(
        endpoint = parseEndpoint(endpoint),
        region =
            overrides.region
                ?: variable("AWS_REGION")
                ?: variable("AWS_DEFAULT_REGION")
                ?: DEFAULT_REGION,
        credentials =
            S3Credentials(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = variable("AWS_SESSION_TOKEN"),
            ),
        addressingStyle = addressingStyle,
        // Never on. Nothing this tool sends is signed as UNSIGNED-PAYLOAD: a whole body is hashed
        // in memory, and so is every part of a multipart upload (S3Payload.InMemory). The option
        // exists for callers that stream, and this one does not.
        allowUnsignedPayloadOverHttp = false,
    )
}

/**
 * The region a credential scope falls back to.
 *
 * `us-east-1` is not a guess about where anything lives: every S3-compatible gateway that ignores
 * regions accepts it, and AWS itself treats it as the global one. Where the region matters, it is
 * wrong to guess and the variable has to be set — which is why it is in `--help`.
 */
private const val DEFAULT_REGION = "us-east-1"

private fun parseEndpoint(text: String): S3Endpoint =
    try {
        S3Endpoint.parse(text)
    } catch (failure: IllegalArgumentException) {
        throw CliUsageException("Endpoint `$text` cannot be used: ${failure.message}")
    }

package io.github.youndie.s3

import kotlin.time.Clock

/**
 * How a bucket is addressed. The choice decides the `host` header, and `host` is signed, so it has
 * to be settled before a request is built rather than while it is being sent.
 *
 * It is not guessed from the endpoint. A rule like "an AWS domain means virtual-hosted" is right
 * most of the time, and the times it is wrong produce `SignatureDoesNotMatch` or a DNS failure,
 * neither of which points at the guess.
 */
public enum class AddressingStyle {
    /** `https://host/bucket/key`. What MinIO and most local deployments expect. */
    PATH,

    /** `https://bucket.host/key`. What AWS expects, and what the AWS SDKs default to. */
    VIRTUAL_HOSTED,
}

/**
 * Access key, secret key and, for temporary credentials, a session token.
 *
 * Not a data class: a generated `toString` would put the secret key into every log line that
 * prints a config, and nothing about that failure is visible until the log is read by someone
 * else.
 */
public class S3Credentials(
    public val accessKeyId: String,
    public val secretAccessKey: String,
    /** `X-Amz-Security-Token` for temporary credentials; signed along with the rest. */
    public val sessionToken: String? = null,
) {
    init {
        require(accessKeyId.isNotBlank()) { "Access key id must not be blank" }
        require(secretAccessKey.isNotBlank()) { "Secret access key must not be blank" }
    }

    override fun toString(): String = "S3Credentials(accessKeyId=$accessKeyId, secretAccessKey=***)"
}

/**
 * Everything a request needs before it can be signed.
 *
 * The clock is a parameter so that signing is a pure function of its inputs: the test vectors are
 * signed at a fixed moment, and a signer that reads the system clock cannot reproduce them.
 */
public class S3Config(
    public val endpoint: S3Endpoint,
    /** Second field of the credential scope, e.g. `us-east-1`. */
    public val region: String,
    public val credentials: S3Credentials,
    public val addressingStyle: AddressingStyle = AddressingStyle.VIRTUAL_HOSTED,
    public val clock: Clock = Clock.System,
) {
    init {
        require(region.isNotBlank()) { "Region must not be blank: it is part of the credential scope" }
    }

    /**
     * The `host` header for a request against this bucket.
     *
     * In [AddressingStyle.VIRTUAL_HOSTED] the bucket becomes a label in front of the endpoint host,
     * and the port — when there is one — stays behind it.
     */
    public fun hostHeaderFor(bucket: String): String =
        when (addressingStyle) {
            AddressingStyle.PATH -> {
                endpoint.hostHeader
            }

            AddressingStyle.VIRTUAL_HOSTED -> {
                val host = "$bucket.${endpoint.host}"
                if (endpoint.port == null) host else "$host:${endpoint.port}"
            }
        }

    /**
     * The path of a request against this bucket and key, already percent-encoded.
     *
     * This is the string that is signed and the string that is sent — they come from here so that
     * they cannot be produced by two different encoders
     * (docs/research/research-architecture.md, decision R4).
     *
     * An empty key addresses the bucket itself, which is what a listing does. In
     * [AddressingStyle.PATH] that leaves a trailing slash, and deliberately so: one rule,
     * `/bucket/` followed by the encoded key, covers both cases.
     *
     * The bucket is not encoded. S3 bucket names are restricted to lower-case letters, digits,
     * hyphens and dots, none of which percent-encoding would touch.
     */
    public fun encodedPathFor(
        bucket: String,
        key: String,
    ): String =
        when (addressingStyle) {
            AddressingStyle.PATH -> "/$bucket/${uriEncodeKey(key)}"
            AddressingStyle.VIRTUAL_HOSTED -> "/${uriEncodeKey(key)}"
        }

    /** The full URL of a request, without a query string. */
    public fun urlFor(
        bucket: String,
        key: String,
    ): String = "${endpoint.scheme}://${hostHeaderFor(bucket)}${encodedPathFor(bucket, key)}"

    override fun toString(): String =
        "S3Config(endpoint=$endpoint, region=$region, addressingStyle=$addressingStyle, " +
            "credentials=$credentials)"
}

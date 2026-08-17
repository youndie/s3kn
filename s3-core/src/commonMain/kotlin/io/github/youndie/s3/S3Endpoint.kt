package io.github.youndie.s3

/**
 * Where the client sends requests: `https://s3.us-east-1.amazonaws.com`, `http://localhost:9000`.
 *
 * The reason this is a type and not a string is [hostHeader]. `host` is a signed header in every
 * S3 request, so the value has to be derived once, by one rule, and then used both in the
 * signature and on the wire.
 */
public class S3Endpoint private constructor(
    /** `https` or `http`, lower-case. */
    public val scheme: String,
    /** Host without a port, lower-case; an IPv6 address keeps its brackets. */
    public val host: String,
    /** Port, or `null` when it is the default for [scheme] and therefore not written anywhere. */
    public val port: Int?,
) {
    public val isSecure: Boolean get() = scheme == HTTPS

    /**
     * The value of the `host` header: lower-case, without userinfo, and without the port when the
     * port is the scheme's default (docs/spec/reference/botocore-auth.py:81).
     */
    public val hostHeader: String get() = if (port == null) host else "$host:$port"

    /** `https://s3.us-east-1.amazonaws.com` — the endpoint without a path. */
    public val origin: String get() = "$scheme://$hostHeader"

    override fun toString(): String = origin

    public companion object {
        private const val HTTPS = "https"
        private const val HTTP = "http"
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        /**
         * Parses an endpoint URL. A path, a query and a fragment are ignored: the client builds
         * those itself from the bucket and the key.
         *
         * @throws IllegalArgumentException if the scheme is missing or is not http(s), if the host
         *   is empty, or if the port is not a number.
         */
        public fun parse(url: String): S3Endpoint {
            val separator = url.indexOf("://")
            require(separator > 0) { "Endpoint must start with http:// or https://, got: $url" }

            val scheme = url.substring(0, separator).lowercase()
            require(scheme == HTTPS || scheme == HTTP) {
                "Endpoint scheme must be http or https, got: $scheme"
            }

            val authority =
                url
                    .substring(separator + "://".length)
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    // Userinfo is never part of the host header. `@` cannot appear in a host, so
                    // the last one separates the two.
                    .substringAfterLast('@')

            val (host, port) = splitHostAndPort(authority)
            require(host.isNotEmpty()) { "Endpoint has no host: $url" }

            val defaultPort = if (scheme == HTTPS) HTTPS_PORT else HTTP_PORT
            return S3Endpoint(scheme, host.lowercase(), port.takeIf { it != defaultPort })
        }

        private fun splitHostAndPort(authority: String): Pair<String, Int?> {
            // An IPv6 literal is full of colons, so the port separator is the one after `]`.
            val hostEnd = if (authority.startsWith('[')) authority.indexOf(']') + 1 else 0
            require(!authority.startsWith('[') || hostEnd > 1) {
                "Endpoint has an unterminated IPv6 address: $authority"
            }

            val colon = authority.indexOf(':', startIndex = hostEnd)
            if (colon < 0) return authority to null

            val portText = authority.substring(colon + 1)
            val port =
                portText.toIntOrNull()
                    ?: throw IllegalArgumentException("Endpoint port is not a number: $portText")
            return authority.substring(0, colon) to port
        }
    }
}

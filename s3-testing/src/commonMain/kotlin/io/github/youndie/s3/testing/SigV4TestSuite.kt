package io.github.youndie.s3.testing

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * One case of the AWS Signature Version 4 test suite, as vendored in `docs/spec`.
 *
 * The files are compared byte for byte, so nothing here trims, normalises or re-encodes them.
 * Two details of the published files matter and are easy to get wrong:
 *
 * - lines are separated by `\n`, not by `\r\n`, even inside `.req`, which holds an HTTP request;
 * - no file ends with a newline.
 */
public class SigV4TestCase internal constructor(
    /** Case name as it appears under `aws-sig-v4-test-suite`, e.g. `normalize-path/get-slash`. */
    public val name: String,
    /** Contents of `<case>.req` — the raw HTTP request that is the input of the case. */
    public val request: String,
    /** Contents of `<case>.creq` — the canonical request the signer is expected to produce. */
    public val canonicalRequest: String,
    /** Contents of `<case>.sts` — the expected string to sign. */
    public val stringToSign: String,
    /** Contents of `<case>.authz` — the expected `Authorization` header. */
    public val authorization: String,
) {
    private val parsed = parseHttpRequest(request)

    /** Request method, upper-case as sent. */
    public val method: String get() = parsed.method

    /** Request path, still percent-encoded exactly as it appeared on the request line. */
    public val path: String get() = parsed.path

    /** Query parameters, decoded, in the order sent and with repeated names kept. */
    public val query: List<Pair<String, String>> get() = parsed.query

    /** Headers in the order sent, with folded values joined but spacing otherwise untouched. */
    public val headers: List<Pair<String, String>> get() = parsed.headers

    /** Request body, empty when the request had none. */
    public val body: String get() = parsed.body

    /**
     * The session token the credentials of this case carry, or `null` when they carry none.
     *
     * Read out of the **expected** canonical request, which is circular and is what the reference
     * implementation does (`botocore/tests/unit/auth/test_sigv4.py`, `SignatureTestCase`). There is
     * no alternative: the two cases that carry a token carry different ones, and
     * `post-sts-token/post-sts-header-after` deliberately has credentials whose token is never
     * signed — a fact that exists nowhere but in the expected output.
     */
    public val sessionToken: String? =
        canonicalRequest
            .lineSequence()
            .firstOrNull { it.startsWith(TOKEN_HEADER_PREFIX) }
            ?.removePrefix(TOKEN_HEADER_PREFIX)

    override fun toString(): String = "SigV4TestCase($name)"

    private companion object {
        const val TOKEN_HEADER_PREFIX = "x-amz-security-token:"
    }
}

/**
 * Loader for the vendored test vectors.
 *
 * The suite ships fixed inputs, listed here so a test does not have to go looking for them:
 * access key `AKIDEXAMPLE`, secret key `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, timestamp
 * `20150830T123600Z`, region `us-east-1`, service `service`.
 */
public object SigV4TestSuite {
    public const val ACCESS_KEY: String = "AKIDEXAMPLE"
    public const val SECRET_KEY: String = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    public const val TIMESTAMP: String = "20150830T123600Z"
    public const val REGION: String = "us-east-1"
    public const val SERVICE: String = "service"

    private val root: Path = Path(SPEC_PATH, "aws-sig-v4-test-suite")

    /**
     * Every case in the suite, sorted, with nested ones named by their relative path
     * (`normalize-path/get-space`). A case is a directory holding a `<basename>.req`.
     */
    public fun caseNames(): List<String> = collectCaseNames(root, prefix = "").sorted()

    /**
     * Loads a case by its name, which may contain a directory: `get-vanilla`,
     * `normalize-path/get-slash`. The file basename is the last segment of the name.
     */
    public fun case(name: String): SigV4TestCase {
        val directory = Path(root, name)
        val basename = name.substringAfterLast('/')
        return SigV4TestCase(
            name = name,
            request = read(Path(directory, "$basename.req")),
            canonicalRequest = read(Path(directory, "$basename.creq")),
            stringToSign = read(Path(directory, "$basename.sts")),
            authorization = read(Path(directory, "$basename.authz")),
        )
    }

    private fun collectCaseNames(
        directory: Path,
        prefix: String,
    ): List<String> =
        SystemFileSystem.list(directory).flatMap { entry ->
            val metadata = SystemFileSystem.metadataOrNull(entry)
            if (metadata?.isDirectory != true) {
                return@flatMap emptyList()
            }
            val name = entry.name
            val qualified = if (prefix.isEmpty()) name else "$prefix/$name"
            if (SystemFileSystem.exists(Path(entry, "$name.req"))) {
                listOf(qualified)
            } else {
                collectCaseNames(entry, qualified)
            }
        }

    private fun read(path: Path): String {
        if (!SystemFileSystem.exists(path)) {
            error("Test vector not found: $path. The suite lives in docs/spec/aws-sig-v4-test-suite.")
        }
        return SystemFileSystem.source(path).use { source ->
            source.buffered().readString()
        }
    }
}

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
)

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
        )
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

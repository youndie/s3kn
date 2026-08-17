package io.github.youndie.s3.testing

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** A request signed with headers, as the reference implementation signs it. */
public class S3HeaderVector internal constructor(
    public val name: String,
    public val method: String,
    /** `path` or `virtual`. */
    public val style: String,
    public val bucket: String,
    public val key: String,
    public val query: List<Pair<String, String>>,
    public val body: String,
    /** Whether the case asks for `UNSIGNED-PAYLOAD` instead of a hash of the body. */
    public val unsigned: Boolean,
    public val sessionToken: String?,
    public val canonicalRequest: String,
    public val stringToSign: String,
    public val authorization: String,
    public val contentSha256: String,
)

/** A presigned URL, as the reference implementation produces it. */
public class S3PresignVector internal constructor(
    public val name: String,
    public val method: String,
    public val style: String,
    public val bucket: String,
    public val key: String,
    public val query: List<Pair<String, String>>,
    public val sessionToken: String?,
    public val expiresSeconds: Long,
    public val url: String,
)

/**
 * Vectors for the two things AWS publishes no vectors for: S3's own signing rules, and presigned
 * URLs.
 *
 * They are generated from botocore — the code that signs for `aws-cli` — by
 * `docs/spec/s3-signing-vectors/generate.py`, which is committed alongside them together with the
 * botocore version used. Unlike `aws-sig-v4-test-suite` these are not published by AWS, so they
 * are only as good as the reference implementation; that is still an independent implementation
 * rather than a restatement of this library's own behaviour.
 *
 * Fixed inputs, the same as the official suite: access key `AKIDEXAMPLE`, secret key
 * `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, timestamp `20150830T123600Z`, region `us-east-1`,
 * service `s3`, endpoint `s3.us-east-1.amazonaws.com`.
 */
public object S3SigningVectors {
    public const val ENDPOINT: String = "https://s3.us-east-1.amazonaws.com"
    public const val SERVICE: String = "s3"

    private val root: Path = Path(SPEC_PATH, "s3-signing-vectors")

    /**
     * `raw` to `encoded` pairs produced by Python's `quote(key, safe='/~')` — an implementation of
     * the encoding rule that owes nothing to this library's.
     */
    public fun keyEncodings(): List<Pair<String, String>> =
        read(Path(root, "key-encoding"))
            .lineSequence()
            .filter { it.isNotEmpty() }
            .map { line -> line.substringBefore('\t') to line.substringAfter('\t') }
            .toList()

    public fun headerCaseNames(): List<String> = caseNames("header")

    public fun presignCaseNames(): List<String> = caseNames("presign")

    public fun headerCase(name: String): S3HeaderVector {
        val directory = Path(root, "header", name)
        val input = readInput(directory)
        return S3HeaderVector(
            name = name,
            method = input.getValue("method"),
            style = input.getValue("style"),
            bucket = input.getValue("bucket"),
            key = input.getValue("key"),
            query = parseRawQuery(input.getValue("query")),
            body = input.getValue("body"),
            unsigned = input.getValue("unsigned") == "true",
            sessionToken = input.getValue("token").ifEmpty { null },
            canonicalRequest = read(Path(directory, "creq")),
            stringToSign = read(Path(directory, "sts")),
            authorization = read(Path(directory, "authz")),
            contentSha256 = read(Path(directory, "sha256")),
        )
    }

    public fun presignCase(name: String): S3PresignVector {
        val directory = Path(root, "presign", name)
        val input = readInput(directory)
        return S3PresignVector(
            name = name,
            method = input.getValue("method"),
            style = input.getValue("style"),
            bucket = input.getValue("bucket"),
            key = input.getValue("key"),
            query = parseRawQuery(input.getValue("query")),
            sessionToken = input.getValue("token").ifEmpty { null },
            expiresSeconds = input.getValue("expires").toLong(),
            url = read(Path(directory, "url")),
        )
    }

    private fun caseNames(group: String): List<String> =
        SystemFileSystem
            .list(Path(root, group))
            .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
            .map { it.name }
            .sorted()

    private fun readInput(directory: Path): Map<String, String> =
        read(Path(directory, "input"))
            .lineSequence()
            .filter { it.isNotEmpty() }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    private fun read(path: Path): String {
        if (!SystemFileSystem.exists(path)) {
            error("Vector not found: $path. Regenerate with docs/spec/s3-signing-vectors/generate.py.")
        }
        return SystemFileSystem.source(path).use { source ->
            source.buffered().readString()
        }
    }
}

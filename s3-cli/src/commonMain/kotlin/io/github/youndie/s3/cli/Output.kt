package io.github.youndie.s3.cli

import io.github.youndie.s3.S3ObjectMetadata
import io.github.youndie.s3.S3ObjectSummary

/**
 * One line of a listing: when, how big, and the key.
 *
 * The size is in bytes, not in mebibytes. What reads these lines is `awk` in a backup job, not a
 * person looking at a terminal, and a rounded number cannot be compared with the size of a file
 * (docs/features/feature-cli.md, section 7).
 *
 * The timestamp is whatever S3 wrote, not reinterpreted here — the library keeps it verbatim for
 * the same reason.
 */
internal fun listingLine(summary: S3ObjectSummary): String =
    "${summary.lastModified ?: "-"}  ${summary.size}  ${summary.key}"

/**
 * One rolled-up prefix.
 *
 * Marked, not printed as a bare key: a prefix is not an object, and a script that treats it as one
 * asks for a key that does not exist.
 */
internal fun prefixLine(prefix: String): String = "PRE  $prefix"

/** What `stat` prints: one `name  value` per line, with the names aligned. */
internal fun statLines(
    bucket: String,
    key: String,
    metadata: S3ObjectMetadata,
): List<String> {
    val fields =
        buildList {
            add("key" to "s3://$bucket/$key")
            add("size" to (metadata.contentLength?.toString() ?: "-"))
            add("last-modified" to (metadata.lastModified ?: "-"))
            add("etag" to (metadata.eTag ?: "-"))
            add("content-type" to (metadata.contentType ?: "-"))
            metadata.userMetadata.entries
                .sortedBy { it.key }
                .forEach { (name, value) -> add("meta-$name" to value) }
        }
    val width = fields.maxOf { it.first.length }
    return fields.map { (name, value) -> "${name.padEnd(width)}  $value" }
}

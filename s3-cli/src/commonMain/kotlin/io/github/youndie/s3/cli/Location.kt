package io.github.youndie.s3.cli

/**
 * One side of a copy: an object, a file, or the process's own standard stream.
 *
 * The three cases are told apart by syntax alone, never by looking at the filesystem. A path that
 * happens to exist and a path that does not have to be parsed the same way, or the same command
 * line would mean two different things on two machines.
 */
internal sealed interface Location {
    /** `s3://bucket/key`. [key] is empty when the text addressed the bucket itself. */
    data class Remote(
        val bucket: String,
        val key: String,
    ) : Location

    /** An ordinary path. */
    data class Local(
        val path: String,
    ) : Location

    /** `-`: standard input when it is the source, standard output when it is the destination. */
    data object Standard : Location
}

internal const val S3_SCHEME: String = "s3://"

/**
 * Parses one side of a copy.
 *
 * The key is taken verbatim — everything after the first `/` of the path, spaces, pluses and
 * non-ASCII included. Encoding it is the library's job and is done in exactly one place
 * (docs/research/research-architecture.md, decision R4); a second encoder here would produce a
 * `SignatureDoesNotMatch` that names no encoding.
 *
 * @throws CliUsageException when the text starts with the scheme but carries no bucket.
 */
internal fun parseLocation(text: String): Location =
    when {
        text == "-" -> {
            Location.Standard
        }

        text.startsWith(S3_SCHEME) -> {
            val rest = text.substring(S3_SCHEME.length)
            val slash = rest.indexOf('/')
            val bucket = if (slash < 0) rest else rest.substring(0, slash)
            val key = if (slash < 0) "" else rest.substring(slash + 1)
            if (bucket.isEmpty()) {
                throw CliUsageException("`$text` names no bucket: expected s3://bucket/key")
            }
            Location.Remote(bucket, key)
        }

        text.isEmpty() -> {
            throw CliUsageException("An empty path is not a location")
        }

        else -> {
            Location.Local(text)
        }
    }

/**
 * Parses a location that has to address a single object.
 *
 * `s3://bucket` on its own is a bucket, and every command but `ls` is about one object. Saying so
 * here means `stat s3://backups` fails with a sentence instead of a `404` from the other side.
 */
internal fun parseObjectLocation(
    text: String,
    command: String,
): Location.Remote {
    val location =
        parseLocation(text) as? Location.Remote
            ?: throw CliUsageException("`$command` works on an object: expected s3://bucket/key, got `$text`")
    if (location.key.isEmpty()) {
        throw CliUsageException("`$command` needs a key: `$text` addresses the bucket itself")
    }
    return location
}

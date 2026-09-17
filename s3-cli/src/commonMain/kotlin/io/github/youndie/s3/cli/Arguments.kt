package io.github.youndie.s3.cli

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A command line that does not mean anything.
 *
 * Separate from every other failure because the two are fixed in different places and, in a
 * cluster, by different people: this one is a wrong manifest, anything else is a conversation with
 * the storage. It is the only failure that exits with [EXIT_USAGE]
 * (docs/features/feature-cli.md, section 2).
 */
internal class CliUsageException(
    message: String,
) : Exception(message)

/**
 * Something that went wrong while doing the work: a file that is not there, a stream that ended.
 *
 * Exits with [EXIT_FAILED], like an error from the storage — from the point of view of whoever
 * reads the job's log, "the dump could not be read" and "the bucket refused it" are the same kind
 * of bad day, and neither is fixed by editing the manifest.
 */
internal class CliFailure(
    message: String,
) : Exception(message)

/** What the invocation asked for. */
internal sealed interface Command {
    class Copy(
        val source: Location,
        val destination: Location,
        val partSize: Long,
        val concurrency: Int,
        val contentType: String?,
    ) : Command

    class Listing(
        val bucket: String,
        val prefix: String,
        val recursive: Boolean,
    ) : Command

    class Stat(
        val bucket: String,
        val key: String,
    ) : Command

    class Remove(
        val bucket: String,
        val key: String,
    ) : Command

    class Presign(
        val bucket: String,
        val key: String,
        val method: String,
        val expires: Duration,
    ) : Command

    data object Help : Command

    data object Version : Command
}

/** Settings given on the command line, which win over the environment. */
internal class Overrides(
    val endpoint: String? = null,
    val region: String? = null,
    val addressing: String? = null,
)

internal class Invocation(
    val command: Command,
    val overrides: Overrides,
)

/** Eight mebibytes, the same default the library uses: above the five-mebibyte floor S3 imposes. */
internal const val DEFAULT_PART_SIZE: Long = 8L * 1024 * 1024
internal const val DEFAULT_CONCURRENCY: Int = 4

internal const val EXIT_OK: Int = 0
internal const val EXIT_FAILED: Int = 1
internal const val EXIT_USAGE: Int = 2

private val GLOBAL_OPTIONS = setOf("endpoint", "region", "addressing")
private val VALUE_OPTIONS =
    GLOBAL_OPTIONS + setOf("part-size", "concurrency", "content-type", "method", "expires")
private val FLAG_OPTIONS = setOf("recursive", "help", "version")
private val SHORT_OPTIONS = mapOf("r" to "recursive", "h" to "help")
private val PRESIGNABLE_METHODS = setOf("GET", "PUT", "HEAD", "DELETE")

/**
 * Turns a command line into something the runner can execute, or refuses it.
 *
 * Options may appear anywhere, before the command or after its arguments, because that is how
 * every shell user expects it to work and because a manifest written by hand will do it.
 *
 * There is deliberately no option for the access key or the secret: process arguments are visible
 * in `ps`, in `kubectl describe pod` and in the trace of any shell run with `set -x`
 * (docs/features/feature-cli.md, section 2). Credentials come from the environment or not at all.
 */
internal fun parseArguments(argv: List<String>): Invocation {
    val tokens = splitAssignments(argv)

    var commandName: String? = null
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String?>()

    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token == "-" || !token.startsWith("-")) {
            if (commandName == null) commandName = token else positional += token
        } else {
            when (val name = optionName(token)) {
                in FLAG_OPTIONS -> {
                    options[name] = null
                }

                in VALUE_OPTIONS -> {
                    index++
                    options[name] = tokens.getOrNull(index) ?: throw CliUsageException("`--$name` needs a value")
                }

                else -> {
                    throw CliUsageException("Unknown option `$token`. Run `s3kn --help`.")
                }
            }
        }
        index++
    }

    if ("help" in options || commandName == "help") return Invocation(Command.Help, Overrides())
    if ("version" in options || commandName == "version") return Invocation(Command.Version, Overrides())

    val name = commandName ?: throw CliUsageException("No command given. Run `s3kn --help`.")
    val command =
        when (name) {
            "cp" -> copyCommand(positional, options)
            "ls" -> listingCommand(positional, options)
            "stat" -> singleObject(positional, options, "stat").let { Command.Stat(it.bucket, it.key) }
            "rm" -> singleObject(positional, options, "rm").let { Command.Remove(it.bucket, it.key) }
            "presign" -> presignCommand(positional, options)
            else -> throw CliUsageException("Unknown command `$name`. Run `s3kn --help`.")
        }

    return Invocation(
        command = command,
        overrides =
            Overrides(
                endpoint = options["endpoint"],
                region = options["region"],
                addressing = options["addressing"],
            ),
    )
}

private fun copyCommand(
    positional: List<String>,
    options: Map<String, String?>,
): Command.Copy {
    refuseUnknown(options, "cp", setOf("part-size", "concurrency", "content-type"))
    if (positional.size != 2) {
        throw CliUsageException(
            "`cp` takes a source and a destination, got ${positional.size}: s3kn cp <source> <destination>",
        )
    }

    val source = parseLocation(positional[0])
    val destination = parseLocation(positional[1])
    val remote = listOf(source, destination).filterIsInstance<Location.Remote>()
    if (remote.size != 1) {
        throw CliUsageException(
            if (remote.isEmpty()) {
                "Exactly one side of a copy has to be s3://bucket/key; neither of these is. " +
                    "Copying a file to a file is what `cp` is for."
            } else {
                "Exactly one side of a copy has to be s3://bucket/key; both of these are. " +
                    "Copying an object to an object needs CopyObject, which this client does not have."
            },
        )
    }
    if (remote.single().key.isEmpty()) {
        throw CliUsageException("`cp` needs a key: s3://${remote.single().bucket} addresses the bucket itself")
    }

    return Command.Copy(
        source = source,
        destination = destination,
        partSize = options["part-size"]?.let { byteSize(it, "part-size") } ?: DEFAULT_PART_SIZE,
        concurrency = options["concurrency"]?.let { positiveInt(it, "concurrency") } ?: DEFAULT_CONCURRENCY,
        contentType = options["content-type"],
    )
}

private fun listingCommand(
    positional: List<String>,
    options: Map<String, String?>,
): Command.Listing {
    refuseUnknown(options, "ls", setOf("recursive"))
    if (positional.size != 1) {
        throw CliUsageException("`ls` takes one location: s3kn ls s3://bucket[/prefix]")
    }
    val location =
        parseLocation(positional[0]) as? Location.Remote
            ?: throw CliUsageException("`ls` lists a bucket: expected s3://bucket[/prefix], got `${positional[0]}`")

    return Command.Listing(
        bucket = location.bucket,
        prefix = location.key,
        recursive = "recursive" in options,
    )
}

private fun presignCommand(
    positional: List<String>,
    options: Map<String, String?>,
): Command.Presign {
    val location = singleObject(positional, options, "presign", accepted = setOf("method", "expires"))

    val method = (options["method"] ?: "GET").uppercase()
    if (method !in PRESIGNABLE_METHODS) {
        throw CliUsageException("`--method $method` is not one this can sign: ${PRESIGNABLE_METHODS.joinToString()}")
    }

    return Command.Presign(
        bucket = location.bucket,
        key = location.key,
        method = method,
        expires = options["expires"]?.let { duration(it) } ?: 1.hours,
    )
}

/** The single object a command works on, with its options checked. */
private fun singleObject(
    positional: List<String>,
    options: Map<String, String?>,
    command: String,
    accepted: Set<String> = emptySet(),
): Location.Remote {
    refuseUnknown(options, command, accepted)
    if (positional.size != 1) {
        throw CliUsageException("`$command` takes one object: s3kn $command s3://bucket/key")
    }
    return parseObjectLocation(positional[0], command)
}

/**
 * Refuses an option the command does not have.
 *
 * Silently ignoring it is worse than it looks: `s3kn cp --recursive dir s3://b/k` would copy one
 * file, report success, and the manifest would keep saying `--recursive` for a year.
 */
private fun refuseUnknown(
    options: Map<String, String?>,
    command: String,
    accepted: Set<String>,
) {
    val unexpected = options.keys - accepted - GLOBAL_OPTIONS
    if (unexpected.isNotEmpty()) {
        throw CliUsageException("`$command` has no option `--${unexpected.first()}`")
    }
}

/** Splits `--name=value` into two tokens; a positional argument that contains `=` is left alone. */
private fun splitAssignments(argv: List<String>): List<String> =
    argv.flatMap { token ->
        if (token.startsWith("--") && token.contains('=')) {
            listOf(token.substringBefore('='), token.substringAfter('='))
        } else {
            listOf(token)
        }
    }

private fun optionName(token: String): String =
    if (token.startsWith("--")) {
        token.substring(2)
    } else {
        SHORT_OPTIONS[token.substring(1)] ?: throw CliUsageException("Unknown option `$token`. Run `s3kn --help`.")
    }

/**
 * A size in bytes, with the suffixes people actually write.
 *
 * `K`, `M` and `G` mean 1024, not 1000. Storage sizes are powers of two everywhere else in this
 * tool, and a `--part-size 5M` that meant 5 000 000 would be below S3's minimum part size by a
 * margin nobody would think to look for.
 */
private fun byteSize(
    text: String,
    option: String,
): Long {
    val normalised =
        text
            .trim()
            .uppercase()
            .removeSuffix("IB")
            .removeSuffix("B")
    val multiplier =
        when (normalised.lastOrNull()) {
            'K' -> 1024L
            'M' -> 1024L * 1024
            'G' -> 1024L * 1024 * 1024
            else -> null
        }
    val digits = if (multiplier == null) normalised else normalised.dropLast(1)
    val value =
        digits.toLongOrNull()
            ?: throw CliUsageException("`--$option $text` is not a size: expected bytes, or 8M, or 16MiB")
    if (value <= 0) throw CliUsageException("`--$option $text` has to be positive")
    return value * (multiplier ?: 1L)
}

private fun positiveInt(
    text: String,
    option: String,
): Int {
    val value = text.toIntOrNull() ?: throw CliUsageException("`--$option $text` is not a number")
    if (value <= 0) throw CliUsageException("`--$option $text` has to be positive")
    return value
}

/** A duration written as seconds, or with one of `s`, `m`, `h`, `d`. */
private fun duration(text: String): Duration {
    val normalised = text.trim().lowercase()
    val multiplier =
        when (normalised.lastOrNull()) {
            's' -> 1L
            'm' -> 60L
            'h' -> 60L * 60
            'd' -> 24L * 60 * 60
            else -> null
        }
    val digits = if (multiplier == null) normalised else normalised.dropLast(1)
    val value =
        digits.toLongOrNull()
            ?: throw CliUsageException("`--expires $text` is not a duration: expected seconds, or 15m, or 1h")
    if (value <= 0) throw CliUsageException("`--expires $text` has to be positive")
    return (value * (multiplier ?: 1L)).seconds
}

internal fun usageText(): String =
    """
    |s3kn — S3 from a shell script, without a configuration file.
    |
    |  s3kn cp <source> <destination>   copy an object in or out; `-` is stdin/stdout
    |  s3kn ls <s3://bucket[/prefix]>   list objects
    |  s3kn stat <s3://bucket/key>      print what the storage knows about an object
    |  s3kn rm <s3://bucket/key>        remove an object
    |  s3kn presign <s3://bucket/key>   print a signed URL, sending nothing
    |
    |Settings come from the environment; the flags below win over it.
    |
    |  --endpoint URL       S3_ENDPOINT or AWS_ENDPOINT_URL, required, with a scheme
    |  --region NAME        AWS_REGION or AWS_DEFAULT_REGION, default us-east-1
    |  --addressing STYLE   S3_ADDRESSING: path (default) or virtual
    |
    |  AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY    required, environment only
    |  AWS_SESSION_TOKEN                           for temporary credentials
    |
    |Per command:
    |
    |  cp       --part-size 8M   --concurrency 4   --content-type TYPE
    |  ls       -r, --recursive
    |  presign  --method GET     --expires 1h
    |
    |Exit codes: 0 done, 1 the operation failed, 2 the command line or the environment is wrong.
    |
    |  s3kn cp /dump/d.dump s3://backups/prod/d.dump
    |  s3kn cp s3://backups/prod/d.dump /dump/d.dump
    |  pg_dump -Fc | s3kn cp - s3://backups/prod/d.dump
    """.trimMargin()

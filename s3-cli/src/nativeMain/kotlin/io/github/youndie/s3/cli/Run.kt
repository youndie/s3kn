package io.github.youndie.s3.cli

import io.github.youndie.s3.S3Client
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Exception
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.utils.io.WriterJob
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import platform.posix.STDERR_FILENO
import platform.posix.write
import kotlin.system.exitProcess

/**
 * The engine, chosen by the target and nowhere else.
 *
 * The library never picks one on purpose — which engine can carry HTTPS is a property of the
 * platform (docs/research/research-architecture.md, fact 1.1) — so the choice belongs to the
 * application, and this binary is the application.
 */
internal expect fun cliEngine(): HttpClientEngineFactory<*>

/** Counts what actually moved, as opposed to what a header claimed. */
internal class ByteCounter {
    var value: Long = 0L
}

fun main(args: Array<String>): Unit = exitProcess(runCli(args.toList()))

/**
 * Runs one invocation and returns its exit code.
 *
 * Returns rather than exits so that the end-to-end tests can drive the real path — argument
 * parsing, configuration, engine, request — instead of a copy of it that happens to look the same
 * (docs/features/feature-cli.md, section 5).
 */
@Suppress(
    "ktlint:kapkan:cancellation-swallowed",
    "the outermost frame of a process: there is no caller left to propagate cancellation to",
)
internal fun runCli(argv: List<String>): Int =
    try {
        val invocation = parseArguments(argv)
        when (val command = invocation.command) {
            Command.Help -> {
                println(usageText())
                EXIT_OK
            }

            Command.Version -> {
                println("s3kn $CLI_VERSION")
                EXIT_OK
            }

            // Sends nothing, so it needs neither an engine nor a network — and must not open one.
            is Command.Presign -> {
                println(presignUrl(buildConfig(invocation.overrides, ::environmentVariable), command))
                EXIT_OK
            }

            else -> {
                connect(command, buildConfig(invocation.overrides, ::environmentVariable))
            }
        }
    } catch (failure: CliUsageException) {
        printError(failure.message)
        printError("")
        printError(usageText())
        EXIT_USAGE
    } catch (failure: IllegalArgumentException) {
        // The library refusing what the command line asked for: a key with a `.` segment, an
        // expiry beyond seven days. The manifest is what has to change, so it is a usage failure.
        printError(failure.message ?: "The request the command line describes cannot be made")
        EXIT_USAGE
    } catch (failure: S3Exception) {
        // Already carries the status, the error code and both request ids — that string is what
        // AWS support asks for, so it is printed whole rather than summarised.
        printError(failure.message)
        EXIT_FAILED
    } catch (failure: CliFailure) {
        printError(failure.message)
        EXIT_FAILED
    } catch (failure: Throwable) {
        // A connection refused, a TLS handshake with no certificates, a disk that filled up. The
        // class name is part of the message on purpose: the text of a native I/O failure is often
        // just an errno string, and on its own it says nothing about what was being attempted.
        printError("${failure::class.simpleName}: ${failure.message}")
        EXIT_FAILED
    }

private fun connect(
    command: Command,
    config: S3Config,
): Int =
    // Dispatchers.Default, not the event loop `runBlocking {}` would install. Every part of a
    // multipart upload is hashed with SHA-256 on the caller's context, and on a single thread that
    // hashing is serialised: the same code on a multi-threaded dispatcher was measured 2.3 times
    // faster (docs/measurements.md, M-110). One line, and it is the whole reason that measurement
    // was made.
    runBlocking(Dispatchers.Default) {
        HttpClient(cliEngine()).use { http ->
            val client = S3Client(config, http)
            when (command) {
                is Command.Copy -> {
                    copy(client, command)
                }

                is Command.Listing -> {
                    list(client, command)
                }

                is Command.Stat -> {
                    stat(client, command)
                }

                is Command.Remove -> {
                    remove(client, command)
                }

                Command.Help, Command.Version, is Command.Presign -> {
                    error("Handled before a client is built: ${command::class.simpleName}")
                }
            }
        }
    }

private suspend fun copy(
    client: S3Client,
    command: Command.Copy,
): Int =
    when (val destination = command.destination) {
        is Location.Remote -> upload(client, command, destination)
        else -> download(client, command, command.source as Location.Remote, destination)
    }

/**
 * Sends a file or standard input to S3.
 *
 * Two paths, and the split is not about speed. A body held in memory is **hashed**, so the
 * signature covers it; a streamed one cannot be, and would have to be signed as `UNSIGNED-PAYLOAD`,
 * which the library refuses over plain HTTP. Multipart takes the first path too — every part is an
 * `S3Payload.InMemory` — so both work against a MinIO reached over `http://`, and memory stays
 * bounded by `partSize × concurrency` however large the file is.
 */
private suspend fun upload(
    client: S3Client,
    command: Command.Copy,
    destination: Location.Remote,
): Int {
    val counter = ByteCounter()
    val source = command.source

    val eTag =
        if (source is Location.Local && fileSizeOf(source.path) <= command.partSize) {
            val body = readWholeFile(source.path)
            counter.value = body.size.toLong()
            client.put(destination.bucket, destination.key, body, command.contentType)
        } else {
            uploadStream(client, command, destination, counter)
        }

    println("copied ${counter.value} bytes to s3://${destination.bucket}/${destination.key} (etag ${eTag ?: "-"})")
    return EXIT_OK
}

private suspend fun uploadStream(
    client: S3Client,
    command: Command.Copy,
    destination: Location.Remote,
    counter: ByteCounter,
): String? =
    coroutineScope {
        val producer: WriterJob =
            when (val source = command.source) {
                is Location.Local -> fileAsChannel(source.path, counter)
                Location.Standard -> stdinAsChannel(counter)
                is Location.Remote -> error("A copy with two remote sides is refused during parsing")
            }
        try {
            client.putMultipart(
                bucket = destination.bucket,
                key = destination.key,
                body = producer.channel,
                partSize = command.partSize,
                concurrency = command.concurrency,
                contentType = command.contentType,
            )
        } finally {
            // Already finished on the happy path. On any other, the upload has stopped reading and
            // this coroutine would stay suspended on a full channel for ever — turning a failure
            // into a hang, which is the one failure nobody reads a log for.
            producer.job.cancel()
        }
    }

private suspend fun download(
    client: S3Client,
    command: Command.Copy,
    source: Location.Remote,
    destination: Location,
): Int {
    val written =
        client.get(source.bucket, source.key) { objectBody ->
            when (destination) {
                is Location.Local -> objectBody.body.writeToFile(destination.path)
                Location.Standard -> objectBody.body.writeToStandardOutput()
                is Location.Remote -> error("A copy with two remote sides is refused during parsing")
            }
        }

    val report = "copied $written bytes from s3://${source.bucket}/${source.key}"
    // When the object itself is going to standard output, the summary cannot: it would land in the
    // middle of the file being written.
    if (destination == Location.Standard) printError(report) else println(report)
    return EXIT_OK
}

private suspend fun list(
    client: S3Client,
    command: Command.Listing,
): Int {
    client
        .list(
            bucket = command.bucket,
            prefix = command.prefix.ifEmpty { null },
            // Without a delimiter a listing is flat; with one, everything below the next `/` is
            // rolled up. That is the difference between `ls` and `ls -r`, and it is the server that
            // does the rolling up — not a filter here.
            delimiter = if (command.recursive) null else "/",
        ).collect { page ->
            page.commonPrefixes.forEach { println(prefixLine(it)) }
            page.objects.forEach { println(listingLine(it)) }
        }
    return EXIT_OK
}

private suspend fun stat(
    client: S3Client,
    command: Command.Stat,
): Int {
    val metadata = client.head(command.bucket, command.key)
    statLines(command.bucket, command.key, metadata).forEach { println(it) }
    return EXIT_OK
}

private suspend fun remove(
    client: S3Client,
    command: Command.Remove,
): Int {
    client.delete(command.bucket, command.key)
    // Deliberately not "removed": S3 answers the same way whether the key was there or not, and
    // claiming a removal that may never have happened is worse than saying what was asked for
    // (docs/api/protocol-s3.md, section 4.3).
    println("s3://${command.bucket}/${command.key} is gone")
    return EXIT_OK
}

/**
 * Writes a line to standard error, through the file descriptor rather than through `stderr`.
 *
 * `println` is not an option — that is standard output, and `cp … -` puts an object there. A
 * descriptor rather than the `FILE*` because the two targets spell that variable differently.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun printError(message: String?) {
    val bytes = "${message ?: ""}\n".encodeToByteArray()
    bytes.usePinned { pinned ->
        var written = 0
        while (written < bytes.size) {
            val count = write(STDERR_FILENO, pinned.addressOf(written), (bytes.size - written).convert()).toInt()
            if (count <= 0) return
            written += count
        }
    }
}

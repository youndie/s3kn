package io.github.youndie.s3.cli

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.WriterJob
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.getenv
import platform.posix.read
import platform.posix.write

/** 64 KiB: one read, one write, and far below the smallest part S3 will accept. */
private const val COPY_BUFFER = 64 * 1024

/**
 * Reads an environment variable, verbatim.
 *
 * Whether an empty value counts as unset is decided in [buildConfig], in one place, where a test
 * can reach it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun environmentVariable(name: String): String? = getenv(name)?.toKString()

/**
 * How large a file is, refusing anything that is not one.
 *
 * A directory is refused here rather than at the first read: `s3kn cp /dump s3://b/k` otherwise
 * fails somewhere inside the copy, with a message about a stream.
 */
internal fun fileSizeOf(path: String): Long {
    val metadata =
        SystemFileSystem.metadataOrNull(Path(path))
            ?: throw CliFailure("Cannot read `$path`: no such file")
    if (metadata.isDirectory) {
        throw CliFailure("`$path` is a directory; this copies one object at a time")
    }
    return metadata.size
}

/** Reads a whole file. Only ever called for one smaller than a part — see `Run.kt`. */
internal fun readWholeFile(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

/**
 * A file, as a channel the library can upload from.
 *
 * The [WriterJob] is returned rather than just its channel so the caller can cancel it. Without
 * that, an upload that fails leaves this coroutine suspended on a channel nobody reads, and the
 * enclosing `coroutineScope` waits for it for ever — the failure becomes a hang.
 */
internal fun CoroutineScope.fileAsChannel(
    path: String,
    counter: ByteCounter,
): WriterJob =
    writer {
        SystemFileSystem.source(Path(path)).buffered().use { source ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val count = source.readAtMostTo(buffer, 0, buffer.size)
                if (count <= 0) break
                channel.writeFully(buffer, 0, count)
                counter.value += count
            }
        }
    }

/** Standard input, as a channel. Its length is unknowable, which is why it is always multipart. */
internal fun CoroutineScope.stdinAsChannel(counter: ByteCounter): WriterJob =
    writer {
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val count = readStandardInput(buffer)
            if (count <= 0) break
            channel.writeFully(buffer, 0, count)
            counter.value += count
        }
    }

/**
 * Writes a response body into a file, without ever holding it whole.
 *
 * @return how many bytes were written, which is what `cp` reports. A count taken here is a count
 *   of what reached the filesystem, unlike `Content-Length`, which is a claim about what was sent.
 */
internal suspend fun ByteReadChannel.writeToFile(path: String): Long {
    var total = 0L
    SystemFileSystem.sink(Path(path)).buffered().use { sink ->
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val count = readAvailable(buffer, 0, buffer.size)
            if (count <= 0) break
            sink.write(buffer, 0, count)
            total += count
        }
    }
    return total
}

/** Writes a response body to standard output. */
internal suspend fun ByteReadChannel.writeToStandardOutput(): Long {
    var total = 0L
    val buffer = ByteArray(COPY_BUFFER)
    while (true) {
        val count = readAvailable(buffer, 0, buffer.size)
        if (count <= 0) break
        writeStandardOutput(buffer, count)
        total += count
    }
    return total
}

@OptIn(ExperimentalForeignApi::class)
private fun readStandardInput(buffer: ByteArray): Int =
    buffer.usePinned { pinned ->
        read(STDIN_FILENO, pinned.addressOf(0), buffer.size.convert()).toInt()
    }

/**
 * Writes exactly [count] bytes to standard output.
 *
 * In a loop because `write(2)` is allowed to write fewer bytes than it was given — on a pipe it
 * regularly does, and a single call would silently truncate the object being downloaded.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeStandardOutput(
    buffer: ByteArray,
    count: Int,
) {
    var written = 0
    buffer.usePinned { pinned ->
        while (written < count) {
            val result = write(STDOUT_FILENO, pinned.addressOf(written), (count - written).convert()).toInt()
            if (result <= 0) throw CliFailure("Writing to standard output failed after $written bytes")
            written += result
        }
    }
}

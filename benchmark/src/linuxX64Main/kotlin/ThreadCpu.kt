package io.github.youndie.s3.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.sysconf

/** CPU a single thread has burned, and what the kernel calls it. */
class ThreadCpu(
    val tid: String,
    val name: String,
    val seconds: Double,
)

/**
 * Per-thread CPU out of `/proc/self/task`.
 *
 * The whole reason the benchmark exists in this shape. Throughput alone cannot separate "the client
 * is the bottleneck" from "the server is": both look like a number that stops growing. A single
 * thread sitting at one whole core while the others idle is not ambiguous — and the curl engine
 * runs all of its I/O on one thread it names `curl-dispatcher`
 * (docs/research/research-architecture.md, consequence 1.6.3).
 */
@OptIn(ExperimentalForeignApi::class)
fun readThreadCpu(): List<ThreadCpu> {
    val ticksPerSecond = sysconf(platform.posix._SC_CLK_TCK).toDouble()
    val directory = opendir("/proc/self/task") ?: return emptyList()
    val threads = mutableListOf<ThreadCpu>()
    try {
        while (true) {
            val entry = readdir(directory) ?: break
            val tid = entry.pointed.d_name.toKString()
            if (tid.startsWith(".")) continue
            readStat("/proc/self/task/$tid/stat")?.let { stat ->
                // Fields 14 and 15 are utime and stime, in clock ticks. The comm field is
                // parenthesised and may itself contain spaces, so everything is counted from the
                // closing parenthesis rather than by splitting the whole line.
                val name = stat.substringAfter('(').substringBeforeLast(')')
                val after = stat.substringAfterLast(')').trim().split(' ')
                val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L
                threads += ThreadCpu(tid, name, (utime + stime) / ticksPerSecond)
            }
        }
    } finally {
        closedir(directory)
    }
    return threads.sortedByDescending { it.seconds }
}

@OptIn(ExperimentalForeignApi::class)
private fun readStat(path: String): String? {
    val file = fopen(path, "r") ?: return null
    try {
        val buffer = ByteArray(4096)
        return fgets(buffer.refTo(0), buffer.size, file)?.toKString()
    } finally {
        fclose(file)
    }
}

/** CPU burned per thread between two samples, largest first, threads that did nothing dropped. */
fun cpuDelta(
    before: List<ThreadCpu>,
    after: List<ThreadCpu>,
): List<ThreadCpu> {
    val start = before.associate { it.tid to it.seconds }
    return after
        .map { ThreadCpu(it.tid, it.name, it.seconds - (start[it.tid] ?: 0.0)) }
        .filter { it.seconds > 0.01 }
        .sortedByDescending { it.seconds }
}

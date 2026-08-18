package io.github.youndie.s3.benchmark

import io.github.youndie.s3.AddressingStyle
import io.github.youndie.s3.S3Client
import io.github.youndie.s3.S3Config
import io.github.youndie.s3.S3Credentials
import io.github.youndie.s3.S3Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.curl.Curl
import io.ktor.utils.io.ByteReadChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Answers risk 5 of the research: does uploading parts at once actually go faster, and if it stops
 * scaling, what stopped it.
 *
 * The measurement M7 could make had the server and the client on one machine, so it bounded the two
 * together and could not say which one it had hit. This one needs two: the server elsewhere, and
 * per-thread CPU sampled here (see [readThreadCpu]).
 *
 *     bench --endpoint http://10.0.0.2:9000 --bucket s3kn-bench --size 256 --runs 3
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val options =
        args
            .toList()
            .chunked(2)
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }
    val endpoint = options["--endpoint"] ?: env("S3_BENCH_ENDPOINT") ?: error("--endpoint is required")
    val bucket = options["--bucket"] ?: env("S3_BENCH_BUCKET") ?: "s3kn-bench"
    val megabytes = options["--size"]?.toInt() ?: 256
    val runs = options["--runs"]?.toInt() ?: 3
    val concurrencies = (options["--concurrency"] ?: "1,2,4,8,16").split(",").map { it.trim().toInt() }
    val partSize = (options["--part-size"]?.toLong() ?: 8L) * MIB
    val engineName = options["--engine"] ?: "curl"
    val engine = engineOf(engineName)
    // "main" is what a caller gets by default from runBlocking: one event-loop thread, and every
    // part hashed on it. "default" hands the same code a multi-threaded dispatcher.
    val dispatcherName = options["--dispatcher"] ?: "main"
    val context = if (dispatcherName == "default") Dispatchers.Default else EmptyCoroutineContext

    val config =
        S3Config(
            endpoint = S3Endpoint.parse(endpoint),
            region = "us-east-1",
            credentials =
                S3Credentials(
                    accessKeyId = env("S3_BENCH_ACCESS_KEY") ?: "s3kn-bench-access-key",
                    secretAccessKey = env("S3_BENCH_SECRET_KEY") ?: "s3kn-bench-secret-key",
                ),
            // An IP address cannot carry a bucket as a DNS label, and the link is a private one.
            addressingStyle = AddressingStyle.PATH,
            allowUnsignedPayloadOverHttp = true,
        )

    val payload = ByteArray(megabytes * MIB.toInt()) { (it % 251).toByte() }
    println(
        "engine=$engineName dispatcher=$dispatcherName endpoint=$endpoint bucket=$bucket " +
            "size=${megabytes}MiB part=${partSize / MIB}MiB runs=$runs",
    )
    println()

    runBlocking(context) {
        HttpClient(engine).use { http ->
            val client = S3Client(config, http)

            // Discarded on purpose: the first upload pays for the connection, the TLS-less
            // handshake and every page the allocator has not touched yet. Reported, it would be a
            // measurement of the warm-up (see the backlog of the neighbouring projects for how
            // convincingly two consistent warm-up numbers can lie).
            print("warm-up… ")
            upload(client, bucket, "warmup", payload, partSize, concurrency = 4)
            println("done")
            println()

            println("concurrency   best      median    worst     MiB/s(best)  busiest thread")
            for (concurrency in concurrencies) {
                val timings = mutableListOf<Duration>()
                var busiest = ""
                repeat(runs) { run ->
                    val before = readThreadCpu()
                    val mark = TimeSource.Monotonic.markNow()
                    upload(client, bucket, "c$concurrency-$run", payload, partSize, concurrency)
                    val elapsed = mark.elapsedNow()
                    val delta = cpuDelta(before, readThreadCpu())
                    timings += elapsed
                    if (run == 0) {
                        val top = delta.firstOrNull()
                        busiest =
                            if (top == null) {
                                "-"
                            } else {
                                val share = top.seconds / elapsed.inWholeMilliseconds.toDouble() * 1000.0
                                "${top.name} ${format(top.seconds)}s (${(share * 100).toInt()}% of a core)"
                            }
                    }
                }
                val sorted = timings.sorted()
                val best = sorted.first()
                val throughput = megabytes.toDouble() / (best.inWholeMilliseconds / 1000.0)
                println(
                    concurrency.toString().padEnd(14) +
                        sorted.joinToString("") { "${format(it.inWholeMilliseconds / 1000.0)}s".padEnd(10) } +
                        format(throughput).padEnd(13) +
                        busiest,
                )
            }

            println()
            println("threads that burned CPU over the whole run:")
            readThreadCpu().filter { it.seconds > 0.01 }.forEach {
                println("  ${it.name.padEnd(20)} ${format(it.seconds)}s")
            }
        }
    }
}

/**
 * The engine under test.
 *
 * `CIO` is here and nowhere else in the project: it cannot serve the library, because
 * `ktor-network-tls` throws on Kotlin/Native and the engine would have no HTTPS
 * (docs/research/research-architecture.md, fact 1.1). Over plain HTTP it runs, which is enough to
 * ask the one question this binary is for — whether curl's single dispatcher thread is what holds
 * the ceiling, or whether the ceiling is somewhere both engines share.
 */
private fun engineOf(name: String): HttpClientEngineFactory<*> =
    when (name) {
        "curl" -> Curl
        "cio" -> CIO
        else -> error("unknown engine: $name (curl, cio)")
    }

private suspend fun upload(
    client: S3Client,
    bucket: String,
    key: String,
    payload: ByteArray,
    partSize: Long,
    concurrency: Int,
) {
    client.putMultipart(
        bucket = bucket,
        key = "bench/$key.bin",
        body = ByteReadChannel(payload),
        partSize = partSize,
        concurrency = concurrency,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()?.ifEmpty { null }

private fun format(value: Double): String {
    val rounded = (value * 100).toLong()
    return "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
}

private const val MIB = 1024L * 1024L

package io.github.youndie.s3.testing

import io.github.youndie.kontainer.Fixture
import io.github.youndie.kontainer.Probe
import io.github.youndie.kontainer.ProbeFailure
import io.github.youndie.kontainer.docker.DockerEngine
import io.github.youndie.kontainer.docker.DockerError
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * An S3 server of this process's own — the SeaweedFS of the repository's `docker-compose.yml` (M-130) — through
 * [kontainer](https://github.com/youndie/kontainer), on a host port kontainer chooses (M-129).
 *
 * Started once, by the first live test that asks, and ready only when the server answers its health check and
 * the buckets exist. It is not removed at the end: a native test process has no hook there, so the next run's
 * kontainer removes it, as it does every fixture whose owner process is gone.
 */
internal actual fun ownServerEndpoint(): String? = own

private val own: String? by lazy {
    try {
        runBlocking { start() }
    } catch (_: DockerError.SocketNotFound) {
        // No Docker Engine here: the tests skip, or fail if S3_E2E_REQUIRED says they must run.
        null
    }
}

private suspend fun start(): String {
    val compose = SPEC_PATH.substringBeforeLast("/docs/spec") + "/docker-compose.yml"
    val fixture = Fixture.owned(listOf(compose), ports = mapOf(SERVER to listOf(API)))
    fixture.up()
    fixture.awaitReady(SERVER, API, Probe.http("/healthz", 200), timeout = READY)
    DockerEngine.fromEnvironment().use { engine ->
        // The buckets come from a one-shot service; the server is ready for the tests once it has exited cleanly.
        val bucketsMade =
            Probe.custom { _, _ ->
                val made = engine.inspect(fixture.containerId("create-buckets"))
                if (made.running) throw ProbeFailure("the buckets are still being created")
                if (made.exitCode != 0) throw ProbeFailure("create-buckets exited with ${made.exitCode}")
            }
        fixture.awaitReady(SERVER, API, bucketsMade, timeout = READY)
    }
    return "http://127.0.0.1:${fixture.port(SERVER, API)}"
}

private const val SERVER = "s3"
private const val API = 9000
private val READY = 60.seconds

package io.github.youndie.s3.cli

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/**
 * The engine on Apple platforms.
 *
 * This target exists so the local loop has somewhere to run tests — `linuxX64Test` is disabled on a
 * macOS host — and the binary it produces is a working `s3kn` on a laptop. Nothing publishes it.
 */
internal actual fun cliEngine(): HttpClientEngineFactory<*> = Darwin

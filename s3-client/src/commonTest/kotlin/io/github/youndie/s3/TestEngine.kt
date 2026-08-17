package io.github.youndie.s3

import io.ktor.client.HttpClient

/**
 * A client with a real engine, for the tests that talk to a real server.
 *
 * Declared per platform because no single engine covers both: on Kotlin/Native only
 * `ktor-client-curl` speaks HTTPS (docs/research/research-architecture.md, fact 1.1), and it does
 * not exist on the JVM. The native tests therefore exercise the engine the library is actually
 * deployed with.
 */
expect fun realHttpClient(): HttpClient

package io.github.youndie.s3.cli

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

/**
 * The only engine on this target that speaks HTTPS.
 *
 * Not a preference: `ktor-client-cio` depends on `ktor-network-tls`, where Kotlin/Native hits
 * `error("TLS sessions are not supported on Native platform.")`
 * (docs/research/research-architecture.md, fact 1.1). It also brings its own OpenSSL, statically,
 * which is why the image needs no `libssl` and does need `ca-certificates`.
 */
internal actual fun cliEngine(): HttpClientEngineFactory<*> = Curl

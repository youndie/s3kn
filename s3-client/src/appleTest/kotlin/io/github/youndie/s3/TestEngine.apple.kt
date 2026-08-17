package io.github.youndie.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * NSURLSession. On iOS it is not a preference — `ktor-client-curl` publishes no iOS target at all
 * (docs/research/research-architecture.md, fact 1.11).
 *
 * It also settles the certificate question that costs a container an `apt install`: the roots come
 * from the system trust store rather than from a file the image has to carry.
 */
actual fun realHttpClient(): HttpClient = HttpClient(Darwin)
